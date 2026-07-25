package com.team.pricecompare.parsers

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import com.team.pricecompare.accessibility.PRICE_REGEX
import com.team.pricecompare.accessibility.SimpleNode
import com.team.pricecompare.accessibility.allTexts
import com.team.pricecompare.accessibility.findPriceNodesWithChain

// ================= MEITUAN_SELECTORS 常量区（App 改版只改这里） =================
object MeituanSelectors {
    /** 弹窗关闭按钮的候选文案（精确匹配，服务侧自动点击）。 */
    val POPUP_CLOSE_TEXTS = listOf("×", "关闭", "跳过", "我知道了", "暂不", "以后再说")

    /** 店名猜测的关键词线索。 */
    val STORE_NAME_HINTS = listOf("店", "餐厅", "奶茶", "咖啡", "饭", "面", "粥", "汉堡", "烤")

    /** 商品名候选文本中需要排除的噪音词（起送/配送/满减等信息）。 */
    val NOISE_TEXTS = listOf("起送", "配送", "月售", "评分", "减", "券", "折")

    /** 价格节点本身含这些词时不视为商品价格（配送费/起送价也含 ¥ 数字）。 */
    val ITEM_PRICE_EXCLUDE_TEXTS = listOf("配送", "起送")

    /** 从价格节点向上回溯找商品名的最大祖先层数。 */
    const val MAX_ANCESTOR_LEVELS = 4

    // 以下正则基于美团公开界面文案的先验猜测（如「评分4.8分」「月售9999+」
    // 「配送费¥3.5」「¥20起送」「满30减12」），未经真机 dump 验证，
    // 待 fixtures 到位后回归校准——只改这里，不动解析逻辑。
    /** 评分：「评分4.8分」/「4.8分」。 */
    val RATING_REGEX = Regex("""(\d(?:\.\d)?)\s*分""")

    /** 月售：「月售9999+」（数字后的 + 被 \d+ 自然截断）。 */
    val MONTHLY_SALES_REGEX = Regex("""月售\s*(\d+)""")

    /** 配送费：「配送费¥3.5」。 */
    val DELIVERY_FEE_REGEX = Regex("""配送费\s*[¥￥]\s*(\d+(?:\.\d{1,2})?)""")

    /** 起送价：「¥20起送」。 */
    val MIN_ORDER_REGEX = Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)\s*起送""")

    /** 满减文案：「满30减12」。 */
    val DISCOUNT_REGEX = Regex("""满\s*\d+\s*减\s*\d+""")
}
// ============================================================================

/**
 * 美团（com.meituan.takeout）解析器 —— M1 版。
 * 面向 SimpleNode 编程，真机节点树与 fixtures JSON 走同一套逻辑。
 * 失败一律返回 null，绝不允许抛出未捕获异常（AGENTS.md 铁律）。
 */
object MeituanParser {

    /**
     * 解析店铺菜单页：文本匹配提取「商品名+价格」及店铺元信息并组装 StoreInfo。
     * 弹窗关闭与滑屏采集由无障碍服务/手势框架负责，
     * 本函数只负责页面识别、文本提取、组装。
     * 拿不到的可选字段回落 0.0/0/空列表；商品或店名缺失则整体返回 null。
     */
    fun parseStorePage(root: SimpleNode, now: Long = System.currentTimeMillis()): StoreInfo? {
        return runCatching {
            val items = root.findPriceNodesWithChain()
                .filter { (priceNode, _) -> isItemPriceNode(priceNode) }
                .mapNotNull { (priceNode, chain) ->
                    val price = PRICE_REGEX.find(priceNode.text)?.value
                        ?.replace(Regex("[¥￥\\s]"), "")
                        ?.toDoubleOrNull()
                        ?: return@mapNotNull null
                    val name = guessItemName(chain) ?: return@mapNotNull null
                    ItemPrice(name = name, price = price, packageFee = 0.0)
                }
                .distinctBy { it.name }
            if (items.isEmpty()) return null
            val storeName = guessStoreName(root) ?: return null
            val texts = root.allTexts()
            StoreInfo(
                platform = "meituan",
                storeName = storeName,
                rating = extractFirst(texts, MeituanSelectors.RATING_REGEX)?.toDoubleOrNull() ?: 0.0,
                monthlySales = extractFirst(texts, MeituanSelectors.MONTHLY_SALES_REGEX)?.toIntOrNull() ?: 0,
                deliveryFee = extractFirst(texts, MeituanSelectors.DELIVERY_FEE_REGEX)?.toDoubleOrNull() ?: 0.0,
                minOrder = extractFirst(texts, MeituanSelectors.MIN_ORDER_REGEX)?.toDoubleOrNull() ?: 0.0,
                discounts = extractDiscounts(texts),
                items = items,
                capturedAt = now,
            )
        }.getOrNull()
    }

    /** 配送费/起送价等价格节点不算商品价格。 */
    private fun isItemPriceNode(node: SimpleNode): Boolean =
        MeituanSelectors.ITEM_PRICE_EXCLUDE_TEXTS.none { node.text.contains(it) }

    /** 在全部文本中找第一个命中正则的捕获组。 */
    private fun extractFirst(texts: List<String>, regex: Regex): String? =
        texts.firstNotNullOfOrNull { regex.find(it)?.groupValues?.get(1) }

    /** 收集全部满减文案，去空白后去重，保持出现顺序。 */
    private fun extractDiscounts(texts: List<String>): List<String> =
        texts.flatMap { text ->
            MeituanSelectors.DISCOUNT_REGEX.findAll(text).map { it.value.replace(Regex("\\s"), "") }
        }.distinct()

    /**
     * 从价格节点的祖先链（根→…→父）回溯猜商品名：
     * 逐层在祖先子树中收集非价格、非噪音的文本，取最长者。
     * 启发式，误差预期内；用 fixtures 回归逐步收紧。
     */
    internal fun guessItemName(chain: List<SimpleNode>): String? {
        val ancestors = chain.takeLast(MeituanSelectors.MAX_ANCESTOR_LEVELS).asReversed()
        for (ancestor in ancestors) {
            val best = ancestor.allTexts()
                .map { it.trim() }
                .filter { text ->
                    text.length >= 2 &&
                        !PRICE_REGEX.containsMatchIn(text) &&
                        MeituanSelectors.NOISE_TEXTS.none { text.contains(it) }
                }
                .maxByOrNull { it.length }
            if (best != null) return best
        }
        return null
    }

    /** 店名启发式：含关键词的文本优先，否则取页面上第一个像样的标题文本。 */
    internal fun guessStoreName(root: SimpleNode): String? {
        val texts = root.allTexts().map { it.trim() }.filter { it.length in 4..30 }
        return texts.firstOrNull { t -> MeituanSelectors.STORE_NAME_HINTS.any { t.contains(it) } }
            ?: texts.firstOrNull()
    }
}
