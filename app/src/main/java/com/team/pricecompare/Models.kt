package com.team.pricecompare

/**
 * 三方共享数据契约（见 AGENTS.md）。
 * 修改任何字段必须三人协商一致，并同步更新 fixtures。
 */
data class ItemPrice(
    val name: String,
    val price: Double,
    val packageFee: Double,
)

data class StoreInfo(
    val platform: String, // "meituan" | "flash"
    val storeName: String,
    val rating: Double,
    val monthlySales: Int,
    val deliveryFee: Double,
    val minOrder: Double,
    val discounts: List<String>,
    val items: List<ItemPrice>,
    val capturedAt: Long,
)

data class Deal(
    val platform: String,
    val finalPrice: Double,
    val breakdown: List<String>,
)
