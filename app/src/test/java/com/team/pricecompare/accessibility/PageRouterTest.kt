package com.team.pricecompare.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯 Kotlin 单测：手工构造 SimpleNode 树验证页面路由判定（不依赖 Android 运行时）。
 */
class PageRouterTest {

    private fun priceItem(name: String, price: String, top: Int): SimpleNode = SimpleNode(
        text = "", className = "android.view.ViewGroup", viewId = "", bounds = "0,$top,1080,${top + 200}",
        children = listOf(
            SimpleNode(name, "android.widget.TextView", "", "0,$top,400,${top + 60}"),
            SimpleNode(price, "android.widget.TextView", "", "800,$top,1000,${top + 60}"),
        ),
    )

    /** 店铺菜单页：店名头部（含月售/起送/配送）+ 3 个以上商品价格。 */
    private fun storeMenuTree(): SimpleNode = SimpleNode(
        text = "", className = "android.widget.FrameLayout", viewId = "", bounds = "0,0,1080,2400",
        children = listOf(
            SimpleNode("老乡鸡(示例店) 月售1000 起送¥20 配送费¥3", "android.widget.TextView", "", "0,100,1080,160"),
            priceItem("香辣鸡腿堡", "¥19.9", 400),
            priceItem("老母鸡汤", "¥15.0", 600),
            priceItem("农家小炒肉", "¥22.0", 800),
        ),
    )

    /** 搜索结果页：筛选栏 + 两张店铺卡片（各带一条「月售」）。 */
    private fun searchResultTree(): SimpleNode = SimpleNode(
        text = "", className = "android.widget.FrameLayout", viewId = "", bounds = "0,0,1080,2400",
        children = listOf(
            SimpleNode("综合排序", "android.widget.TextView", "", "0,100,200,160"),
            SimpleNode(
                text = "", className = "android.view.ViewGroup", viewId = "", bounds = "0,300,1080,600",
                children = listOf(
                    SimpleNode("老乡鸡(示例店)", "android.widget.TextView", "", "0,300,600,360"),
                    SimpleNode("月售1000 评分4.8", "android.widget.TextView", "", "0,360,600,420"),
                ),
            ),
            SimpleNode(
                text = "", className = "android.view.ViewGroup", viewId = "", bounds = "0,600,1080,900",
                children = listOf(
                    SimpleNode("麦当劳(示例店)", "android.widget.TextView", "", "0,600,600,660"),
                    SimpleNode("月售2000 评分4.6", "android.widget.TextView", "", "0,660,600,720"),
                ),
            ),
        ),
    )

    @Test
    fun `菜单页特征判定为 STORE_MENU`() {
        assertEquals(PageType.STORE_MENU, PageRouter.classify("flash", storeMenuTree()))
        assertEquals(PageType.STORE_MENU, PageRouter.classify("meituan", storeMenuTree()))
    }

    @Test
    fun `搜索页特征判定为 SEARCH_RESULT`() {
        assertEquals(PageType.SEARCH_RESULT, PageRouter.classify("flash", searchResultTree()))
    }

    @Test
    fun `价格节点不足 3 个不算菜单页`() {
        val tree = SimpleNode(
            text = "", className = "", viewId = "", bounds = "",
            children = listOf(
                SimpleNode("月售100 起送¥20", "android.widget.TextView", "", "0,0,500,60"),
                priceItem("唯一的菜", "¥9.9", 200),
            ),
        )
        assertEquals(PageType.OTHER, PageRouter.classify("flash", tree))
    }

    @Test
    fun `判不了一律 OTHER`() {
        val tree = SimpleNode("随便一个页面", "android.widget.TextView", "", "0,0,1,1")
        assertEquals(PageType.OTHER, PageRouter.classify("flash", tree))
        assertEquals(PageType.OTHER, PageRouter.classify("meituan", tree))
    }

    @Test
    fun `空树不崩溃返回 OTHER`() {
        assertEquals(PageType.OTHER, PageRouter.classify("flash", SimpleNode("", "", "", "")))
    }
}
