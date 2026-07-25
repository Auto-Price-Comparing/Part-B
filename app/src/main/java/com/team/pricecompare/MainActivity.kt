package com.team.pricecompare

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.team.pricecompare.accessibility.DumpAccessibilityService
import com.team.pricecompare.engine.CaptureHub
import com.team.pricecompare.launcher.AppLauncher
import com.team.pricecompare.launcher.LaunchResult
import com.team.pricecompare.overlay.OverlayService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * M0/M1 控制台：权限与拉起验证 + 采集/比价状态展示。
 */
class MainActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        findViewById<Button>(R.id.btnShowOverlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, OverlayService::class.java))
            } else {
                toast("请先开启悬浮窗权限")
            }
        }

        findViewById<Button>(R.id.btnLaunchFlash).setOnClickListener {
            scope.launch {
                val result = AppLauncher.launchAndAwait(this@MainActivity, AppLauncher.FLASH)
                toast(
                    when (result) {
                        LaunchResult.SUCCESS -> "拉起成功，闪购已到前台"
                        LaunchResult.TIMEOUT -> "已发起拉起，但未检测到闪购到前台（无障碍服务是否已开启？）"
                        LaunchResult.NOT_INSTALLED -> "未安装淘宝闪购（me.ele）"
                    },
                )
            }
        }

        findViewById<Button>(R.id.btnRefreshStatus).setOnClickListener { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val foreground = DumpAccessibilityService.foregroundPackage ?: "未知（无障碍服务未开启？）"
        val dump = DumpAccessibilityService.lastDumpFile ?: "暂无 dump"
        val compare = CaptureHub.lastSummary
        findViewById<TextView>(R.id.tvStatus).text =
            "前台包名：$foreground\n最近 dump：$dump\n比价状态：$compare\n\ndump 导出：adb pull /sdcard/Android/data/$packageName/files/dumps/"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
