package com.team.pricecompare.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 Kotlin 单测：手工构造 SimpleNode 树验证多屏合并逻辑（不依赖 Android 运行时）。
 */
class NodeMergerTest {

    private fun leaf(text: String, bounds: String): SimpleNode =
        SimpleNode(text, "android.widget.TextView", "", bounds)

    private fun root(vararg children: SimpleNode): SimpleNode =
        SimpleNode("", "android.widget.FrameLayout", "", "0,0,1080,2400", children.toList())

    @Test
    fun `多屏 children 拼接且按 text+bounds 去重`() {
        val screen1 = root(leaf("商品A", "0,0,100,60"), leaf("商品B", "0,60,100,120"))
        val screen2 = root(leaf("商品B", "0,60,100,120"), leaf("商品C", "0,120,100,180"))
        val merged = mergeScreens(listOf(screen1, screen2))
        assertEquals(listOf("商品A", "商品B", "商品C"), merged.children.map { it.text })
    }

    @Test
    fun `文本相同但 bounds 不同的节点保留（交给解析器按商品名去重）`() {
        val screen1 = root(leaf("商品A", "0,0,100,60"))
        val screen2 = root(leaf("商品A", "0,500,100,560"))
        val merged = mergeScreens(listOf(screen1, screen2))
        assertEquals(2, merged.children.size)
    }

    @Test
    fun `重复节点的 children 递归合并`() {
        val screen1 = root(
            SimpleNode("", "android.view.ViewGroup", "", "0,0,1080,200", listOf(leaf("商品A", "0,0,100,60"))),
        )
        val screen2 = root(
            SimpleNode("", "android.view.ViewGroup", "", "0,0,1080,200", listOf(leaf("¥19.9", "800,0,1000,60"))),
        )
        val merged = mergeScreens(listOf(screen1, screen2))
        assertEquals(1, merged.children.size)
        assertEquals(listOf("商品A", "¥19.9"), merged.children[0].children.map { it.text })
    }

    @Test
    fun `虚拟根保留第一屏根节点字段`() {
        val screen1 = root(leaf("商品A", "0,0,100,60"))
        val merged = mergeScreens(listOf(screen1))
        assertEquals("android.widget.FrameLayout", merged.className)
        assertEquals("0,0,1080,2400", merged.bounds)
    }

    @Test
    fun `空列表返回全空虚拟根`() {
        val merged = mergeScreens(emptyList())
        assertEquals("", merged.text)
        assertTrue(merged.children.isEmpty())
    }

    @Test
    fun `不修改输入树`() {
        val screen1 = root(leaf("商品A", "0,0,100,60"))
        val screen2 = root(leaf("商品A", "0,0,100,60"))
        mergeScreens(listOf(screen1, screen2))
        assertEquals(1, screen1.children.size)
        assertEquals(1, screen2.children.size)
    }
}
