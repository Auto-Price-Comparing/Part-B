package com.team.pricecompare.engine.match

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 Kotlin 单测：归一化、同店分组、同品配对（不依赖 Android 运行时）。
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
    fun `同名商品跨平台配对`() {
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
        val pairs = StoreMatcher.matchItems(a, b)
        assertEquals(2, pairs.size)
        assertEquals("香辣鸡腿堡", pairs[0].first.name)
        assertEquals("香辣鸡腿堡 ", pairs[0].second.name)
        assertEquals("老母鸡汤", pairs[1].first.name)
    }

    @Test
    fun `无同名商品时配对为空`() {
        val a = store("meituan", "老乡鸡", items = listOf(ItemPrice("鸡腿堡", 1.0, 0.0)))
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("牛肉粉", 2.0, 0.0)))
        assertTrue(StoreMatcher.matchItems(a, b).isEmpty())
    }

    @Test
    fun `相似商品名可模糊配对`() {
        val a = store("meituan", "老乡鸡", items = listOf(ItemPrice("招牌肥西老母鸡汤", 18.0, 0.0)))
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("肥西老母鸡汤", 17.0, 0.0)))
        val pairs = StoreMatcher.matchItems(a, b)
        assertEquals(1, pairs.size)
        assertEquals("招牌肥西老母鸡汤", pairs[0].first.name)
        assertEquals("肥西老母鸡汤", pairs[0].second.name)
    }

    @Test
    fun `完全不相关的商品模糊配对不上`() {
        val a = store("meituan", "老乡鸡", items = listOf(ItemPrice("香辣鸡腿堡", 10.0, 0.0)))
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("红烧牛肉面", 10.0, 0.0)))
        assertTrue(StoreMatcher.matchItems(a, b).isEmpty())
    }

    @Test
    fun `一个 b 商品不会被重复配对`() {
        // 「老母鸡汤」先完全相等占掉 b 侧唯一商品，「招牌老母鸡汤」模糊配对无路可走
        val a = store(
            "meituan", "老乡鸡",
            items = listOf(
                ItemPrice("招牌老母鸡汤", 18.0, 0.0),
                ItemPrice("老母鸡汤", 15.0, 0.0),
            ),
        )
        val b = store("flash", "老乡鸡", items = listOf(ItemPrice("老母鸡汤", 14.0, 0.0)))
        val pairs = StoreMatcher.matchItems(a, b)
        assertEquals(1, pairs.size)
        assertEquals("老母鸡汤", pairs[0].first.name)
    }

    @Test
    fun `itemSimilarity 边界值`() {
        assertEquals(1.0, StoreMatcher.itemSimilarity("老母鸡汤", "老母鸡汤"), 0.0001)
        // 归一化后相同也应为 1.0
        assertEquals(1.0, StoreMatcher.itemSimilarity("老母鸡汤", " 老母鸡汤（小份）"), 0.0001)
        // 完全不相关接近 0
        assertTrue(StoreMatcher.itemSimilarity("鸡腿堡", "牛肉粉") < 0.1)
        // 前缀差异但主体相同的相似度应达到模糊配对阈值
        assertTrue(
            StoreMatcher.itemSimilarity("招牌肥西老母鸡汤", "肥西老母鸡汤") >=
                MatcherRules.ITEM_SIMILARITY_THRESHOLD,
        )
    }
}
