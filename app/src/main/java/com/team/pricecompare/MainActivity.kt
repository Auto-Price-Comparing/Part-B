package com.team.pricecompare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.team.pricecompare.accessibility.DumpAccessibilityService
import com.team.pricecompare.engine.CaptureHub
import com.team.pricecompare.engine.analysis.PriceAnalyzer
import com.team.pricecompare.engine.analysis.StoreAnalysis
import com.team.pricecompare.engine.data.CouponRepository
import com.team.pricecompare.engine.data.SnapshotRepository
import com.team.pricecompare.launcher.AppLauncher
import com.team.pricecompare.launcher.LaunchResult
import com.team.pricecompare.overlay.OverlayService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制台：M0/M1 权限与拉起验证 + M3 红包录入/商家分析 + M4 一键全采入口。
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

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

        findViewById<Button>(R.id.btnAutoCapture).setOnClickListener {
            // 按钮点击属于用户手势上下文，满足 Android 10+ 后台拉起的豁免兜底
            val service = DumpAccessibilityService.instance
            if (service == null) {
                toast("无障碍服务未运行，请先开启")
            } else {
                service.startAutoCapture()
                toast("已开始一键全采，请勿操作手机")
            }
        }

        findViewById<Button>(R.id.btnRefreshStatus).setOnClickListener { refreshStatus() }

        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener { requestIgnoreBatteryOpt() }
        findViewById<Button>(R.id.btnAutoStart).setOnClickListener { openAutoStartSettings() }

        findViewById<Button>(R.id.btnCouponSave).setOnClickListener { saveCoupon() }
        findViewById<Button>(R.id.btnCouponClear).setOnClickListener {
            scope.launch {
                runCatching { CouponRepository(this@MainActivity).deleteAll() }
                    .onSuccess { toast("已清空红包") }
                    .onFailure { toast("清空失败：${it.message}") }
                refreshCoupons()
            }
        }

        findViewById<Button>(R.id.btnAnalyze).setOnClickListener { runAnalysis() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshCoupons()
    }

    private fun refreshStatus() {
        val serviceAlive = DumpAccessibilityService.instance != null
        val serviceStatus =
            if (serviceAlive) "运行中" else "未运行（可能被系统杀死，请重新开启）"
        val foreground = DumpAccessibilityService.foregroundPackage ?: "未知（无障碍服务未开启？）"
        val dump = DumpAccessibilityService.lastDumpFile ?: "暂无 dump"
        val compare = CaptureHub.lastSummary
        findViewById<TextView>(R.id.tvStatus).text =
            "无障碍服务：$serviceStatus\n前台包名：$foreground\n最近 dump：$dump\n比价状态：$compare\n\ndump 导出：adb pull /sdcard/Android/data/$packageName/files/dumps/"
    }

    // ================= M5 保活引导 =================

    /** 引导用户把本 App 加入电池优化白名单；已在白名单则直接提示。 */
    private fun requestIgnoreBatteryOpt() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在电池优化白名单中")
            return
        }
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(request) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast("无法打开电池优化设置，请手动前往系统设置") }
        }
    }

    /**
     * 跳转国产 ROM 的自启动/后台管理页：按厂商逐个尝试常见 intent，
     * 全部失败兜底跳本应用系统详情页（用户可自行找「自启动/电池」入口）。
     */
    private fun openAutoStartSettings() {
        val candidates = listOf(
            // 小米
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为/荣耀
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            // OPPO
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.oplus.safecenter/.startupapp.StartupAppListActivity",
            // vivo
            "com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
        )
        for (c in candidates) {
            val (pkg, cls) = c.split("/", limit = 2).let { it[0] to it[1] }
            val intent = Intent().setClassName(pkg, if (cls.startsWith(".")) "$pkg$cls" else cls)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }.onFailure { toast("无法打开设置页，请手动前往系统设置") }
    }

    // ================= M3 红包录入 =================

    private fun saveCoupon() {
        val threshold = findViewById<EditText>(R.id.etCouponThreshold).text.toString().toDoubleOrNull()
        val amount = findViewById<EditText>(R.id.etCouponAmount).text.toString().toDoubleOrNull()
        if (threshold == null || amount == null || threshold < 0 || amount <= 0) {
            toast("请输入合法的门槛和金额")
            return
        }
        val platform =
            if (findViewById<RadioButton>(R.id.rbCouponMeituan).isChecked) "meituan" else "flash"
        scope.launch {
            runCatching { CouponRepository(this@MainActivity).save(platform, threshold, amount) }
                .onSuccess { toast("已录入") }
                .onFailure { toast("录入失败：${it.message}") }
            refreshCoupons()
        }
    }

    private fun refreshCoupons() {
        scope.launch {
            val coupons = runCatching { CouponRepository(this@MainActivity).list() }
                .getOrDefault(emptyList())
            findViewById<TextView>(R.id.tvCouponList).text =
                if (coupons.isEmpty()) {
                    "暂无红包"
                } else {
                    coupons.joinToString("\n") {
                        "${CaptureHub.platformLabel(it.platform)} 满${fmt(it.threshold)}减${fmt(it.amount)}"
                    }
                }
        }
    }

    // ================= M3 商家分析 =================

    private fun runAnalysis() {
        val view = findViewById<TextView>(R.id.tvAnalysis)
        view.text = "分析中…"
        scope.launch {
            val snapshots = runCatching { SnapshotRepository(this@MainActivity).latest() }
                .getOrDefault(emptyList())
            if (snapshots.isEmpty()) {
                view.text = "暂无快照，请先浏览店铺页"
                return@launch
            }
            val analyses = PriceAnalyzer.analyze(snapshots)
            view.text = if (analyses.isEmpty()) {
                "快照数据无法解析"
            } else {
                analyses.joinToString("\n\n") { renderAnalysis(it) }
            }
        }
    }

    private fun renderAnalysis(a: StoreAnalysis): String = buildString {
        append("${CaptureHub.platformLabel(a.platform)} · ${a.storeName}（快照 ${a.snapshotCount} 条）")
        a.ratingTrend?.let { (from, to) -> append("\n评分 $from → $to") }
        a.salesTrend?.let { (from, to) -> append("\n月售 $from → $to") }
        append("\n最新菜单 ${a.itemCountLatest} 件")
        if (a.priceChanges.isEmpty()) {
            append("\n暂无变价记录")
        } else {
            append("\n最近变价：")
            a.priceChanges.takeLast(5).reversed().forEach { c ->
                append("\n· ${c.itemName} ¥${fmt(c.oldPrice)} → ¥${fmt(c.newPrice)}（${timeFormat.format(Date(c.changedAt))}）")
            }
        }
    }

    /** 整数去掉小数部分展示（20.0 → "20"）。 */
    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
