package com.team.pricecompare.engine.match

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 Kotlin 单测：归一化、同店分组、同品三级配对（不依赖 Android 运行时）。
 */
class StoreMatcherTest {

    private fun store(
        platform: String,
        name: String,
        items: List<ItemPrice> = emptyList(),
    ) = StoreInfo(
        platform = platform,
        storeName = name,
        rating = 0.0,
        monthlySales = 0,
        deliveryFee = 0.0,
        minOrder = 0.0,
        discounts = emptyList(),
        items = items,
        capturedAt = 0L,
    )

    @Test
    fun `归一化去空白与括号内容`() {
        assertEquals("老乡鸡", StoreMatcher.normalizeStoreName("老乡鸡(华科店)"))
        assertEquals("老乡鸡", StoreMatcher.normalizeStoreName("老乡鸡（华科店）"))
        assertEquals("老乡鸡", StoreMatcher.normalizeStoreName("  老 乡 鸡 "))
        assertEquals("麦当劳", StoreMatcher.normalizeStoreName("麦当劳【光谷店】"))
    }

    @Test
    fun `归一化全角转半角`() {
        assertEquals("ABC咖啡", StoreMatcher.normalizeStoreName("ＡＢＣ咖啡"))
        assertEquals("KFC", StoreMatcher.normalizeStoreName("ＫＦＣ"))
    }

    @Test
    fun `同店多平台归为一组`() {
        val stores = listOf(
            store("meituan", "老乡鸡(华科店)"),
            store("flash", "老乡鸡（华科店）"),
            store("meituan", "麦当劳"),
        )
        val groups = StoreMatcher.matchStores(stores)
        assertEquals(2, groups.size)
        val lxq = groups.first { it.size == 2 }
        assertEquals(setOf("meituan", "flash"), lxq.map { it.platform }.toSet())
    }

