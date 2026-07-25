package com.team.pricecompare.engine.analysis

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import com.team.pricecompare.engine.data.StoreInfoCodec
import com.team.pricecompare.engine.data.StoreSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 Kotlin 单测：价格轨迹、店铺汇总、变价检测（不依赖 Android 运行时）。
 */
class PriceAnalyzerTest {

    private fun store(
        platform: String = "meituan",
        name: String = "老乡鸡",
        rating: Double = 4.5,
        sales: Int = 1000,
        items: List<ItemPrice> = listOf(ItemPrice("老母鸡汤", 15.0, 0.0)),
        at: Long = 0L,
    ) = StoreInfo(
        platform = platform,
        storeName = name,
        rating = rating,
        monthlySales = sales,
        deliveryFee = 3.0,
        minOrder = 20.0,
        discounts = emptyList(),
        items = items,
        capturedAt = at,
    )

    private fun snapshot(store: StoreInfo) = StoreSnapshot(
        platform = store.platform,
        storeName = store.storeName,
        payloadJson = StoreInfoCodec.toJson(store),
        capturedAt = store.capturedAt,
    )

    @Test
    fun `空输入返回全空汇总`() {
        val summary = PriceAnalyzer.storeSummary(emptyList())
        assertEquals(0, summary.snapshotCount)
        assertNull(summary.ratingTrend)
        assertNull(summary.salesTrend)
        assertEquals(0, summary.itemCountLatest)
        assertTrue(summary.priceChanges.isEmpty())
    }

    @Test
    fun `快照不足两条时趋势为 null`() {
        val summary = PriceAnalyzer.storeSummary(listOf(store(at = 100L)))
        assertEquals(1, summary.snapshotCount)
        assertNull(summary.ratingTrend)
        assertNull(summary.salesTrend)
    }

    @Test
    fun `评分与销量取首尾趋势且按时间排序`() {
        val summary = PriceAnalyzer.storeSummary(
            listOf(
                store(rating = 4.8, sales = 2000, at = 200L),
                store(rating = 4.5, sales = 1000, at = 100L),
            ),
        )
        assertEquals(4.5 to 4.8, summary.ratingTrend)
        assertEquals(1000 to 2000, summary.salesTrend)
    }

    @Test
    fun `同名商品涨价降价都记录且时间为变价后快照`() {
        val summary = PriceAnalyzer.storeSummary(
            listOf(
                store(items = listOf(ItemPrice("老母鸡汤", 15.0, 0.0)), at = 100L),
                store(items = listOf(ItemPrice("老母鸡汤", 18.0, 0.0)), at = 200L),
                store(items = listOf(ItemPrice("老母鸡汤", 16.0, 0.0)), at = 300L),
            ),
        )
        assertEquals(2, summary.priceChanges.size)
        assertEquals(PriceChange("老母鸡汤", 15.0, 18.0, 200L), summary.priceChanges[0])
        assertEquals(PriceChange("老母鸡汤", 18.0, 16.0, 300L), summary.priceChanges[1])
    }

    @Test
    fun `商品下架后重新上架不与旧价对比`() {
        val summary = PriceAnalyzer.storeSummary(
            listOf(
                store(items = listOf(ItemPrice("老母鸡汤", 15.0, 0.0)), at = 100L),
                // 200L 时商品下架（菜单为空）
                store(items = emptyList(), at = 200L),
                // 300L 重新上架且价格不同，不应记为变价
                store(items = listOf(ItemPrice("老母鸡汤", 20.0, 0.0)), at = 300L),
            ),
        )
        assertTrue(summary.priceChanges.isEmpty())
    }

    @Test
    fun `itemPriceTrend 按时间升序且跳过缺货快照`() {
        val trend = PriceAnalyzer.itemPriceTrend(
            listOf(
                store(items = listOf(ItemPrice("老母鸡汤", 18.0, 0.0)), at = 200L),
                store(items = emptyList(), at = 150L),
                store(items = listOf(ItemPrice("老母鸡汤", 15.0, 0.0)), at = 100L),
            ),
            "老母鸡汤",
        )
        assertEquals(listOf(100L to 15.0, 200L to 18.0), trend)
    }

    @Test
    fun `analyze 按平台店名分组且跳过损坏数据`() {
        val good = snapshot(store(at = 100L))
        val damaged = StoreSnapshot(
            platform = "flash",
            storeName = "老乡鸡",
            payloadJson = "{not-json",
            capturedAt = 100L,
        )
        val result = PriceAnalyzer.analyze(listOf(good, damaged))
        assertEquals(1, result.size)
        assertEquals("meituan", result[0].platform)
        assertEquals("老乡鸡", result[0].storeName)
        assertEquals(1, result[0].snapshotCount)
    }

    @Test
    fun `analyze 同店不同平台分为两组`() {
        val result = PriceAnalyzer.analyze(
            listOf(
                snapshot(store(platform = "meituan", at = 100L)),
                snapshot(store(platform = "flash", at = 100L)),
            ),
        )
        assertEquals(2, result.size)
        assertEquals(setOf("meituan", "flash"), result.map { it.platform }.toSet())
    }
}
