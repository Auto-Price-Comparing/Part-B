package com.team.pricecompare.accessibility

/** 价格文本匹配：¥19.9 / ￥20 / ¥ 8 等形态。解析策略核心——按文本而非 resource-id 定位。 */
val PRICE_REGEX = Regex("""[¥￥]\s*\d+(\.\d{1,2})?""")

/** 先序遍历收集全部非空文本（含自身）。 */
fun SimpleNode.allTexts(): List<String> =
    listOfNotNull(text.ifEmpty { null }) + children.flatMap { it.allTexts() }

/**
 * 找出所有文本含价格的节点，并带回各自的祖先链（根→…→父）。
 * 祖先链供解析器向上回溯找商品名（见 FlashParser.guessItemName）。
 */
fun SimpleNode.findPriceNodesWithChain(): List<Pair<SimpleNode, List<SimpleNode>>> {
    val out = mutableListOf<Pair<SimpleNode, List<SimpleNode>>>()
    fun walk(n: SimpleNode, chain: List<SimpleNode>) {
        if (PRICE_REGEX.containsMatchIn(n.text)) out.add(n to chain)
        n.children.forEach { walk(it, chain + n) }
    }
    walk(this, emptyList())
    return out
}
