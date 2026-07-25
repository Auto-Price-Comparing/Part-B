package com.team.pricecompare.parsers

import com.team.pricecompare.accessibility.SimpleNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯 Kotlin 单测：手工构造 SimpleNode 树验证解析逻辑（不依赖 Android 运行时）。
 * 真机 dump 的 fixtures 回归在 M1 接入。
 */
class FlashParserTest {

    private fun storeTree(): SimpleNode = SimpleNode(
        text = "", className = "android.widget.FrameLayout", viewId = "", bounds = "0,0,1080,2400",
        children = listOf(
            SimpleNode("老乡鸡(示例店)", "android.widget.TextView", "", "0,100,500,160"),
            SimpleNode(
                text = "", className = "android.view.ViewGroup", viewId = "", bounds = "0,400,1080,600",
                children = listOf(
                    SimpleNode("香辣鸡腿堡", "android.widget.TextView", "", "0,400,400,460"),
                    SimpleNode("¥19.9", "android.widget.TextView", "", "800,400,1000,460"),
                ),
            ),
            SimpleNode(
                text = "", className = "android.view.ViewGroup", viewId = "", bounds = "0,600,1080,800",
                children = listOf(
                    SimpleNode("老母鸡汤", "android.widget.TextView", "", "0,600,400,660"),
                    SimpleNode("¥15.0", "android.widget.TextView", "", "800,600,1000,660"),
                ),
            ),
        ),
    )

    @Test
    fun `解析出店名与商品价格`() {
        val info = FlashParser.parseStorePage(storeTree(), now = 123L)!!
        assertEquals("flash", info.platform)
        assertEquals("老乡鸡(示例店)", info.storeName)
        assertEquals(123L, info.capturedAt)
        assertEquals(2, info.items.size)
        assertEquals("香辣鸡腿堡", info.items[0].name)
        assertEquals(19.9, info.items[0].price, 0.001)
        assertEquals("老母鸡汤", info.items[1].name)
        assertEquals(15.0, info.items[1].price, 0.001)
    }

    @Test
    fun `无价格节点的页面返回 null`() {
        val empty = SimpleNode("随便一个页面", "android.widget.TextView", "", "0,0,1,1")
        assertNull(FlashParser.parseStorePage(empty))
    }

    @Test
    fun `噪音文本不会被当作商品名`() {
        val tree = SimpleNode(
            text = "", className = "", viewId = "", bounds = "",
            children = listOf(
                SimpleNode("配送费¥3", "android.widget.TextView", "", ""),
                SimpleNode(
                    text = "", className = "", viewId = "", bounds = "",
                    children = listOf(
                        SimpleNode("起送价说明", "android.widget.TextView", "", ""),
                        SimpleNode("¥20.0", "android.widget.TextView", "", ""),
                    ),
                ),
            ),
        )
        // 唯一的价格节点周围只有噪音文本，猜不出商品名 → 整体返回 null
        assertNull(FlashParser.parseStorePage(tree))
    }

    private fun itemRow(name: String, price: String, top: Int): SimpleNode = SimpleNode(
        text = "", className = "android.view.ViewGroup", viewId = "", bounds = "0,$top,1080,${top + 200}",
        children = listOf(
            SimpleNode(name, "android.widget.TextView", "", "0,$top,400,${top + 60}"),
            SimpleNode(price, "android.widget.TextView", "", "800,$top,1000,${top + 60}"),
        ),
    )

    private fun textNode(text: String, top: Int): SimpleNode =
        SimpleNode(text, "android.widget.TextView", "", "0,$top,1080,${top + 50}")

    /** 带完整店铺元信息（评分/月售/配送费/起送/优惠）的店铺树。 */
    private fun fullStoreTree(): SimpleNode = SimpleNode(
        text = "", className = "android.widget.FrameLayout", viewId = "", bounds = "0,0,1080,2400",
        children = listOf(
            textNode("老乡鸡(示例店)", 100),
            textNode("评分4.8", 160),
            textNode("月售1000+", 210),
            textNode("配送费¥3", 260),
            textNode("起送¥20", 310),
            textNode("满30减15", 360),
            textNode("新客立减券", 410),
            itemRow("香辣鸡腿堡", "¥19.9", 500),
            itemRow("老母鸡汤", "¥15.0", 700),
        ),
    )

    @Test
    fun `解析完整店铺元信息`() {
        val info = FlashParser.parseStorePage(fullStoreTree(), now = 456L)!!
        assertEquals("老乡鸡(示例店)", info.storeName)
        assertEquals(4.8, info.rating, 0.001)
        assertEquals(1000, info.monthlySales)
        assertEquals(3.0, info.deliveryFee, 0.001)
        assertEquals(20.0, info.minOrder, 0.001)
        assertEquals(listOf("满30减15", "新客立减券"), info.discounts)
        assertEquals(456L, info.capturedAt)
        // 配送费/起送价节点含 ¥，绝不能被当成商品
        assertEquals(2, info.items.size)
        assertEquals("香辣鸡腿堡", info.items[0].name)
        assertEquals(19.9, info.items[0].price, 0.001)
        assertEquals("老母鸡汤", info.items[1].name)
        assertEquals(15.0, info.items[1].price, 0.001)
    }

    @Test
    fun `多屏合并后重复商品按名去重`() {
        val screen1 = SimpleNode(
            text = "", className = "android.widget.FrameLayout", viewId = "", bounds = "0,0,1080,2400",
            children = listOf(
                textNode("老乡鸡(示例店)", 100),
                itemRow("香辣鸡腿堡", "¥19.9", 400),
                itemRow("老母鸡汤", "¥15.0", 600),
            ),
        )
        // 第二屏：重复上一屏最后一个商品（懒加载衔接），并带来新商品
        val screen2 = SimpleNode(
            text = "", className = "android.widget.FrameLayout", viewId = "", bounds = "0,0,1080,2400",
            children = listOf(
                itemRow("老母鸡汤", "¥15.0", 100),
                itemRow("可口可乐", "¥5.0", 300),
            ),
        )
        val info = FlashParser.parseStorePages(listOf(screen1, screen2))!!
        assertEquals("老乡鸡(示例店)", info.storeName)
        assertEquals(3, info.items.size)
        assertEquals(listOf("香辣鸡腿堡", "老母鸡汤", "可口可乐"), info.items.map { it.name })
        assertEquals(15.0, info.items[1].price, 0.001)
        assertEquals(5.0, info.items[2].price, 0.001)
    }

    @Test
    fun `免配送费解析为零`() {
        val tree = SimpleNode(
            text = "", className = "android.widget.FrameLayout", viewId = "", bounds = "0,0,1080,2400",
            children = listOf(
                textNode("老乡鸡(示例店)", 100),
                textNode("免配送费", 160),
                textNode("20元起送", 210),
                itemRow("香辣鸡腿堡", "¥19.9", 400),
            ),
        )
        val info = FlashParser.parseStorePage(tree)!!
        assertEquals(0.0, info.deliveryFee, 0.001)
        assertEquals(20.0, info.minOrder, 0.001)
        assertEquals(1, info.items.size)
    }
}
