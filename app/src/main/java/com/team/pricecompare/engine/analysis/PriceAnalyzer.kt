package com.team.pricecompare.engine.analysis

import com.team.pricecompare.StoreInfo
import com.team.pricecompare.engine.data.StoreInfoCodec
import com.team.pricecompare.engine.data.StoreSnapshot

// ================= ANALYSIS 常量区（分析口径调整只改这里） =================
object AnalysisRules {
    /** 浮点价格比较容差：差值小于该值视为未变价。 */
    const val PRICE_EPSILON = 1e-6
}
// ============================================================================

/** 一次商品价格变动：从 oldPrice 变为 newPrice，发生在 changedAt 时刻的快照中。 */
data class PriceChange(
    val itemName: String,
    val oldPrice: Double,
    val newPrice: Double,
    val changedAt: Long,
)

/** 单店单平台的分析汇总：快照数、评分/销量首尾趋势、最新菜单条目数、变价记录。 */
data class StoreAnalysis(
    val storeName: String,
    val platform: String,
    val snapshotCount: Int,
    val ratingTrend: Pair<Double, Double>?, // (最早评分, 最新评分)；快照不足 2 条为 null
    val salesTrend: Pair<Int, Int>?, // (最早月售, 最新月售)；快照不足 2 条为 null
    val itemCountLatest: Int,
    val priceChanges: List<PriceChange>,
)

/**
 * 商家分析引擎 —— M3。
 * 纯逻辑，不直接碰 Room：输入反序列化后的 StoreInfo 列表，或经 [analyze] 传入
 * StoreSnapshot 由内部用 StoreInfoCodec 反序列化（坏数据跳过，优雅降级）。
 */
object PriceAnalyzer {

    /**
     * 某商品的价格轨迹，按采集时间升序。
     * 快照中不含该商品的条目直接跳过；商品名比较为精确匹配。
     */
    fun itemPriceTrend(snapshots: List<StoreInfo>, itemName: String): List<Pair<Long, Double>> =
        snapshots.sortedBy { it.capturedAt }
            .mapNotNull { store ->
                store.items.firstOrNull { it.name == itemName }
                    ?.let { store.capturedAt to it.price }
            }

    /**
     * 汇总同一店铺同一平台的一串快照（调用方保证同店同平台，内部不校验）。
     * 空输入返回全空汇总（snapshotCount = 0，趋势为 null），不抛异常。
     */
    fun storeSummary(snapshots: List<StoreInfo>): StoreAnalysis {
        val sorted = snapshots.sortedBy { it.capturedAt }
        val first = sorted.firstOrNull()
        val latest = sorted.lastOrNull()
        return StoreAnalysis(
            storeName = latest?.storeName ?: "",
            platform = latest?.platform ?: "",
            snapshotCount = sorted.size,
            ratingTrend = if (sorted.size >= 2 && first != null && latest != null) {
                first.rating to latest.rating
            } else {
                null
            },
            salesTrend = if (sorted.size >= 2 && first != null && latest != null) {
                first.monthlySales to latest.monthlySales
            } else {
                null
            },
            itemCountLatest = latest?.items?.size ?: 0,
            priceChanges = detectPriceChanges(sorted),
        )
    }

    /**
     * Room 入口：反序列化快照 → 按 (平台, 店名) 分组 → 逐组汇总。
     * payloadJson 损坏的快照跳过该条，不影响其余数据。
     */
    fun analyze(snapshots: List<StoreSnapshot>): List<StoreAnalysis> =
        snapshots.mapNotNull { snap ->
            runCatching { StoreInfoCodec.fromJson(snap.payloadJson) }.getOrNull()
        }
            .groupBy { it.platform to it.storeName }
            .values
            .map { storeSummary(it) }

    /**
     * 逐屏对比相邻快照中的同名商品价：记录每次变价（涨/降都算），
     * changedAt 取变价后那条快照的采集时间。商品下架后重新上架按新商品处理，
     * 不与下架前价格对比。
     */
    private fun detectPriceChanges(sorted: List<StoreInfo>): List<PriceChange> {
        val lastPriceByItem = mutableMapOf<String, Double>()
        val changes = mutableListOf<PriceChange>()
        for (store in sorted) {
            val currentNames = store.items.map { it.name }.toSet()
            // 已下架商品从历史价格表中移除，重新上架不与旧价对比
            lastPriceByItem.keys.retainAll(currentNames)
            for (item in store.items) {
                val old = lastPriceByItem[item.name]
                if (old != null && kotlin.math.abs(item.price - old) >= AnalysisRules.PRICE_EPSILON) {
                    changes.add(PriceChange(item.name, old, item.price, store.capturedAt))
                }
                lastPriceByItem[item.name] = item.price
            }
        }
        return changes
    }
}
