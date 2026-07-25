package com.team.pricecompare.engine.data

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import org.json.JSONArray
import org.json.JSONObject

/** StoreInfo ↔ JSON，供 Room 持久化与跨模块传输。 */
object StoreInfoCodec {

    fun toJson(store: StoreInfo): String = JSONObject().apply {
        put("platform", store.platform)
        put("storeName", store.storeName)
        put("rating", store.rating)
        put("monthlySales", store.monthlySales)
        put("deliveryFee", store.deliveryFee)
        put("minOrder", store.minOrder)
        put("discounts", JSONArray(store.discounts))
        put("capturedAt", store.capturedAt)
        put("items", JSONArray().also { arr ->
            store.items.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("name", item.name)
                        put("price", item.price)
                        put("packageFee", item.packageFee)
                    },
                )
            }
        })
    }.toString()

    fun fromJson(raw: String): StoreInfo {
        val o = JSONObject(raw)
        val items = mutableListOf<ItemPrice>()
        o.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                items.add(
                    ItemPrice(
                        name = item.getString("name"),
                        price = item.getDouble("price"),
                        packageFee = item.optDouble("packageFee", 0.0),
                    ),
                )
            }
        }
        val discounts = mutableListOf<String>()
        o.optJSONArray("discounts")?.let { arr ->
            for (i in 0 until arr.length()) discounts.add(arr.getString(i))
        }
        return StoreInfo(
            platform = o.getString("platform"),
            storeName = o.getString("storeName"),
            rating = o.optDouble("rating", 0.0),
            monthlySales = o.optInt("monthlySales", 0),
            deliveryFee = o.optDouble("deliveryFee", 0.0),
            minOrder = o.optDouble("minOrder", 0.0),
            discounts = discounts,
            items = items,
            capturedAt = o.optLong("capturedAt", System.currentTimeMillis()),
        )
    }
}