    @Test
    fun `归一化后仍不相等的店名不归为一组`() {
        val groups = StoreMatcher.matchStores(
            listOf(store("meituan", "老乡鸡"), store("flash", "老乡鸡快餐")),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `同名商品跨平台自动配对`() {
        val a = store(
            "meituan", "老乡鸡",
            items = listOf(
                ItemPrice("香辣鸡腿堡", 19.9, 1.0),
                ItemPrice("老母鸡汤", 15.0, 0.0),
                ItemPrice("独占菜", 9.9, 0.0),
            ),
        )
        val b = store(
            "flash", "老乡鸡(华科店)",
            items = listOf(
                ItemPrice("香辣鸡腿堡 ", 18.9, 2.0),
                ItemPrice("老母鸡汤", 14.5, 0.0),
            ),
        )
        val result = StoreMatcher.matchItems(a, b)
        assertEquals(2, result.auto.size)
        assertEquals("香辣鸡腿堡", result.auto[0].first.name)
        assertEquals("香辣鸡腿堡 ", result.auto[0].second.name)
        assertEquals("老母鸡汤", result.auto[1].first.name)
        assertEquals(listOf("独占菜"), result.unmatchedA.map { it.name })
    }

    @Test
    fun `无同名且不相似的商品不配对`() {
        val a = store("meituan", "老乡鸡", items = listOf(ItemPrice("鸡腿堡", 1.0, 0.0)))
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("牛肉粉", 2.0, 0.0)))
        val result = StoreMatcher.matchItems(a, b)
        assertTrue(result.auto.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertEquals(1, result.unmatchedA.size)
    }

    @Test
    fun `高相似度商品自动配对`() {
        // bigram 相似度 7/8 ≈ 0.875 ≥ AUTO_MATCH_THRESHOLD
        val a = store("meituan", "老乡鸡", items = listOf(ItemPrice("招牌肥西老母鸡汤面", 18.0, 0.0)))
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("招牌肥西老母鸡汤", 17.0, 0.0)))
        val result = StoreMatcher.matchItems(a, b)
        assertEquals(1, result.auto.size)
        assertTrue(result.pending.isEmpty())
    }

    @Test
    fun `中等相似度商品进入待确认`() {
        // bigram 相似度 5/7 ≈ 0.714，落在 [CONFIRM, AUTO) 区间
        val a = store("meituan", "老乡鸡", items = listOf(ItemPrice("招牌肥西老母鸡汤", 18.0, 0.0)))
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("肥西老母鸡汤", 17.0, 0.0)))
        val result = StoreMatcher.matchItems(a, b)
        assertTrue(result.auto.isEmpty())
        assertEquals(1, result.pending.size)
        val pending = result.pending[0]
        assertEquals("招牌肥西老母鸡汤", pending.a.name)
        assertEquals("肥西老母鸡汤", pending.b?.name)
        assertTrue(pending.needsConfirm)
        assertTrue(pending.similarity >= MatcherRules.CONFIRM_MATCH_THRESHOLD)
        assertTrue(pending.similarity < MatcherRules.AUTO_MATCH_THRESHOLD)
    }

    @Test
    fun `用户确认过的配对直通自动配对`() {
        val a = store("meituan", "老乡鸡", items = listOf(ItemPrice("招牌肥西老母鸡汤", 18.0, 0.0)))
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("肥西老母鸡汤", 17.0, 0.0)))
        // 确认记忆使用归一化后的名对
        val confirmed = setOf("招牌肥西老母鸡汤" to "肥西老母鸡汤")
        val result = StoreMatcher.matchItems(a, b, confirmed)
        assertEquals(1, result.auto.size)
        assertEquals("肥西老母鸡汤", result.auto[0].second.name)
        assertTrue(result.pending.isEmpty())
    }

    @Test
    fun `待确认配对同样占用 b 侧商品`() {
        // b 侧只有一个候选，两个相似的 a 商品只能有一个进入 pending
        val a = store(
            "meituan", "老乡鸡",
            items = listOf(
                ItemPrice("招牌肥西老母鸡汤", 18.0, 0.0),
                ItemPrice("肥西老母鸡汤大份", 20.0, 0.0),
            ),
        )
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("肥西老母鸡汤", 17.0, 0.0)))
        val result = StoreMatcher.matchItems(a, b)
        assertTrue(result.auto.isEmpty())
        assertEquals(1, result.pending.size)
        assertEquals(1, result.unmatchedA.size)
    }

    @Test
    fun `一个 b 商品不会被重复配对`() {
        // 「老母鸡汤」先完全相等占掉 b 侧唯一商品，「招牌老母鸡汤」配对无路可走
        val a = store(
            "meituan", "老乡鸡",
            items = listOf(
                ItemPrice("招牌老母鸡汤", 18.0, 0.0),
                ItemPrice("老母鸡汤", 15.0, 0.0),
            ),
        )
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("老母鸡汤", 14.0, 0.0)))
        val result = StoreMatcher.matchItems(a, b)
        assertEquals(1, result.auto.size)
        assertEquals("老母鸡汤", result.auto[0].first.name)
        assertTrue(result.pending.isEmpty())
    }

    @Test
    fun `itemSimilarity 边界值`() {
        assertEquals(1.0, StoreMatcher.itemSimilarity("老母鸡汤", "老母鸡汤"), 0.0001)
        // 归一化后相同也应为 1.0
        assertEquals(1.0, StoreMatcher.itemSimilarity("老母鸡汤", " 老母鸡汤（小份）"), 0.0001)
        // 完全不相关接近 0
        assertTrue(StoreMatcher.itemSimilarity("鸡腿堡", "牛肉粉") < 0.1)
        // 前缀差异但主体相同的相似度应达到待确认阈值
        assertTrue(
            StoreMatcher.itemSimilarity("招牌肥西老母鸡汤", "肥西老母鸡汤") >=
                MatcherRules.CONFIRM_MATCH_THRESHOLD,
        )
    }
}
