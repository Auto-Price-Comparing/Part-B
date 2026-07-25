package com.team.pricecompare.engine.match

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo

// ================= MATCHER 常量区（匹配规则调整只改这里） =================
object MatcherRules {
    /** 各类括号及其内容整体剔除：半角 () 与全角（）【】［］（全角已先转半角，这里仍兜底列出）。 */
    val BRACKET_REGEX = Regex("""[\(（\[【][^\)）\]】]*[\)）\]】]""")

    /** 所有空白字符（含全角空格转半角后的普通空格）。 */
    val WHITESPACE_REGEX = Regex("""\s+""")

    /** 相似度 ≥ 该值自动配对（三级判定设计移植自 C 侧，算法仍用本仓库的 Jaccard bigram）。 */
    const val AUTO_MATCH_THRESHOLD = 0.85

    /** 相似度在 [本值, [AUTO_MATCH_THRESHOLD]) 区间进入待确认，由用户人工配对（见 MatchMemory）。 */
    const val CONFIRM_MATCH_THRESHOLD = 0.6
}
// ============================================================================

/** 单条商品配对结果：[b] 为 null 表示 a 侧商品在另一侧无对应（未配上）。 */
data class ItemMatch(
    val a: ItemPrice,
    val b: ItemPrice?,
    val similarity: Double,
    val needsConfirm: Boolean,
)

/**
 * 同品配对总结果：
 * - [auto]：可直接参与计价的配对（完全相等 / 高相似度 / 用户已确认），顺序以 a 的商品顺序为准；
 * - [pending]：相似度落在待确认区间的疑似配对，等用户确认后才进 [auto]；
 * - [unmatchedA]：另一侧完全无对应的 a 侧商品。
 */
data class MatchResult(
    val auto: List<Pair<ItemPrice, ItemPrice>>,
    val pending: List<ItemMatch>,
    val unmatchedA: List<ItemPrice>,
)

/**
 * 同店同品匹配器 —— 确认配对版（整合 C 侧设计）。
 * 三级判定：归一化完全相等 / Jaccard ≥ [MatcherRules.AUTO_MATCH_THRESHOLD] → 自动配对；
 * ≥ [MatcherRules.CONFIRM_MATCH_THRESHOLD] → 待用户确认；低于 → 不配对。
 * 用户确认过的配对（[confirmed]，归一化名对）直接按自动配对处理。
 * 只做文本相似度，不做拼音。
 */
object StoreMatcher {

    /**
     * 店名归一化：全角转半角 → 去括号内容（含「(xx店)」类后缀）→ 去所有空白。
     * 两平台同一门店的常见差异（「老乡鸡（华科店）」vs「老乡鸡 华科店」暂不在 M1 处理）
     * 主要靠括号剔除覆盖。
     */
    fun normalizeStoreName(name: String): String = normalizeName(name)

    /**
     * 按归一化店名完全相等分组：同一组内为不同平台抓到的同一门店快照。
     * 组间顺序按首次出现位置，组内保持输入顺序。
     */
    fun matchStores(stores: List<StoreInfo>): List<List<StoreInfo>> =
        stores.groupBy { normalizeStoreName(it.storeName) }
            .values
            .filter { it.isNotEmpty() }

