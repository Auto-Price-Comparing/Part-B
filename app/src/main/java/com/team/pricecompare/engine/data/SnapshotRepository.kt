package com.team.pricecompare.engine.data

import android.content.Context
import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 店铺快照仓库：解析器产出 StoreInfo 后落库，引擎/悬浮窗按时间线读取。
 * 序列化用 org.json 手写（Android 自带，不引新依赖），互转函数见文件底部。
 */
class SnapshotRepository(context: Context) {

    private val dao = AppDatabase.get(context.applicationContext).storeDao()

    /** 保存一次采集快照。序列化失败则直接放弃该条（优雅降级，不抛异常）。 */
    suspend fun save(info: StoreInfo) {
        val payload = info.toPayloadJson().toString()
        dao.insert(
            StoreSnapshot(
                platform = info.platform,
                storeName = info.storeName,
                payloadJson = payload,
                capturedAt = info.capturedAt,
            ),
        )
    }

    /** 最近 50 条快照（按采集时间倒序，见 [StoreSnapshotDao.latest]）。 */
    suspend fun latest(): List<StoreSnapshot> = dao.latest()
}

// ================= StoreInfo ↔ payloadJson 互转（internal，同模块可用） =================

internal fun StoreInfo.toPayloadJson(): JSONObject = JSONObject().apply {
    put("platform", platform)
    put("storeName", storeName)
    put("rating", rating)
    put("monthlySales", monthlySales)
    put("deliveryFee", deliveryFee)
    put("minOrder", minOrder)
    put("discounts", JSONArray().also { arr -> discounts.forEach { arr.put(it) } })
    put(
        "items",
        JSONArray().also { arr ->
            items.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("name", item.name)
                        put("price", item.price)
                        put("packageFee", item.packageFee)
                    },
                )
            }
        },
    )
    put("capturedAt", capturedAt)
}

/** 反序列化失败（数据损坏/旧版本字段缺失）返回 null，由调用方跳过该条快照。 */
internal fun StoreSnapshot.toStoreInfo(): StoreInfo? = runCatching {
    val o = JSONObject(payloadJson)
    val discountsArr = o.optJSONArray("discounts")
    val itemsArr = o.optJSONArray("items")
    StoreInfo(
        platform = o.optString("platform"),
        storeName = o.optString("storeName"),
        rating = o.optDouble("rating"),
        monthlySales = o.optInt("monthlySales"),
        deliveryFee = o.optDouble("deliveryFee"),
        minOrder = o.optDouble("minOrder"),
        discounts = buildList {
            if (discountsArr != null) for (i in 0 until discountsArr.length()) add(discountsArr.getString(i))
        },
        items = buildList {
            if (itemsArr != null) for (i in 0 until itemsArr.length()) {
                val it = itemsArr.getJSONObject(i)
                add(ItemPrice(it.optString("name"), it.optDouble("price"), it.optDouble("packageFee")))
            }
        },
        capturedAt = o.optLong("capturedAt"),
    )
}.getOrNull()
