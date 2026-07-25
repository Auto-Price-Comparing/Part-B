package com.team.pricecompare.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 Kotlin 单测：系统 ENABLED_ACCESSIBILITY_SERVICES 串的解析（移植自 C 侧）。
 * 串格式为冒号分隔的 ComponentName 扁平串列表。
 */
class OverlayControllerMatchTest {

    private val expected = "com.team.pricecompare/com.team.pricecompare.accessibility.DumpAccessibilityService"

    @Test
    fun `null 与空串不匹配`() {
        assertFalse(OverlayController.matchesEnabledService(null, expected))
        assertFalse(OverlayController.matchesEnabledService("", expected))
        assertFalse(OverlayController.matchesEnabledService("   ", expected))
    }

    @Test
    fun `单条精确匹配`() {
        assertTrue(OverlayController.matchesEnabledService(expected, expected))
    }

    @Test
    fun `多条中包含目标即匹配`() {
        val raw = "com.other.app/.OtherService:$expected:com.third.app/.ThirdService"
        assertTrue(OverlayController.matchesEnabledService(raw, expected))
    }

    @Test
    fun `匹配忽略大小写与前后空格`() {
        assertTrue(OverlayController.matchesEnabledService(expected.uppercase(), expected))
        assertTrue(OverlayController.matchesEnabledService("  $expected  ", expected))
    }

    @Test
    fun `前缀子串不误匹配`() {
        // 包名相同但服务类名不同 / 目标串是别人串的前缀，都不应误判
        val other = "com.team.pricecompare/com.team.pricecompare.accessibility.OtherService"
        assertFalse(OverlayController.matchesEnabledService(other, expected))
        assertFalse(OverlayController.matchesEnabledService(expected + "Extra", expected))
    }

    @Test
    fun `其他包名的服务不匹配`() {
        assertFalse(
            OverlayController.matchesEnabledService("com.other.app/.OtherService", expected),
        )
    }

    @Test
    fun `尾冒号不干扰匹配`() {
        assertTrue(OverlayController.matchesEnabledService("$expected:", expected))
    }
}
