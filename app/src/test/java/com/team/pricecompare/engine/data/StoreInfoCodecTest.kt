package com.team.pricecompare.engine.data

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreInfoCodecTest {

    @Test
    fun `roundtrip 保留全部字段`() {
        val original = StoreInfo(
            platform = "meituan",
            storeName = "老乡鸡(示例店)",
            rating = 4.7,
            monthlySales = 999,
            deliveryFee = 3.5,
            minOrder = 20.0,
            discounts = listOf("满30减12", "新客券"),
            items = listOf(
                ItemPrice("香辣鸡腿堡", 19.9, 1.0),
                ItemPrice("老母鸡汤", 15.0, 0.0),
            ),
            capturedAt = 1234567890L,
        )
        val decoded = StoreInfoCodec.fromJson(StoreInfoCodec.toJson(original))
        assertEquals(original, decoded)
    }
}
