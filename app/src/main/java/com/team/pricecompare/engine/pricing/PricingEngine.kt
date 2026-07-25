package com.team.pricecompare.engine.pricing

import com.team.pricecompare.Deal
import com.team.pricecompare.StoreInfo
import com.team.pricecompare.engine.match.StoreMatcher

// ================= PRICING 常量区（计价规则调整只改这里） =================
object PricingRules {
    /** 满减文案匹配：「满40减12」「满 40 减 12.5」等形态，捕获门槛与减免额。 */
    val FULL_REDUCTION_REGEX = Regex("""满\s*(\d+(?:\.\d+)?)\s*减\s*(\d+(?:\.\d+)?)""")
}
// ============================================================================

/** 购物车条目：商品名 + 数量。 */
data class CartItem(val name: String, val quantity: Int)

/**
 * 实付价计算引擎 —— M1 版。
 * 实付价 = Σ(商品价 + 包装费) × 数量 + 配送费 - 满减优惠。
 * 满减是否可叠加 M1 不处理，只取满足门槛的最优一档；任何异常一律返回 null。
 */
object PricingEngine {

    /**
     * 计算某平台某门店对指定购物车的实付方案。
     * 返回 null 的情形：
     * - 购物车为空，或商品在该店菜单中找不到（归一化商品名匹配不上）；
     * - 商品小计达不到起送价 [StoreInfo.minOrder]；
     * - 任何解析/计算异常（优雅降级铁律）。
     */
    fun calcDeal(store: StoreInfo, cart: List<CartItem>): Deal? {
        return runCatching {
            if (cart.isEmpty()) return null
            val itemsByName = store.items.associateBy { StoreMatcher.normalizeStoreName(it.name) }

            var subtotal = 0.0
            for (entry in cart) {
                if (entry.quantity <= 0) return null
                val item = itemsByName[StoreMatcher.normalizeStoreName(entry.name)] ?: return null
                subtotal += (item.price + item.packageFee) * entry.quantity
            }

            // 未达起送价：该平台下不了单，无 Deal 可言
            if (subtotal < store.minOrder) return null

            val reduction = bestFullReduction(store.discounts, subtotal)
            val finalPrice = (subtotal + store.deliveryFee - (reduction?.second ?: 0.0))
                .coerceAtLeast(0.0)

            val breakdown = mutableListOf<String>()
            breakdown.add("商品小计 ¥$subtotal")
            breakdown.add("配送费 ¥${store.deliveryFee}")
            if (reduction != null && reduction.second > 0.0) {
                breakdown.add("满${fmt(reduction.first)}减${fmt(reduction.second)} -¥${reduction.second}")
            }
            breakdown.add("实付 ¥$finalPrice")

            Deal(platform = store.platform, finalPrice = finalPrice, breakdown = breakdown)
        }.getOrNull()
    }

    /**
     * 对同店各平台分别计算实付方案，按 finalPrice 升序返回（最优在前）。
     * 算不出 Deal 的平台直接略过。
     */
    fun bestDeal(stores: List<StoreInfo>, cart: List<CartItem>): List<Deal> =
        stores.mapNotNull { calcDeal(it, cart) }.sortedBy { it.finalPrice }

    /**
     * 从满减文案列表中挑出「门槛 ≤ 小计」里减免额最高的一档。
     * 返回 (门槛, 减免额)；没有任何满足门槛的档位时返回 null。
     */
    internal fun bestFullReduction(discounts: List<String>, subtotal: Double): Pair<Double, Double>? =
        discounts.mapNotNull { text ->
            val m = PricingRules.FULL_REDUCTION_REGEX.find(text) ?: return@mapNotNull null
            val threshold = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val off = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            if (subtotal >= threshold) threshold to off else null
        }.maxByOrNull { it.second }

    /** 明细中的门槛/减免额展示：整数去掉小数部分（40.0 → "40"）。 */
    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
}
