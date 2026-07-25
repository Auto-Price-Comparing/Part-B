package com.team.pricecompare.parsers

import com.team.pricecompare.ItemPrice
import com.team.pricecompare.StoreInfo
import com.team.pricecompare.accessibility.PRICE_REGEX
import com.team.pricecompare.accessibility.SimpleNode
import com.team.pricecompare.accessibility.allTexts
import com.team.pricecompare.accessibility.findPriceNodesWithChain

// ================= FLASH_SELECTORS 常量区（App 改版只改这里） =================
object FlashSelectors {
    /** 弹窗关闭按钮的候选文案（精确匹配，服务侧自动点击）。 */
    val POPUP_CLOSE_TEXTS = listOf("×", "关闭", "跳过", "我知道了", "暂不", "以后再说")

    /** 店名猜测的关键词线索。 */
    val STORE_NAME_HINTS = listOf("店", "餐厅", "奶茶", "咖啡", "饭", "面", "粥", "汉堡", "烤")

    /** 商品名候选文本中需要排除的噪音词（起送/配送/满减等信息）。 */
    val NOISE_TEXTS = listOf("起送", "配送", "月售", "评分", "减", "券", "折")

    /** 从价格节点向上回溯找商品名的最大祖先层数。 */
    const val MAX_ANCESTOR_LEVELS = 4

    /** 评分文本模式：「4.8分」「评分4.8」。 */
    val RATING_REGEXES = listOf(
        Regex("""(\d(?:\.\d)?)\s*分"""),
        Regex("""评分\s*(\d(?:\.\d)?)"""),
    )

    /** 月售文本模式：「月售1000+」「月售300」。 */
    val MONTHLY_SALES_REGEX = Regex("""月售\s*(\d+)\s*\+?""")

    /** 配送费文本模式：「配送费¥3」「配送费3元」。 */
    val DELIVERY_FEE_REGEX = Regex("""配送费\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?""")

    /** 免配送费文案（命中即配送费为 0.0）。 */
    val FREE_DELIVERY_TEXTS = listOf("免配送费", "配送费全免")

    /** 起送价文本模式：「起送¥20」「20元起送」。 */
    val MIN_ORDER_REGEXES = listOf(
        Regex("""起送\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)"""),
        Regex("""(\d+(?:\.\d{1,2})?)\s*元\s*起送"""),
    )

    /** 满减优惠文案模式：「满30减15」。 */
    val FULL_MINUS_REGEX = Regex("""满\s*\d+\s*减\s*\d+""")

    /** 其他优惠文案关键词：含「券」「折」的文本行视为优惠信息。 */
    val DISCOUNT_KEYWORDS = listOf("券", "折")

    /** 价格文本清洗：去掉货币符号与空白后转数字。 */
    val PRICE_CLEAN_REGEX = Regex("""[¥￥\s]""")

    /**
     * 该文本是否属于店铺元信息价格（配送费/起送价），而非商品价格。
     * 这类节点也含 ¥，提取商品时必须排除，否则会猜出张冠李戴的"商品"。
     */
    fun isStoreMetaPriceText(text: String): Boolean =
        DELIVERY_FEE_REGEX.containsMatchIn(text) ||
            MIN_ORDER_REGEXES.any { it.containsMatchIn(text) }
}
// ============================================================================

/**
 * 淘宝闪购（me.ele）解析器 —— M1 版。
 * 面向 SimpleNode 编程，真机节点树与 fixtures JSON 走同一套逻辑。
 * 失败一律返回 null，绝不允许抛出未捕获异常（AGENTS.md 铁律）。
 */
object FlashParser {

    /**
     * 解析店铺菜单页：文本匹配提取店铺元信息与「商品名+价格」并组装 StoreInfo。
     * 弹窗关闭（步骤 1）与滑屏采集（步骤 4）由无障碍服务/手势框架负责，
     * 本函数只负责页面识别（2）、文本提取（3）、组装（5）。
     *
     * 宽容策略：rating/monthlySales/deliveryFee/minOrder/discounts 拿不到时
     * 回落 0.0/0/空列表，绝不因此失败；只有 items 为空或店名猜不出才返回 null。
     */
    fun parseStorePage(root: SimpleNode, now: Long = System.currentTimeMillis()): StoreInfo? {
        return runCatching {
            // 优惠信息在噪音过滤之前单独走一遍（"券/折/减"同时在 NOISE_TEXTS 里）。
            val texts = root.allTexts().map { it.trim() }.filter { it.isNotEmpty() }
            val items = root.findPriceNodesWithChain()
                .filterNot { (priceNode, _) -> FlashSelectors.isStoreMetaPriceText(priceNode.text) }
                .mapNotNull { (priceNode, chain) ->
                    val price = PRICE_REGEX.find(priceNode.text)?.value
                        ?.replace(FlashSelectors.PRICE_CLEAN_REGEX, "")
                        ?.toDoubleOrNull()
                        ?: return@mapNotNull null
                    val name = guessItemName(chain) ?: return@mapNotNull null
                    ItemPrice(name = name, price = price, packageFee = 0.0)
                }
                .distinctBy { it.name }
            if (items.isEmpty()) return null
            val storeName = guessStoreName(root) ?: return null
            StoreInfo(
                platform = "flash",
                storeName = storeName,
                rating = extractRating(texts),
                monthlySales = extractMonthlySales(texts),
                deliveryFee = extractDeliveryFee(texts),
                minOrder = extractMinOrder(texts),
                discounts = extractDiscounts(texts),
                items = items,
                capturedAt = now,
            )
        }.getOrNull()
    }

