package com.team.pricecompare.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 节点树的可序列化镜像。
 * 采集层与解析层之间的统一数据形态：真机上由 [AccessibilityNodeInfo.toSimpleNode] 生成，
 * 测试时由 fixtures JSON 经 [SimpleNode.fromJson] 还原，解析器只面向 SimpleNode 编程。
 */
data class SimpleNode(
    val text: String,
    val className: String,
    val viewId: String,
    val bounds: String, // "left,top,right,bottom"
    val children: List<SimpleNode> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("class", className)
        put("id", viewId)
        put("bounds", bounds)
        put("children", JSONArray().also { arr -> children.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): SimpleNode {
            val kids = mutableListOf<SimpleNode>()
            o.optJSONArray("children")?.let { arr ->
                for (i in 0 until arr.length()) kids.add(fromJson(arr.getJSONObject(i)))
            }
            return SimpleNode(
                text = o.optString("text"),
                className = o.optString("class"),
                viewId = o.optString("id"),
                bounds = o.optString("bounds"),
                children = kids,
            )
        }
    }
}

fun AccessibilityNodeInfo.toSimpleNode(): SimpleNode {
    val r = Rect()
    getBoundsInScreen(r)
    val kids = ArrayList<SimpleNode>(childCount.coerceAtLeast(0))
    for (i in 0 until childCount) {
        getChild(i)?.let { kids.add(it.toSimpleNode()) }
    }
    return SimpleNode(
        text = text?.toString().orEmpty(),
        className = className?.toString().orEmpty(),
        viewId = viewIdResourceName.orEmpty(),
        bounds = "${r.left},${r.top},${r.right},${r.bottom}",
        children = kids,
    )
}
