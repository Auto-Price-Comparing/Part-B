package com.team.pricecompare.engine.data

import android.content.Context
import com.team.pricecompare.engine.match.StoreMatcher

/**
 * 用户确认配对的持久化记忆（整合自 C 侧设计）。
 * 悬浮窗上确认一对「疑似同品」后落库，此后该对商品匹配时直接按自动配对处理。
 * 存储的是归一化后的商品名对；确认前查重，同一对只存一次。
 */
class MatchMemory(context: Context) {

    private val dao = AppDatabase.get(context.applicationContext).productMatchDao()

    /** 确认一对同品：名归一化后落库，已存在则跳过（幂等）。 */
    suspend fun confirm(nameA: String, nameB: String) {
        val a = StoreMatcher.normalizeStoreName(nameA)
        val b = StoreMatcher.normalizeStoreName(nameB)
        if (a.isEmpty() || b.isEmpty()) return
        if (dao.count(a, b) > 0) return
        dao.insert(ProductMatchEntity(nameA = a, nameB = b, createdAt = System.currentTimeMillis()))
    }

    /** 全部已确认名对（归一化后），供 [StoreMatcher.matchItems] 直通配对。 */
    suspend fun confirmedPairs(): Set<Pair<String, String>> =
        dao.all().map { it.nameA to it.nameB }.toSet()
}
