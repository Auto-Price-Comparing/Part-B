package com.team.pricecompare.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.team.pricecompare.engine.CapturePipeline
import com.team.pricecompare.launcher.AppLauncher
import com.team.pricecompare.launcher.LaunchResult
import com.team.pricecompare.launcher.LaunchTarget
import kotlinx.coroutines.delay

// ================= 一键全采常量区（目标/节奏/验证码词表只改这里） =================
object AutoCaptureRules {
    /** 拉起落地后的稳定等待时长（毫秒）：等首页弹窗/懒加载就位。 */
    const val SETTLE_AFTER_LAUNCH_MS = 3000L

    /** 每平台最大滑屏数（上限兜底，正常到底会提前停止）。 */
    const val MAX_SWIPES_PER_PLATFORM = 8

    /** 验证码特征词：命中即停止采集并提示人工处理（合规红线：绝不自动破解）。 */
    val CAPTCHA_HINTS = listOf("安全验证", "滑块", "拖动滑块", "人机验证", "验证码")

    /** 文本列表中是否出现验证码特征。纯函数，便于单测。 */
    fun containsCaptcha(texts: List<String>): Boolean =
        texts.any { t -> CAPTCHA_HINTS.any { hint -> t.contains(hint) } }
}

/** 全采目标：拉起参数 + 平台标识。顺序即采集顺序。 */
data class AutoCaptureTarget(val target: LaunchTarget, val platform: String)

val DEFAULT_AUTO_CAPTURE_TARGETS = listOf(
    AutoCaptureTarget(AppLauncher.FLASH, "flash"),
    AutoCaptureTarget(AppLauncher.MEITUAN, "meituan"),
)
// ============================================================================

/** 单平台全采结果状态。 */
enum class CaptureStatus { SUCCESS, NOT_INSTALLED, LAUNCH_TIMEOUT, CAPTCHA, NO_DATA }

/** 单平台全采结果：状态 + 一句人话摘要。 */
data class CaptureOutcome(val platform: String, val status: CaptureStatus, val summary: String)

/**
 * 一键全采编排器（M4）：自动驾驶各外卖 App 完成「拉起 → 落地 → 滑屏采集 → 解析入库」。
 *
 * 采集层组件，不碰 UI：进度通过 [onProgress] 回调交给调用方（服务层转发到 CaptureHub）。
 * 合规约束：
 * - 节奏由 [scrollAndCollect] 的 1 秒限速保证，本文件不提供任何绕过路径；
 * - 命中验证码特征词立即中止该平台并报告，绝不尝试自动破解；
 * - 只读菜单与价格，绝不触碰下单/支付节点。
 *
 * 单平台失败不中断整体；任何异常都兜底为 [CaptureStatus.NO_DATA]，不向上抛。
 *
 * @return 各平台逐条结果，顺序与 [targets] 一致。
 */
suspend fun AccessibilityService.captureAll(
    targets: List<AutoCaptureTarget> = DEFAULT_AUTO_CAPTURE_TARGETS,
    onProgress: (String) -> Unit = {},
): List<CaptureOutcome> {
    val outcomes = mutableListOf<CaptureOutcome>()
    for ((index, t) in targets.withIndex()) {
        val label = platformLabel(t.platform)
        val outcome = runCatching {
            captureOne(t, index + 1, targets.size, onProgress)
        }.getOrElse { e ->
            Log.w(TAG, "capture $label failed", e)
            CaptureOutcome(t.platform, CaptureStatus.NO_DATA, "$label：采集异常（${e.message}）")
        }
        outcomes += outcome
        onProgress(outcome.summary)
    }
    return outcomes
}

private const val TAG = "AutoCapture"

private suspend fun AccessibilityService.captureOne(
    t: AutoCaptureTarget,
    step: Int,
    total: Int,
    onProgress: (String) -> Unit,
): CaptureOutcome {
    val label = platformLabel(t.platform)
    onProgress("[$step/$total] 正在拉起 $label…")
    when (AppLauncher.launchAndAwait(this, t.target)) {
        LaunchResult.NOT_INSTALLED ->
            return CaptureOutcome(t.platform, CaptureStatus.NOT_INSTALLED, "$label：未安装，跳过")
        LaunchResult.TIMEOUT ->
            return CaptureOutcome(t.platform, CaptureStatus.LAUNCH_TIMEOUT, "$label：拉起后未到前台，跳过")
        LaunchResult.SUCCESS -> Unit
    }

    // 等首页稳定；期间无障碍服务仍在监听弹窗并自动关闭
    delay(AutoCaptureRules.SETTLE_AFTER_LAUNCH_MS)

    // 滑屏前先查一次验证码：命中立即中止，不继续任何模拟操作
    val landing = rootInActiveWindow?.toSimpleNode()
        ?: return CaptureOutcome(t.platform, CaptureStatus.NO_DATA, "$label：读不到界面节点，跳过")
    if (AutoCaptureRules.containsCaptcha(landing.allTexts())) {
        return CaptureOutcome(t.platform, CaptureStatus.CAPTCHA, "$label：检测到验证码，请人工处理后重试")
    }

    onProgress("[$step/$total] $label 采集菜单中，请勿操作手机…")
    val screens = scrollAndCollect(maxSwipes = AutoCaptureRules.MAX_SWIPES_PER_PLATFORM)

    // 滑屏中途也可能弹出验证，合并前再查一次
    if (screens.any { AutoCaptureRules.containsCaptcha(it.allTexts()) }) {
        return CaptureOutcome(t.platform, CaptureStatus.CAPTCHA, "$label：检测到验证码，请人工处理后重试")
    }

    val merged = mergeScreens(screens)
    val store = CapturePipeline.parseStore(t.platform, merged)
        ?: return CaptureOutcome(t.platform, CaptureStatus.NO_DATA, "$label：当前页不是店铺菜单页，未采集")

    // 走完整流水线（持久化 + 跨平台比价发布），等待完成再切下一平台
    CapturePipeline.processAwait(applicationContext, t.target.packageName, merged)
    return CaptureOutcome(
        t.platform,
        CaptureStatus.SUCCESS,
        "$label：已采集「${store.storeName}」${store.items.size} 件商品",
    )
}

private fun platformLabel(platform: String): String = when (platform) {
    "meituan" -> "美团"
    "flash" -> "淘宝闪购"
    else -> platform
}
