package com.team.pricecompare.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// ================= 手势常量区（合规红线：模拟真人节奏，只改这里） =================
object GestureDefaults {
    /** 上滑起点：屏幕高度的 3/4 处。 */
    const val SWIPE_START_HEIGHT_RATIO = 0.75f

    /** 上滑终点：屏幕高度的 1/4 处。 */
    const val SWIPE_END_HEIGHT_RATIO = 0.25f

    /** 一次滑动的手势时长（毫秒），接近真人滑屏。 */
    const val SWIPE_DURATION_MS = 500L

    /** 两次滑动之间的最小间隔（毫秒）——合规红线：不得小于 1 秒。 */
    const val MIN_SWIPE_INTERVAL_MS = 1000L
}
// ============================================================================

/**
 * 无障碍手势工具（M1 滑屏采集基础设施）。
 * 全部 suspend 风格：手势完成用 [GestureResultCallback] + [suspendCancellableCoroutine] 等待，
 * 节奏控制用 [kotlinx.coroutines.delay]。只实现「上滑浏览」，
 * 绝不触碰下单/支付相关节点（合规红线）。
 */

/**
 * 从屏幕 3/4 高度匀速上滑到 1/4 高度。
 * @return 手势是否被系统接受并完成；取消或失败返回 false，调用方应中止采集。
 */
suspend fun AccessibilityService.swipeUp(durationMs: Long = GestureDefaults.SWIPE_DURATION_MS): Boolean {
    val dm = resources.displayMetrics
    val x = dm.widthPixels / 2f
    val path = Path().apply {
        moveTo(x, dm.heightPixels * GestureDefaults.SWIPE_START_HEIGHT_RATIO)
        lineTo(x, dm.heightPixels * GestureDefaults.SWIPE_END_HEIGHT_RATIO)
    }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
        .build()
    return suspendCancellableCoroutine { cont ->
        val accepted = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        // 系统拒收手势时不会触发任何回调，直接返回失败，避免协程挂起。
        if (!accepted && cont.isActive) cont.resume(false)
    }
}

/**
 * 滑一屏抓一屏：取当前 [AccessibilityService.getRootInActiveWindow] 转 [SimpleNode] →
 * 上滑 → 等 [settleMs] 让懒加载内容稳定 → 再取，直到连续两屏文本集合相同（判定到底）
 * 或滑动次数达到 [maxSwipes]。
 *
 * 节奏合规：每轮滑动后至少等待 [GestureDefaults.MIN_SWIPE_INTERVAL_MS]，
 * [settleMs] 传入更小的值也会被抬到 1 秒，调用方无法绕过限速。
 *
 * @return 逐屏抓到的 [SimpleNode] 列表；任何一步 root 为空或手势失败就提前返回已抓到的部分。
 */
suspend fun AccessibilityService.scrollAndCollect(
    maxSwipes: Int = 8,
    settleMs: Long = 1200,
): List<SimpleNode> {
    val screens = mutableListOf<SimpleNode>()
    var prevTexts: Set<String>? = null
    repeat(maxSwipes) {
        val root = rootInActiveWindow?.toSimpleNode() ?: return screens
        screens.add(root)
        val texts = root.allTexts().toSet()
        if (prevTexts != null && texts == prevTexts) return screens // 连续两屏内容相同 → 到底
        prevTexts = texts
        if (!swipeUp()) return screens
        delay(settleMs.coerceAtLeast(GestureDefaults.MIN_SWIPE_INTERVAL_MS))
    }
    return screens
}
