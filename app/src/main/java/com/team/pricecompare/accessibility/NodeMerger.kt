package com.team.pricecompare.accessibility

/**
 * 多屏节点树合并：把 [scrollAndCollect] 逐屏抓到的 [SimpleNode] 拼成一棵虚拟树，
 * 供解析器一次解析多屏结果（菜单懒加载场景，单屏抓不全商品）。
 *
 * 合并规则：
 * - 虚拟根保留第一屏根节点的 text/className/viewId/bounds，children = 各屏 children 拼接；
 * - 兄弟节点按 (text, bounds) 去重：key 相同的重复节点只保留一份，
 *   其 children 递归合并（滑屏重叠区域在两屏中位置、结构一致时可被消重）；
 * - 滑屏重叠但 bounds 已变化的节点不会被这里的去重消掉，靠解析器按商品名
 *   distinctBy 兜底（见 FlashParser.parseStorePage），两层去重互不依赖。
 *
 * 纯函数，不修改输入；空列表返回一棵全空字段的虚拟根。
 */
fun mergeScreens(screens: List<SimpleNode>): SimpleNode {
    val first = screens.firstOrNull()
        ?: return SimpleNode(text = "", className = "", viewId = "", bounds = "")
    return first.copy(children = mergeSiblings(screens.flatMap { it.children }))
}

/** 兄弟列表按 (text, bounds) 去重，重复节点的 children 递归合并。 */
private fun mergeSiblings(nodes: List<SimpleNode>): List<SimpleNode> {
    val merged = LinkedHashMap<Pair<String, String>, SimpleNode>()
    for (n in nodes) {
        val key = n.text to n.bounds
        val existing = merged[key]
        merged[key] = if (existing == null) {
            n.copy(children = mergeSiblings(n.children))
        } else {
            existing.copy(children = mergeSiblings(existing.children + n.children))
        }
    }
    return merged.values.toList()
}
