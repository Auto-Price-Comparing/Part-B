package com.team.pricecompare.engine.pricing

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 Kotlin 单测：满减取档、起送门槛、breakdown 明细（不依赖 Android 运行时）。
 */
class PricingEngineTest {

    private fun store(
        platform: String = "meituan",
        deliveryFee: Double = 3.0,
        minOrder: Double = 20.0,
        discounts: List<String> = emptyList(),
        items: List<ItemPrice> = listOf(
            ItemPrice("香辣鸡腿堡", 20.0, 1.0),
            ItemPrice("老母鸡汤", 15.0, 0.0),
        ),
    ) = StoreInfo(
        platform = platform,
        storeName = "老乡鸡",
        rating = 0.0,
        monthlySales = 0,
        deliveryFee = deliveryFee,
        minOrder = minOrder,
        discounts = discounts,
        items = items,
        capturedAt = 0L,
    )

    @Test
    fun `无满减时实付为小计加配送费`() {
        val deal = PricingEngine.calcDeal(
            store(deliveryFee = 3.0, minOrder = 20.0),
            listOf(CartItem("香辣鸡腿堡", 1), CartItem("老母鸡汤", 1)),
        )!!
        // (20+1) + 15 = 36.0，+3 配送费
        assertEquals(39.0, deal.finalPrice, 0.001)
        assertEquals("meituan", deal.platform)
    }

    @Test
    fun `满减取满足门槛的最高档`() {
        val s = store(
            deliveryFee = 3.0,
            minOrder = 0.0,
            discounts = listOf("满20减5", "满40减12", "满100减30"),
        )
        // 小计 21.0 + 30.0 = 51.0：满100 达不到，取 满40减12
        val deal = PricingEngine.calcDeal(s, listOf(CartItem("香辣鸡腿堡", 1), CartItem("老母鸡汤", 2)))!!
        assertEquals(51.0 + 3.0 - 12.0, deal.finalPrice, 0.001)
        assertTrue(deal.breakdown.any { it.contains("满40减12") })
    }

    @Test
    fun `未达起送价返回 null`() {
        val s = store(minOrder = 30.0)
        // 小计 21.0 < 30.0
        assertNull(PricingEngine.calcDeal(s, listOf(CartItem("香辣鸡腿堡", 1))))
    }

    @Test
    fun `购物车商品不在菜单中返回 null`() {
        assertNull(PricingEngine.calcDeal(store(), listOf(CartItem("不存在的菜", 1))))
    }

    @Test
    fun `空购物车返回 null`() {
        assertNull(PricingEngine.calcDeal(store(), emptyList()))
    }

    @Test
    fun `breakdown 包含小计配送费满减与实付`() {
        val deal = PricingEngine.calcDeal(
            store(deliveryFee = 3.0, minOrder = 0.0, discounts = listOf("满40减12")),
            listOf(CartItem("香辣鸡腿堡", 1), CartItem("老母鸡汤", 2)),
        )!!
        assertEquals("商品小计 ¥51.0", deal.breakdown[0])
        assertEquals("配送费 ¥3.0", deal.breakdown[1])
        assertEquals("满40减12 -¥12.0", deal.breakdown[2])
        assertEquals("实付 ¥42.0", deal.breakdown[3])
    }

    @Test
    fun `bestDeal 按实付升序排列`() {
        val cheap = store(platform = "flash", deliveryFee = 0.0, minOrder = 0.0, discounts = listOf("满20减5"))
        val dear = store(platform = "meituan", deliveryFee = 3.0, minOrder = 0.0)
        val deals = PricingEngine.bestDeal(listOf(dear, cheap), listOf(CartItem("香辣鸡腿堡", 1)))
        assertEquals(2, deals.size)
        assertEquals("flash", deals[0].platform)
        assertEquals("meituan", deals[1].platform)
    }

    @Test
    fun `bestDeal 略过算不出 Deal 的平台`() {
        val ok = store(platform = "flash", minOrder = 0.0)
        val noStock = store(platform = "meituan", items = emptyList(), minOrder = 0.0)
        val deals = PricingEngine.bestDeal(listOf(ok, noStock), listOf(CartItem("香辣鸡腿堡", 1)))
        assertEquals(1, deals.size)
        assertEquals("flash", deals[0].platform)
    }

    @Test
    fun `红包叠加在满减之后`() {
        val s = store(deliveryFee = 3.0, minOrder = 0.0, discounts = listOf("满40减12"))
        // 小计 51.0，满减 12，红包 5
        val deal = PricingEngine.calcDeal(
            s,
            listOf(CartItem("香辣鸡腿堡", 1), CartItem("老母鸡汤", 2)),
            listOf(Coupon("meituan", 20.0, 5.0)),
        )!!
        assertEquals(51.0 + 3.0 - 12.0 - 5.0, deal.finalPrice, 0.001)
        assertTrue(deal.breakdown.any { it == "红包 -¥5.0" })
    }

    @Test
    fun `红包门槛不满足时不叠加`() {
        val s = store(minOrder = 0.0)
        // 小计 21.0 < 门槛 30.0
        val deal = PricingEngine.calcDeal(
            s,
            listOf(CartItem("香辣鸡腿堡", 1)),
            listOf(Coupon("meituan", 30.0, 5.0)),
        )!!
        assertEquals(21.0 + 3.0, deal.finalPrice, 0.001)
        assertTrue(deal.breakdown.none { it.contains("红包") })
    }

    @Test
    fun `多张可用红包取金额最大者`() {
        val s = store(minOrder = 0.0)
        val deal = PricingEngine.calcDeal(
            s,
            listOf(CartItem("香辣鸡腿堡", 1)),
            listOf(
                Coupon("meituan", 10.0, 3.0),
                Coupon("meituan", 10.0, 5.0),
                Coupon("meituan", 20.0, 4.0),
            ),
        )!!
        assertEquals(21.0 + 3.0 - 5.0, deal.finalPrice, 0.001)
        assertTrue(deal.breakdown.any { it == "红包 -¥5.0" })
    }

    @Test
    fun `其他平台红包不生效`() {
        val s = store(platform = "meituan", minOrder = 0.0)
        val deal = PricingEngine.calcDeal(
            s,
            listOf(CartItem("香辣鸡腿堡", 1)),
            listOf(Coupon("flash", 0.0, 5.0)),
        )!!
        assertEquals(21.0 + 3.0, deal.finalPrice, 0.001)
        assertTrue(deal.breakdown.none { it.contains("红包") })
    }

    @Test
    fun `不传红包时行为与旧版一致`() {
        val s = store(deliveryFee = 3.0, minOrder = 0.0, discounts = listOf("满40减12"))
        val deal = PricingEngine.calcDeal(
            s,
            listOf(CartItem("香辣鸡腿堡", 1), CartItem("老母鸡汤", 2)),
        )!!
        assertEquals(42.0, deal.finalPrice, 0.001)
        assertEquals(4, deal.breakdown.size)
        assertTrue(deal.breakdown.none { it.contains("红包") })
    }

    @Test
    fun `bestDeal 支持红包参数`() {
        val s = store(platform = "meituan", minOrder = 0.0)
        val deals = PricingEngine.bestDeal(
            listOf(s),
            listOf(CartItem("香辣鸡腿堡", 1)),
            listOf(Coupon("meituan", 0.0, 5.0)),
        )
        assertEquals(1, deals.size)
        assertEquals(21.0 + 3.0 - 5.0, deals[0].finalPrice, 0.001)
    }
}
