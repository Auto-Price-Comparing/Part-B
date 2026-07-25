package com.team.pricecompare.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.team.pricecompare.accessibility.DumpAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍开关监听与悬浮窗服务自动启停（移植自 C 侧，M5 保活增强）。
 * 进程级 ContentObserver 监听系统无障碍开关：「无障碍已开启 + 悬浮窗权限已授予」
 * 时自动拉起前台化的 OverlayService，任一不满足即停止——用户在系统设置里
 * 关掉/重开无障碍，悬浮窗随之自动消失/恢复，无需手动回 App 操作。
 */
object OverlayController {

    private val _accessibilityEnabled = MutableStateFlow(false)

    /** 系统设置中本 App 的无障碍服务是否已开启（不等于服务进程存活）。 */
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    // lazy 延迟到 bind() 才触碰 Looper：纯 JVM 单测加载本对象时无 Android 运行时
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var observer: ContentObserver? = null

    /**
     * 系统 ENABLED_ACCESSIBILITY_SERVICES 串中是否包含本服务。
     * 串格式为冒号分隔的 ComponentName 扁平串列表；纯函数，便于单测。
     */
    fun matchesEnabledService(raw: String?, expected: String): Boolean {
        if (raw.isNullOrBlank()) return false
        return raw.split(':').any { it.trim().equals(expected, ignoreCase = true) }
    }

    /** 注册监听（幂等）；开关变化时刷新状态并自动启停悬浮窗服务。 */
    fun bind(context: Context) {
        val app = context.applicationContext
        if (observer == null) {
            val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (uri != null) {
                val o = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        refresh(app)
                        ensureService(app)
                    }
                }
                app.contentResolver.registerContentObserver(uri, false, o)
                observer = o
            }
        }
        refresh(app)
    }

    fun refresh(context: Context) {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        _accessibilityEnabled.value = matchesEnabledService(raw, expectedComponent(context))
    }

    /** 按「无障碍已开启 + 悬浮窗权限已授予」决定启动或停止 OverlayService。 */
    fun ensureService(context: Context) {
        refresh(context)
        val shouldRun = _accessibilityEnabled.value && Settings.canDrawOverlays(context)
        val intent = Intent(context, OverlayService::class.java)
        if (shouldRun) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }
    }

    private fun expectedComponent(context: Context): String {
        val cls = DumpAccessibilityService::class.java.name
        return ComponentName(context.packageName, cls).flattenToString()
    }
}
