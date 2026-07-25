package com.team.pricecompare.parsers

import com.team.pricecompare.accessibility.SimpleNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯 Kotlin 单测：手工构造 SimpleNode 树验证解析逻辑（不依赖 Android 运行时）。
 * 树中文案按美团公开界面习惯编造（评分/月售/配送费/起送/满减），
 * 真机 dump 的 fixtures 回归到位后需重新校准。
 */
class MeituanParserTest {

    private fun storeTree(): SimpleNode = SimpleNode(
        text = "", className = "android.widget.FrameLayout", viewId = "", bounds = "0,0,1080,2400",
        children = listOf(
            SimpleNode("老乡鸡(美团示例店)", "android.widget.TextView", "", "0,100,500,160"),
            SimpleNode("评分4.8分", "android.widget.TextView", "", "0,160,300,220"),
            SimpleNode("月售9999+", "android.widget.TextView", "", "300,160,600,220"),
            SimpleNode("配送费¥3.5", "android.widget.TextView", "", "600,160,900,220"),
            SimpleNode("¥20起送", "android.widget.TextView", "", "0,220,300,280"),
            SimpleNode("满30减12 满50减20", "android.widget.TextView", "", "300,220,900,280"),
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
    fun `完整店铺树解析出全部字段`() {
        val info = MeituanParser.parseStorePage(storeTree(), now = 123L)!!
        assertEquals("meituan", info.platform)
        assertEquals("老乡鸡(美团示例店)", info.storeName)
        assertEquals(4.8, info.rating, 0.001)
        assertEquals(9999, info.monthlySales)
        assertEquals(3.5, info.deliveryFee, 0.001)
        assertEquals(20.0, info.minOrder, 0.001)
        assertEquals(123L, info.capturedAt)
        assertEquals(2, info.items.size)
        assertEquals("香辣鸡腿堡", info.items[0].name)
        assertEquals(19.9, info.items[0].price, 0.001)
        assertEquals("老母鸡汤", info.items[1].name)
        assertEquals(15.0, info.items[1].price, 0.001)
    }

    @Test
    fun `配送费与起送价不会被当作商品价格`() {
        // 若不排除，「配送费¥3.5」「¥20起送」会混进 items，商品数将大于 2
        val info = MeituanParser.parseStorePage(storeTree())!!
        assertEquals(2, info.items.size)
    }

    @Test
    fun `无价格节点的页面返回 null`() {
        val empty = SimpleNode("随便一个页面", "android.widget.TextView", "", "0,0,1,1")
        assertNull(MeituanParser.parseStorePage(empty))
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
        // 「配送费¥3」被价格排除规则跳过；「¥20.0」周围只有噪音文本，
        // 猜不出商品名 → 整体返回 null
        assertNull(MeituanParser.parseStorePage(tree))
    }

    @Test
    fun `满减文案按序提取并去重`() {
        val info = MeituanParser.parseStorePage(storeTree())!!
        assertEquals(listOf("满30减12", "满50减20"), info.discounts)
    }

    @Test
    fun `缺店铺元信息时可选项回落默认值`() {
        val tree = SimpleNode(
            text = "", className = "", viewId = "", bounds = "",
            children = listOf(
                SimpleNode("张记面馆", "android.widget.TextView", "", ""),
                SimpleNode(
                    text = "", className = "", viewId = "", bounds = "",
                    children = listOf(
                        SimpleNode("牛肉面", "android.widget.TextView", "", ""),
                        SimpleNode("¥18", "android.widget.TextView", "", ""),
                    ),
                ),
            ),
        )
        val info = MeituanParser.parseStorePage(tree)!!
        assertEquals(0.0, info.rating, 0.001)
        assertEquals(0, info.monthlySales)
        assertEquals(0.0, info.deliveryFee, 0.001)
        assertEquals(0.0, info.minOrder, 0.001)
        assertEquals(emptyList<String>(), info.discounts)
    }
}
