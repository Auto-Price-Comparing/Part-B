package com.team.pricecompare.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import com.team.pricecompare.Deal
import com.team.pricecompare.Morandi
import com.team.pricecompare.R
import com.team.pricecompare.engine.CaptureHub
import com.team.pricecompare.engine.CapturePipeline
import com.team.pricecompare.engine.OverlayState
import com.team.pricecompare.engine.data.MatchMemory
import com.team.pricecompare.engine.match.ItemMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ================= OVERLAY 常量区（文案/展示名改动只改这里；配色见 Morandi） =================
private object OverlayStyle {
    /** 无数据时的占位文案（兜底，M0 沿用）。 */
    const val PLACEHOLDER_TEXT = "比价助手运行中"

    /** 拿到 Deal 但店名缺失时的标题兜底。 */
    const val DEFAULT_TITLE = "比价助手"

    /** 平台名在卡片上的展示名。 */
    val PLATFORM_NAMES = mapOf("meituan" to "美团", "flash" to "淘宝闪购")
}
// ============================================================================

/**
 * 悬浮窗比价卡片：展示同一店铺跨平台的实付价对比。
 *
 * 数据来源：直接订阅 [CaptureHub.state]（M5 起；原 companion 变量 + ACTION_REFRESH
 * 协议无人接线，已删除）。四种 [OverlayState] 各自渲染，任何状态下都不崩溃；
 * 可拖动、可折叠（C 侧交互移植）；关闭按钮 stopSelf()。
 *
 * C 侧整合起转为前台服务（specialUse 类型 + 低重要性通知），配合
 * [OverlayController] 按无障碍开关自动启停，提升保活能力。
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private var cardView: View? = null

    /** 折叠状态：折叠时只保留标题行（比价/待确认内容隐藏）。 */
    private var collapsed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        showCard()
        return START_STICKY
    }

    /** Android 14 合规：specialUse 类型前台服务，低重要性通知不打扰用户。 */
    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "比价悬浮窗", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentText("比价悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showCard() {
        if (cardView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_card, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 240
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        // 折叠/关闭按钮消费点击事件，不会触发整卡拖动
        val collapseBtn = view.findViewById<TextView>(R.id.overlay_collapse)
        collapseBtn.setOnClickListener {
            collapsed = !collapsed
            collapseBtn.text = if (collapsed) "＋" else "—"
            applyCollapsed()
        }
        view.findViewById<View>(R.id.overlay_close).setOnClickListener { stopSelf() }

        wm.addView(view, params)
        cardView = view

        // 订阅引擎状态总线：采集/比价结果实时刷到卡片上
        scope.launch {
            CaptureHub.state.collect { state -> render(state) }
        }
    }

    /** 折叠时隐藏内容区，只留标题行。 */
    private fun applyCollapsed() {
        val view = cardView ?: return
        val visibility = if (collapsed) View.GONE else View.VISIBLE
        view.findViewById<LinearLayout>(R.id.overlay_deals_container).visibility = visibility
        val placeholder = view.findViewById<TextView>(R.id.overlay_placeholder)
        if (collapsed || placeholder.text.isEmpty()) placeholder.visibility = View.GONE
    }

    /** 按状态渲染卡片；任何状态下都不抛异常（铁律：优雅降级）。 */
    private fun render(state: OverlayState) {
        val view = cardView ?: return
        val storeNameView = view.findViewById<TextView>(R.id.overlay_store_name)
        val placeholder = view.findViewById<TextView>(R.id.overlay_placeholder)
        val container = view.findViewById<LinearLayout>(R.id.overlay_deals_container)
        container.removeAllViews()

        val updatedAt = CaptureHub.lastUpdatedAt
        val timeSuffix =
            if (updatedAt > 0L) " · 更新 ${timeFormat.format(Date(updatedAt))}" else ""

        when (state) {
            is OverlayState.Waiting -> {
                storeNameView.text = OverlayStyle.DEFAULT_TITLE
                placeholder.text = OverlayStyle.PLACEHOLDER_TEXT
                placeholder.visibility = if (collapsed) View.GONE else View.VISIBLE
            }
            is OverlayState.Unsupported -> {
                storeNameView.text = OverlayStyle.DEFAULT_TITLE + timeSuffix
                placeholder.text = state.message
                placeholder.visibility = if (collapsed) View.GONE else View.VISIBLE
            }
            is OverlayState.SinglePlatform -> {
                storeNameView.text = state.store.storeName.ifBlank { OverlayStyle.DEFAULT_TITLE } + timeSuffix
                placeholder.visibility = View.GONE
                container.addView(buildDealRow(state.deal, isBest = true))
                container.addView(buildHintRow(state.hint))
            }
            is OverlayState.Comparing -> {
                storeNameView.text = state.storeName.ifBlank { OverlayStyle.DEFAULT_TITLE } + timeSuffix
                placeholder.visibility = View.GONE
                val bestPrice = state.deals.minOf { it.finalPrice }
                state.deals.forEach { deal ->
                    container.addView(buildDealRow(deal, isBest = deal.finalPrice == bestPrice))
                }
                container.addView(buildHintRow("已匹配 ${state.matchedItemCount} 件同品"))
                // 疑似同品：逐条提供人工确认入口（确认后永久按同品计价）
                state.pending.forEach { match ->
                    container.addView(buildPendingRow(match))
                }
            }
        }
        applyCollapsed()
    }

    /** 构造一行比价结果：上排「平台名 + 实付价」，下排明细摘要（折叠为单行）。 */
    private fun buildDealRow(deal: Deal, isBest: Boolean): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            if (isBest) background = Morandi.card(this@OverlayService, Morandi.overlayBest, 8f)
        }

        val topLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val platformView = TextView(this).apply {
            text = OverlayStyle.PLATFORM_NAMES[deal.platform] ?: deal.platform
            setTextColor(if (isBest) Morandi.overlayOnAccent else Morandi.overlayText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val priceView = TextView(this).apply {
            text = "¥%.2f".format(deal.finalPrice)
            setTextColor(if (isBest) Morandi.overlayOnAccent else Morandi.overlayPrice)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        topLine.addView(
            platformView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        topLine.addView(priceView)

        val summaryView = TextView(this).apply {
            text = deal.breakdown.joinToString("，")
            setTextColor(if (isBest) Morandi.overlayOnAccent else Morandi.overlaySub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        row.addView(topLine)
        row.addView(summaryView)
        return row
    }

    /** 构造一行待确认配对：「A ⇋ B（相似度）」+ 确认按钮；确认落库后触发重算刷新。 */
    private fun buildPendingRow(match: ItemMatch): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val label = TextView(this).apply {
            text = "%s ⇋ %s（%.0f%%）".format(match.a.name, match.b?.name ?: "?", match.similarity * 100)
            setTextColor(Morandi.overlayWarn)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val confirmBtn = Button(this).apply {
            text = "确认同品"
            textSize = 11f
        }
        confirmBtn.setOnClickListener {
            val bName = match.b?.name ?: return@setOnClickListener
            confirmBtn.isEnabled = false
            confirmBtn.text = "已确认"
            scope.launch {
                runCatching { MatchMemory(applicationContext).confirm(match.a.name, bName) }
                CapturePipeline.rematchWithConfirmed(applicationContext)
            }
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(confirmBtn)
        return row
    }

    /** 构造一行灰色小字提示（单平台引导语 / 已匹配同品数）。 */
    private fun buildHintRow(hint: String): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return TextView(this).apply {
            text = hint
            setTextColor(Morandi.overlaySub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(6), dp(2), dp(6), dp(4))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        cardView?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        cardView = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "overlay_fgs"
        private const val NOTIFICATION_ID = 1
    }
}
