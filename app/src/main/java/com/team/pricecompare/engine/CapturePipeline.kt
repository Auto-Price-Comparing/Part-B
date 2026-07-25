package com.team.pricecompare.engine

import android.content.Context
import android.util.Log
import com.team.pricecompare.StoreInfo
import com.team.pricecompare.accessibility.PageRouter
import com.team.pricecompare.accessibility.PageType
import com.team.pricecompare.accessibility.SimpleNode
import com.team.pricecompare.engine.data.AppDatabase
import com.team.pricecompare.engine.data.CouponRepository
import com.team.pricecompare.engine.data.StoreInfoCodec
import com.team.pricecompare.engine.data.StoreSnapshot
import com.team.pricecompare.engine.match.StoreMatcher
import com.team.pricecompare.engine.pricing.CartItem
import com.team.pricecompare.engine.pricing.Coupon
import com.team.pricecompare.engine.pricing.PricingEngine
import com.team.pricecompare.parsers.FlashParser
import com.team.pricecompare.parsers.MeituanParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * M1 采集流水线：页面路由 → 解析 → Room 持久化 → 跨平台匹配 → 实付价估算 → CaptureHub 发布。
 * 由 DumpAccessibilityService 在 dump 后调用；失败一律优雅降级，不抛未捕获异常。
 */
object CapturePipeline {

    private const val TAG = "CapturePipeline"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestByPlatform = ConcurrentHashMap<String, StoreInfo>()

    fun process(context: Context, pkg: String, tree: SimpleNode) {
        scope.launch {
            runCatching { processInternal(context.applicationContext, pkg, tree) }
                .onFailure { Log.w(TAG, "pipeline failed", it) }
        }
    }

    /**
     * M4 一键全采用：与 [process] 相同的逻辑，但 suspend 等待持久化/比价完成，
     * 便于编排器顺序切换平台。失败同样优雅降级，不抛异常。
     */
    suspend fun processAwait(context: Context, pkg: String, tree: SimpleNode) {
        runCatching { processInternal(context.applicationContext, pkg, tree) }
            .onFailure { Log.w(TAG, "pipeline failed", it) }
    }

    private suspend fun processInternal(context: Context, pkg: String, tree: SimpleNode) {
        val platform = platformForPackage(pkg) ?: return
        // 红包读取失败只丢红包，绝不允许拖垮整条流水线
        val coupons = runCatching {
            CouponRepository(context).list().map { Coupon(it.platform, it.threshold, it.amount) }
        }.getOrDefault(emptyList())
        when (PageRouter.classify(platform, tree)) {
            PageType.STORE_MENU -> {
                val store = parseStore(platform, tree)
                if (store == null) {
                    CaptureHub.publishUnsupported("该页面暂不支持")
                    return
                }
                latestByPlatform[platform] = store
                persist(context, store)
                val counterpart = findCounterpart(context, store)
                if (counterpart == null) {
                    // 单平台场景：按「菜单每样一件」估算实付，算不出则降级提示
                    val deal = PricingEngine.calcDeal(store, wholeMenuCart(store), coupons)
                    if (deal == null) CaptureHub.publishUnsupported("无法计算实付价")
                    else CaptureHub.publishSingle(store, deal)
                    return
                }
                // 跨平台场景：以匹配上的同品各一件为购物车，两平台分别计价
                val matched = StoreMatcher.matchItems(store, counterpart)
                val cart = matched.map { CartItem(it.first.name, 1) }
                val deals = PricingEngine.bestDeal(listOf(store, counterpart), cart, coupons)
                if (deals.isEmpty()) {
                    CaptureHub.publishUnsupported("两平台均无法计算实付价")
                    return
                }
                CaptureHub.publishComparison(store, deals, matched.size)
            }
            PageType.SEARCH_RESULT -> CaptureHub.publishUnsupported("搜索结果页暂不支持")
            PageType.OTHER -> CaptureHub.publishUnsupported("该页面暂不支持")
        }
    }

    internal fun platformForPackage(pkg: String): String? = when (pkg) {
        "com.meituan.takeout" -> "meituan"
        "me.ele", "com.taobao.taobao" -> "flash"
        else -> null
    }

    internal fun parseStore(platform: String, tree: SimpleNode): StoreInfo? = when (platform) {
        "flash" -> FlashParser.parseStorePage(tree)
        "meituan" -> MeituanParser.parseStorePage(tree)
        else -> null
    }

    /** 单平台场景的默认估算口径：菜单每样一件。 */
    private fun wholeMenuCart(store: StoreInfo): List<CartItem> =
        store.items.map { CartItem(it.name, 1) }

    private suspend fun persist(context: Context, store: StoreInfo) {
        AppDatabase.get(context).storeDao().insert(
            StoreSnapshot(
                platform = store.platform,
                storeName = store.storeName,
                payloadJson = StoreInfoCodec.toJson(store),
                capturedAt = store.capturedAt,
            ),
        )
    }

    private suspend fun findCounterpart(context: Context, current: StoreInfo): StoreInfo? {
        val otherPlatform = if (current.platform == "meituan") "flash" else "meituan"
        val normalized = StoreMatcher.normalizeStoreName(current.storeName)

        latestByPlatform[otherPlatform]?.let { cached ->
            if (StoreMatcher.normalizeStoreName(cached.storeName) == normalized) return cached
        }

        for (snap in AppDatabase.get(context).storeDao().latest()) {
            if (snap.platform != otherPlatform) continue
            val store = runCatching { StoreInfoCodec.fromJson(snap.payloadJson) }.getOrNull() ?: continue
            if (StoreMatcher.normalizeStoreName(store.storeName) == normalized) return store
        }
        return null
    }
}
