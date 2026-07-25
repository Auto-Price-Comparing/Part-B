package com.team.pricecompare.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.team.pricecompare.Deal
import com.team.pricecompare.R

// ================= OVERLAY 常量区（样式/文案改动只改这里） =================
private object OverlayStyle {
    /** 无数据时的占位文案（兜底，M0 沿用）。 */
    const val PLACEHOLDER_TEXT = "比价助手运行中"

    /** 拿到 Deal 但店名缺失时的标题兜底。 */
    const val DEFAULT_TITLE = "比价助手"

    /** 平台名在卡片上的展示名。 */
    val PLATFORM_NAMES = mapOf("meituan" to "美团", "flash" to "淘宝闪购")

    /** 常规文字色 / 明细摘要文字色。 */
    const val TEXT_COLOR = "#FFFFFF"
    const val SUB_TEXT_COLOR = "#AAAAAA"

    /** 最低价行的实付价颜色与行背景色。 */
    const val BEST_PRICE_COLOR = "#FFCC00"
    const val BEST_ROW_BG_COLOR = "#33FFCC00"
}
// ============================================================================

/**
 * M1 悬浮窗比价卡片：展示同一店铺跨平台的实付价对比。
 *
 * 数据来源（C 侧消费方式，采集→引擎→悬浮窗的串联通路由主线程后续接线）：
 * 引擎算好 List<Deal> 后，先赋值 companion 变量 [latestStoreName] / [latestDeals]，
 * 再 `startService(Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH))`，
 * 服务收到 [ACTION_REFRESH] 后把最新数据重新 bind 到已存在的卡片视图上。
 *
 * 铁律遵守：数据为空时显示占位文案优雅降级，绝不崩溃；可拖动；关闭按钮 stopSelf()。
 */
class OverlayService : Service() {

    private var cardView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showCard()
        if (intent?.action == ACTION_REFRESH) {
            bindCard()
        }
        return START_STICKY
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

        // 关闭按钮消费点击事件，不会触发整卡拖动
        view.findViewById<View>(R.id.overlay_close).setOnClickListener { stopSelf() }

        wm.addView(view, params)
        cardView = view
        bindCard()
    }

    /** 把 companion 中的最新数据刷到卡片上；deals 为空显示占位文案。 */
    private fun bindCard() {
        val view = cardView ?: return
        val storeNameView = view.findViewById<TextView>(R.id.overlay_store_name)
        val placeholder = view.findViewById<TextView>(R.id.overlay_placeholder)
        val container = view.findViewById<LinearLayout>(R.id.overlay_deals_container)

        val deals = latestDeals.orEmpty()
        storeNameView.text = latestStoreName?.takeIf { it.isNotBlank() }
            ?: OverlayStyle.DEFAULT_TITLE
        container.removeAllViews()

        if (deals.isEmpty()) {
            placeholder.visibility = View.VISIBLE
            return
        }
        placeholder.visibility = View.GONE

        val bestPrice = deals.minOf { it.finalPrice }
        deals.forEach { deal ->
            container.addView(buildDealRow(deal, isBest = deal.finalPrice == bestPrice))
        }
    }

    /** 构造一行比价结果：上排「平台名 + 实付价」，下排明细摘要（折叠为单行）。 */
    private fun buildDealRow(deal: Deal, isBest: Boolean): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            if (isBest) setBackgroundColor(Color.parseColor(OverlayStyle.BEST_ROW_BG_COLOR))
        }

        val topLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val platformView = TextView(this).apply {
            text = OverlayStyle.PLATFORM_NAMES[deal.platform] ?: deal.platform
            setTextColor(Color.parseColor(OverlayStyle.TEXT_COLOR))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val priceView = TextView(this).apply {
            text = "¥%.2f".format(deal.finalPrice)
            setTextColor(
                Color.parseColor(
                    if (isBest) OverlayStyle.BEST_PRICE_COLOR else OverlayStyle.TEXT_COLOR,
                ),
            )
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
            setTextColor(Color.parseColor(OverlayStyle.SUB_TEXT_COLOR))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        row.addView(topLine)
        row.addView(summaryView)
        return row
    }

    override fun onDestroy() {
        cardView?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        cardView = null
        super.onDestroy()
    }

    companion object {
        /** 通知悬浮窗重新读取 companion 变量并刷新卡片。 */
        const val ACTION_REFRESH = "com.team.pricecompare.action.REFRESH_OVERLAY"

        /** 引擎写入：当前比价店铺名；null 时标题回落为默认文案。 */
        @Volatile
        var latestStoreName: String? = null

        /** 引擎写入：各平台实付价结果；null/空列表时显示占位文案。 */
        @Volatile
        var latestDeals: List<Deal>? = null
    }
}