    /**
     * 同店两平台间商品配对，每个 b 商品最多被（自动或待确认）占用一次：
     * 1. 归一化完全相等配对（一侧重名只取第一个）；
     * 2. [confirmed] 中的用户确认对直接配对（覆盖相似度判定）；
     * 3. 剩余商品按 Jaccard 相似度贪心配对，按三级阈值分流到 auto / pending / 不匹配。
     */
    fun matchItems(
        a: StoreInfo,
        b: StoreInfo,
        confirmed: Set<Pair<String, String>> = emptySet(),
    ): MatchResult {
        val auto = MutableList<Pair<ItemPrice, ItemPrice>?>(a.items.size) { null }
        val pendingByIndex = MutableList<ItemMatch?>(a.items.size) { null }
        val usedB = BooleanArray(b.items.size)

        // 第一遍：归一化后完全相等配对
        val bIndexByName = mutableMapOf<String, Int>()
        b.items.forEachIndexed { index, item -> bIndexByName.putIfAbsent(normalizeName(item.name), index) }
        a.items.forEachIndexed { i, itemA ->
            val j = bIndexByName[normalizeName(itemA.name)]
            if (j != null) {
                usedB[j] = true
                auto[i] = itemA to b.items[j]
            }
        }

        // 第二遍：用户确认对直通（归一化名对命中即配）
        a.items.forEachIndexed { i, itemA ->
            if (auto[i] != null) return@forEachIndexed
            val normA = normalizeName(itemA.name)
            val j = b.items.indices.firstOrNull { k ->
                !usedB[k] && (normA to normalizeName(b.items[k].name)) in confirmed
            }
            if (j != null) {
                usedB[j] = true
                auto[i] = itemA to b.items[j]
            }
        }

        // 第三遍：相似度贪心，按三级阈值分流
        val unmatchedB = b.items.indices.filter { !usedB[it] }.toMutableList()
        a.items.forEachIndexed { i, itemA ->
            if (auto[i] != null) return@forEachIndexed
            val best = unmatchedB
                .map { it to itemSimilarity(itemA.name, b.items[it].name) }
                .filter { it.second >= MatcherRules.CONFIRM_MATCH_THRESHOLD }
                .maxByOrNull { it.second } ?: return@forEachIndexed
            unmatchedB.remove(best.first)
            usedB[best.first] = true
            if (best.second >= MatcherRules.AUTO_MATCH_THRESHOLD) {
                auto[i] = itemA to b.items[best.first]
            } else {
                pendingByIndex[i] = ItemMatch(itemA, b.items[best.first], best.second, needsConfirm = true)
            }
        }

        val unmatchedA = a.items.filterIndexed { i, _ -> auto[i] == null && pendingByIndex[i] == null }
        return MatchResult(auto.filterNotNull(), pendingByIndex.filterNotNull(), unmatchedA)
    }

    /**
     * 商品名相似度：归一化后按字符二元组（bigram）Jaccard 计算，取值 [0, 1]。
     * 纯函数，便于单测；长度不足 2 的串退化为整串比较。
     */
    fun itemSimilarity(x: String, y: String): Double {
        val bigramsX = bigrams(normalizeName(x))
        val bigramsY = bigrams(normalizeName(y))
        if (bigramsX.isEmpty() || bigramsY.isEmpty()) return 0.0
        val intersection = bigramsX.intersect(bigramsY).size.toDouble()
        val union = bigramsX.union(bigramsY).size.toDouble()
        return intersection / union
    }

    /** 字符二元组集合；长度不足 2 时退化为整串单元素集合（空串返回空集）。 */
    private fun bigrams(s: String): Set<String> = when {
        s.isEmpty() -> emptySet()
        s.length < 2 -> setOf(s)
        else -> s.windowed(2, 1).toSet()
    }

    /** 商品名与店名共用同一套归一化规则（M1 不区分）。 */
    internal fun normalizeName(name: String): String {
        var s = name.trim()
        s = toHalfWidth(s)
        s = s.replace(MatcherRules.BRACKET_REGEX, "")
        s = s.replace(MatcherRules.WHITESPACE_REGEX, "")
        return s
    }

    /** 全角转半角：全角空格 → 空格，FF01..FF5E 区段平移到半角。 */
    private fun toHalfWidth(s: String): String = buildString(s.length) {
        for (ch in s) {
            when {
                ch == '　' -> append(' ')
                ch.code in 0xFF01..0xFF5E -> append((ch.code - 0xFEE0).toChar())
                else -> append(ch)
            }
        }
    }
}
