package com.team.pricecompare.parsers

import android.util.Log

/**
 * 解析器统一容错包装（移植自 C 侧，落实「解析器绝不抛未捕获异常」铁律）。
 * 任何解析异常降级为 null，由上层提示「该页面暂不支持」。
 */
inline fun <T> safeParse(tag: String, block: () -> T?): T? = try {
    block()
} catch (e: Exception) {
    Log.w(tag, "parse failed", e)
    null
}
