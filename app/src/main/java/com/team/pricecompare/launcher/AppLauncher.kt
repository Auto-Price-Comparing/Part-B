package com.team.pricecompare.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.team.pricecompare.accessibility.DumpAccessibilityService
import kotlinx.coroutines.delay

data class LaunchTarget(
    val packageName: String,
    val deepLink: String? = null,
)

enum class LaunchResult { SUCCESS, TIMEOUT, NOT_INSTALLED }

/**
 * App 拉起模块（B 负责，接口约定见 AGENTS.md）。
 * - manifest 已声明 <queries>，否则 Android 11+ 检测不到目标 App
 * - 本 App 持有悬浮窗权限，在 Android 10+ 后台启动 Activity 豁免名单内
 * - 落地检测复用无障碍服务的前台包名事件，不引入额外权限
 */
object AppLauncher {

    const val DEFAULT_TIMEOUT_MS = 8000L

    /** 淘宝闪购（原饿了么，包名沿用；eleme:// scheme M0 实测验证）。 */
    val FLASH = LaunchTarget("me.ele", "eleme://")
    val MEITUAN = LaunchTarget("com.meituan.takeout")
    /** 备选入口：淘宝 App 内闪购频道。 */
    val TAOBAO = LaunchTarget("com.taobao.taobao")

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** 发起拉起（不等待落地）。优先 deep link，失败降级为包名拉起首页。 */
    fun launch(context: Context, target: LaunchTarget): Boolean {
        if (!isInstalled(context, target.packageName)) return false
        val deepLinkIntent = target.deepLink
            ?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(target.packageName) }
            ?.takeIf { it.resolveActivity(context.packageManager) != null }
        val intent = deepLinkIntent
            ?: context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 拉起并轮询等待目标 App 到达前台。 */
    suspend fun launchAndAwait(
        context: Context,
        target: LaunchTarget,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): LaunchResult {
        if (!isInstalled(context, target.packageName)) return LaunchResult.NOT_INSTALLED
        if (!launch(context, target)) return LaunchResult.TIMEOUT
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (DumpAccessibilityService.foregroundPackage == target.packageName) {
                return LaunchResult.SUCCESS
            }
            delay(300)
        }
        return LaunchResult.TIMEOUT
    }
}
