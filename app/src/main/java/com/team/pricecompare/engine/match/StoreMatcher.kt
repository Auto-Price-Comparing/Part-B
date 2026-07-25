package com.team.pricecompare.engine.match

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo

// ================= MATCHER 常量区（匹配规则调整只改这里） =================
object MatcherRules {
    /** 各类括号及其内容整体剔除：半角 () 与全角（）【】［］（全角已先转半角，这里仍兜底列出）。 */
    val BRACKET_REGEX = Regex("""[\(（\[【][^\)）\]】]*[\)）\]】]""")

    /** 所有空白字符（含全角空格转半角后的普通空格）。 */
    val WHITESPACE_REGEX = Regex("""\s+""")

    /** 商品名模糊配对阈值：字符二元组 Jaccard 相似度 ≥ 该值才允许配对。 */
    const val ITEM_SIMILARITY_THRESHOLD = 0.5
}
// ============================================================================

/**
 * 同店同品匹配器 —— M2 版。
 * M2 在归一化完全相等配对之上引入相似度兜底：未配对商品按字符二元组
 * Jaccard 相似度二次配对（阈值见 [MatcherRules.ITEM_SIMILARITY_THRESHOLD]）；
 * 只做文本相似度，不做拼音；公开接口保持不变。
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
     * 同店两平台间商品配对：先按归一化商品名完全相等配对（一侧重名只取第一个）；
     * 未配上的 a 侧商品再在 b 侧剩余商品中找相似度最高且 ≥ 阈值者配对，
     * 每个 b 商品最多配对一次。返回顺序以 a 的商品顺序为准。
     */
    fun matchItems(a: StoreInfo, b: StoreInfo): List<Pair<ItemPrice, ItemPrice>> {
        // 第一遍：归一化后完全相等配对（M1 原有行为）
        val bIndexByName = mutableMapOf<String, Int>()
        b.items.forEachIndexed { index, item -> bIndexByName.putIfAbsent(normalizeName(item.name), index) }
        val usedB = BooleanArray(b.items.size)
        val pairs = MutableList<Pair<ItemPrice, ItemPrice>?>(a.items.size) { null }
        a.items.forEachIndexed { i, itemA ->
            val j = bIndexByName[normalizeName(itemA.name)]
            if (j != null) {
                usedB[j] = true
                pairs[i] = itemA to b.items[j]
            }
        }
        // 第二遍：相似度兜底，每个 b 商品最多用一次
        val unmatchedB = b.items.indices.filter { !usedB[it] }.toMutableList()
        a.items.forEachIndexed { i, itemA ->
            if (pairs[i] != null) return@forEachIndexed
            val best = unmatchedB
                .map { it to itemSimilarity(itemA.name, b.items[it].name) }
                .filter { it.second >= MatcherRules.ITEM_SIMILARITY_THRESHOLD }
                .maxByOrNull { it.second }
            if (best != null) {
                unmatchedB.remove(best.first)
                pairs[i] = itemA to b.items[best.first]
            }
        }
        return pairs.filterNotNull()
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
