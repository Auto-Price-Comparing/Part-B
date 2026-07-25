package com.team.pricecompare.engine.match

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo

// ================= MATCHER 常量区（匹配规则调整只改这里） =================
object MatcherRules {
    /** 各类括号及其内容整体剔除：半角 () 与全角（）【】［］（全角已先转半角，这里仍兜底列出）。 */
    val BRACKET_REGEX = Regex("""[\(（\[【][^\)）\]】]*[\)）\]】]""")

    /** 所有空白字符（含全角空格转半角后的普通空格）。 */
    val WHITESPACE_REGEX = Regex("""\s+""")
}
// ============================================================================

/**
 * 同店同品匹配器 —— M1 版。
 * M1 只做归一化后的完全相等匹配，不做模糊/相似度匹配；
 * 相似度（如编辑距离、拼音）留待 M2 引入，接口保持不变。
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
     * 同店两平台间按归一化商品名相等配对。
     * 一侧重名商品只取第一个；返回顺序以 a 的商品顺序为准。
     */
    fun matchItems(a: StoreInfo, b: StoreInfo): List<Pair<ItemPrice, ItemPrice>> {
        val bByName = b.items.associateBy { normalizeName(it.name) }
        return a.items.mapNotNull { itemA ->
            bByName[normalizeName(itemA.name)]?.let { itemA to it }
        }
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
