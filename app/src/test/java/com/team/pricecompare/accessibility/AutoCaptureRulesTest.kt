package com.team.pricecompare.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 Kotlin 单测：验证码特征词检测（合规红线：命中即停，绝不自动破解）。
 */
class AutoCaptureRulesTest {

    @Test
    fun `空文本列表不命中`() {
        assertFalse(AutoCaptureRules.containsCaptcha(emptyList()))
    }

    @Test
    fun `常规菜单文本不命中`() {
        assertFalse(
            AutoCaptureRules.containsCaptcha(
                listOf("老乡鸡", "月售1000", "老母鸡汤", "¥15.0", "满40减12"),
            ),
        )
    }

    @Test
    fun `命中任一特征词即判定为验证码`() {
        assertTrue(AutoCaptureRules.containsCaptcha(listOf("请完成安全验证")))
        assertTrue(AutoCaptureRules.containsCaptcha(listOf("拖动滑块完成拼图")))
        assertTrue(AutoCaptureRules.containsCaptcha(listOf("人机验证中，请稍候")))
        assertTrue(AutoCaptureRules.containsCaptcha(listOf("正常文本", "请输入验证码")))
    }
}
