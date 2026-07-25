package com.team.pricecompare.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import com.team.pricecompare.engine.CaptureHub
import com.team.pricecompare.engine.CapturePipeline
import com.team.pricecompare.parsers.FlashSelectors
import com.team.pricecompare.parsers.MeituanSelectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * M0/M1 核心无障碍采集服务。
 * - 监听目标外卖 App（包名见 manifest 的 accessibility_service_config）
 * - 记录前台包名（供 AppLauncher 做落地检测，零额外权限）
 * - 页面变化后节流 3s，dump 节点树 JSON 并走 M1 解析/比价流水线
 * - 检测到已知弹窗关闭按钮时自动点击（弹窗会遮挡菜单节点）
 * - M4：持有服务实例供「一键全采」编排器调用；全采期间暂停常规节流 dump
 */
class DumpAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DumpService"
        private const val DUMP_THROTTLE_MS = 3000L

        val TARGET_PACKAGES = setOf("me.ele", "com.meituan.takeout", "com.taobao.taobao")

        /** 当前前台 App 包名；null 表示服务未运行或尚无事件。 */
        @Volatile
        var foregroundPackage: String? = null
            private set

        /** 最近一次 dump 的文件路径，供 MainActivity 状态页展示。 */
        @Volatile
        var lastDumpFile: String? = null
            private set

        /** 运行中的服务实例；null 表示服务未开启。供 M4 一键全采入口使用。 */
        @Volatile
        var instance: DumpAccessibilityService? = null
            private set

        /** 一键全采进行中：暂停常规节流 dump（滑屏事件不打断采集节奏）。 */
        @Volatile
        var autoCapturing: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingDump: Runnable? = null

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = pkg
        }
        if (pkg !in TARGET_PACKAGES) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            tryDismissPopups()
        }
        // 全采期间滑屏会触发大量事件，暂停常规 dump，避免对半屏数据跑流水线
        if (!autoCapturing) scheduleDump(pkg)
    }

    /** 节流：页面连续变化时只 dump 最后一次，模拟真人节奏，避免高频读屏。 */
    private fun scheduleDump(pkg: String) {
        pendingDump?.let { handler.removeCallbacks(it) }
        val r = Runnable { dumpNow(pkg) }
        pendingDump = r
        handler.postDelayed(r, DUMP_THROTTLE_MS)
    }

    private fun dumpNow(pkg: String) {
        val root = rootInActiveWindow ?: return
        try {
            val tree = root.toSimpleNode()
            val priceCount = tree.findPriceNodesWithChain().size
            val dir = File(getExternalFilesDir(null), "dumps").apply { mkdirs() }
            val file = File(
                dir,
                "${pkg.replace('.', '_')}_${System.currentTimeMillis()}_p$priceCount.json",
            )
            file.writeText(tree.toJson().toString())
            lastDumpFile = file.absolutePath
            Log.i(TAG, "dumped ${file.name}, priceNodes=$priceCount")
            CapturePipeline.process(this, pkg, tree)
        } catch (e: Exception) {
            Log.w(TAG, "dump failed", e)
        }
    }

    /** 点击文案精确匹配的关闭类按钮（"×"/"关闭"/"跳过" 等，见各平台 Selectors）。 */
    private fun tryDismissPopups() {
        val root = rootInActiveWindow ?: return
        val closeTexts = FlashSelectors.POPUP_CLOSE_TEXTS + MeituanSelectors.POPUP_CLOSE_TEXTS
        fun walk(n: AccessibilityNodeInfo) {
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (n.isClickable && (t in closeTexts || d in closeTexts)) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
    }

    /**
     * M4 一键全采入口：防止重入；进度逐条写入 [CaptureHub] 摘要，
     * 结束后恢复常规节流 dump。编排逻辑见 [captureAll]。
     */
    fun startAutoCapture() {
        if (autoCapturing) return
        autoCapturing = true
        pendingDump?.let { handler.removeCallbacks(it) }
        serviceScope.launch {
            try {
                CaptureHub.publishProgress("一键全采开始…")
                val outcomes = captureAll { msg -> CaptureHub.publishProgress(msg) }
                val succeeded = outcomes.count { it.status == CaptureStatus.SUCCESS }
                CaptureHub.publishProgress("一键全采结束：${succeeded}/${outcomes.size} 个平台成功")
            } finally {
                autoCapturing = false
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        foregroundPackage = null
        instance = null
        super.onDestroy()
    }
}