    /**
     * 解析多屏合并结果（对应手势框架 scrollAndCollect / mergeScreens 的输出）：
     * 把每屏的根节点挂到同一个合成根下再解析，商品仍按名去重（先出现的屏优先），
     * 店铺元信息跨屏取第一个命中的值，优惠信息跨屏合并去重。
     */
    fun parseStorePages(screens: List<SimpleNode>, now: Long = System.currentTimeMillis()): StoreInfo? {
        if (screens.isEmpty()) return null
        val merged = SimpleNode(
            text = "", className = "", viewId = "", bounds = "",
            children = screens,
        )
        return parseStorePage(merged, now)
    }

    /** 评分：第一个命中评分模式的文本，拿不到回落 0.0。 */
    internal fun extractRating(texts: List<String>): Double =
        texts.firstNotNullOfOrNull { t ->
            FlashSelectors.RATING_REGEXES.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1) }
        }?.toDoubleOrNull() ?: 0.0

    /** 月售：「月售1000+」取 1000（忽略 +），拿不到回落 0。 */
    internal fun extractMonthlySales(texts: List<String>): Int =
        texts.firstNotNullOfOrNull { FlashSelectors.MONTHLY_SALES_REGEX.find(it)?.groupValues?.get(1) }
            ?.toIntOrNull() ?: 0

    /** 配送费：免配送费文案优先返回 0.0，否则取「配送费¥3」式金额，拿不到回落 0.0。 */
    internal fun extractDeliveryFee(texts: List<String>): Double {
        if (texts.any { t -> FlashSelectors.FREE_DELIVERY_TEXTS.any { t.contains(it) } }) return 0.0
        return texts.firstNotNullOfOrNull { FlashSelectors.DELIVERY_FEE_REGEX.find(it)?.groupValues?.get(1) }
            ?.toDoubleOrNull() ?: 0.0
    }

    /** 起送价：命中「起送¥20」「20元起送」任一模式，拿不到回落 0.0。 */
    internal fun extractMinOrder(texts: List<String>): Double =
        texts.firstNotNullOfOrNull { t ->
            FlashSelectors.MIN_ORDER_REGEXES.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1) }
        }?.toDoubleOrNull() ?: 0.0

    /** 优惠信息：满减文案 + 含「券」「折」的文本行，去重后返回。 */
    internal fun extractDiscounts(texts: List<String>): List<String> =
        texts.filter { t ->
            FlashSelectors.FULL_MINUS_REGEX.containsMatchIn(t) ||
                FlashSelectors.DISCOUNT_KEYWORDS.any { t.contains(it) }
        }.distinct()

    /**
     * 从价格节点的祖先链（根→…→父）回溯猜商品名：
     * 逐层在祖先子树中收集非价格、非噪音的文本，取最长者。
     * M0 启发式，误差预期内；M1 用 fixtures 回归逐步收紧。
     */
    internal fun guessItemName(chain: List<SimpleNode>): String? {
        val ancestors = chain.takeLast(FlashSelectors.MAX_ANCESTOR_LEVELS).asReversed()
        for (ancestor in ancestors) {
            val best = ancestor.allTexts()
                .map { it.trim() }
                .filter { text ->
                    text.length >= 2 &&
                        !PRICE_REGEX.containsMatchIn(text) &&
                        FlashSelectors.NOISE_TEXTS.none { text.contains(it) }
                }
                .maxByOrNull { it.length }
            if (best != null) return best
        }
        return null
    }

    /** 店名启发式：含关键词的文本优先，否则取页面上第一个像样的标题文本。 */
    internal fun guessStoreName(root: SimpleNode): String? {
        val texts = root.allTexts().map { it.trim() }.filter { it.length in 4..30 }
        return texts.firstOrNull { t -> FlashSelectors.STORE_NAME_HINTS.any { t.contains(it) } }
            ?: texts.firstOrNull()
    }
}
