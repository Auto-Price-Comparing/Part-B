package com.team.pricecompare.engine

import com.team.pricecompare.Deal
import com.team.pricecompare.StoreInfo
import com.team.pricecompare.engine.match.ItemMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 悬浮窗消费的比价状态（采集层写入，UI 层只读）。 */
sealed class OverlayState {
    /** 等待用户进入店铺菜单页。 */
    data object Waiting : OverlayState()

    /** 页面不支持或解析失败。 */
    data class Unsupported(val message: String) : OverlayState()

    /** 仅有一侧平台数据。 */
    data class SinglePlatform(
        val store: StoreInfo,
        val deal: Deal,
        val hint: String,
    ) : OverlayState()

    /** 同店跨平台比价；[pending] 为疑似同品待用户确认的配对。 */
    data class Comparing(
        val storeName: String,
        val currentPlatform: String,
        val deals: List<Deal>,
        val matchedItemCount: Int,
        val pending: List<ItemMatch> = emptyList(),
    ) : OverlayState()
}

/**
 * M1 全局状态总线：DumpAccessibilityService 解析完成后发布，
 * OverlayService / MainActivity 订阅更新。
 */
object CaptureHub {

    private val _state = MutableStateFlow<OverlayState>(OverlayState.Waiting)
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    /** 最近一次解析摘要，供 MainActivity 状态页展示。 */
    @Volatile
    var lastSummary: String = "等待进入店铺菜单页…"
        private set

    /** 最近一次状态发布时间戳，供悬浮窗展示「更新于 HH:mm」。 */
    @Volatile
    var lastUpdatedAt: Long = 0L
        private set

    fun publishWaiting() {
        lastSummary = "等待进入店铺菜单页…"
        lastUpdatedAt = System.currentTimeMillis()
        _state.value = OverlayState.Waiting
    }

    fun publishUnsupported(message: String) {
        lastSummary = message
        lastUpdatedAt = System.currentTimeMillis()
        _state.value = OverlayState.Unsupported(message)
    }

    /** M4 一键全采进度：只更新摘要文本，不改变悬浮窗的比价状态。 */
    fun publishProgress(message: String) {
        lastSummary = message
        lastUpdatedAt = System.currentTimeMillis()
    }

    fun publishSingle(store: StoreInfo, deal: Deal) {
        val hint = "暂无另一平台同店数据，请先去对比平台浏览该店"
        lastSummary = "${platformLabel(store.platform)} · ${store.storeName} · ¥${deal.finalPrice}"
        lastUpdatedAt = System.currentTimeMillis()
        _state.value = OverlayState.SinglePlatform(store, deal, hint)
    }

    fun publishComparison(
        current: StoreInfo,
        deals: List<Deal>,
        matchedItemCount: Int,
        pending: List<ItemMatch> = emptyList(),
    ) {
        val cheapest = deals.minByOrNull { it.finalPrice }
        lastSummary = buildString {
            append("${current.storeName} · 匹配${matchedItemCount}件 · ")
            append(deals.joinToString(" vs ") { "${platformLabel(it.platform)}¥${it.finalPrice}" })
            if (cheapest != null) append(" · 最低${platformLabel(cheapest.platform)}")
            if (pending.isNotEmpty()) append(" · ${pending.size}对待确认")
        }
        lastUpdatedAt = System.currentTimeMillis()
        _state.value = OverlayState.Comparing(
            storeName = current.storeName,
            currentPlatform = current.platform,
            deals = deals,
            matchedItemCount = matchedItemCount,
            pending = pending,
        )
    }

    fun platformLabel(platform: String): String = when (platform) {
        "meituan" -> "美团"
        "flash" -> "闪购"
        else -> platform
    }
}
