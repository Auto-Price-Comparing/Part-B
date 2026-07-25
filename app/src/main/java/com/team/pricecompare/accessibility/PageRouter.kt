package com.team.pricecompare.accessibility

// ================= 页面路由常量区（App 改版只改这里） =================
object PageRouterSelectors {
    /** 店铺菜单页特征词：店名头部常见的「月售/起送/配送」信息。 */
    val STORE_MENU_HINTS = listOf("月售", "起送", "配送")

    /** 搜索页筛选栏特征词：出现在搜索结果顶部容器。 */
    val SEARCH_BAR_HINTS = listOf("综合排序", "销量优先", "距离最近", "筛选")

    /** 店铺卡片的销量文本特征：搜索页每张卡片各带一条，菜单页只有店名头部一条。 */
    const val STORE_CARD_SALES_KEYWORD = "月售"

    /** 判为店铺菜单页所需的最少价格节点数（少于它说明不是菜单列表）。 */
    const val MIN_PRICE_NODES_FOR_MENU = 3

    /** 判为搜索页所需的最少「店名卡片」数（每张卡片通常各带一条「月售」文本）。 */
    const val MIN_STORE_CARDS_FOR_SEARCH = 2
}
// ============================================================================

/** 页面类型：判不了的一律 [OTHER]，宁漏勿错。 */
enum class PageType { STORE_MENU, SEARCH_RESULT, OTHER }

/**
 * 页面路由：按文本特征把当前页面归类，决定交给哪个解析流程。
 * 纯函数、面向 [SimpleNode]，真机节点树与 fixtures 走同一套逻辑。
 *
 * 判定优先级（宁漏勿错，顺序不可乱）：
 * 1. 价格节点 >= [PageRouterSelectors.MIN_PRICE_NODES_FOR_MENU]
 *    且含「月售/起送/配送」任一 → [PageType.STORE_MENU]；
 * 2. 含搜索筛选栏特征词，或「月售」文本 >= [PageRouterSelectors.MIN_STORE_CARDS_FOR_SEARCH] 条
 *    （搜索页每个店铺卡片各带一条，菜单页只有店名头部一条）→ [PageType.SEARCH_RESULT]；
 * 3. 其余 → [PageType.OTHER]。
 *
 * @param platform 平台标识（"meituan" | "flash"），预留给后续按平台分化判定规则，当前两平台共用。
 */
object PageRouter {

    fun classify(platform: String, root: SimpleNode): PageType {
        return runCatching {
            val texts = root.allTexts()
            val priceCount = root.findPriceNodesWithChain().size
            val hasMenuHint = texts.any { t -> PageRouterSelectors.STORE_MENU_HINTS.any { t.contains(it) } }
            if (priceCount >= PageRouterSelectors.MIN_PRICE_NODES_FOR_MENU && hasMenuHint) {
                return PageType.STORE_MENU
            }
            val storeCards = texts.count { it.contains(PageRouterSelectors.STORE_CARD_SALES_KEYWORD) }
            val hasSearchBar = texts.any { t -> PageRouterSelectors.SEARCH_BAR_HINTS.any { t.contains(it) } }
            if (hasSearchBar || storeCards >= PageRouterSelectors.MIN_STORE_CARDS_FOR_SEARCH) {
                return PageType.SEARCH_RESULT
            }
            PageType.OTHER
        }.getOrDefault(PageType.OTHER)
    }
}
