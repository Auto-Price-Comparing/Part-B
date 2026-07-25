package com.team.pricecompare.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import com.team.pricecompare.Morandi

/**
 * 历史价格折线图（移植自 C 侧，纯 Canvas 零依赖）。
 * 数据源为「时间戳 → 价格」序列；不足 2 个点时显示「暂无历史」。
 * 与 C 侧原版的差异：价格标签在 setPoints 时算好，onDraw 里不再做字符串格式化分配。
 */
class ChartView(context: Context) : View(context) {

    private var points: List<Pair<Long, Double>> = emptyList()
    private var maxLabel = ""
    private var minLabel = ""

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Morandi.divider
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Morandi.bestText
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x336E8B5E
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Morandi.textSub
        textSize = dp(11f)
    }

    /** [pts] 为 (采集时间戳, 价格) 序列，内部按时间升序排序。 */
    fun setPoints(pts: List<Pair<Long, Double>>) {
        points = pts.sortedBy { it.first }
        if (points.size >= 2) {
            maxLabel = "¥%.2f".format(points.maxOf { it.second })
            minLabel = "¥%.2f".format(points.minOf { it.second })
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(8f)
        canvas.drawRoundRect(pad, pad, w - pad, h - pad, dp(10f), dp(10f), axisPaint)
        if (points.size < 2) {
            canvas.drawText("暂无历史", pad + dp(6f), h / 2f, labelPaint)
            return
        }

        val prices = points.map { it.second }
        val minP = prices.min()
        val maxP = prices.max()
        val span = (maxP - minP).coerceAtLeast(0.01)
        val left = pad * 2
        val right = w - pad
        val top = pad * 2
        val bottom = h - pad * 2

        fun xAt(i: Int) = left + (right - left) * i / (points.size - 1)
        fun yAt(p: Double) = (bottom - (bottom - top) * ((p - minP) / span)).toFloat()

        val fillPath = Path()
        fillPath.moveTo(xAt(0), bottom)
        for (i in points.indices) fillPath.lineTo(xAt(i), yAt(points[i].second))
        fillPath.lineTo(xAt(points.size - 1), bottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        val linePath = Path()
        linePath.moveTo(xAt(0), yAt(points[0].second))
        for (i in points.indices) linePath.lineTo(xAt(i), yAt(points[i].second))
        canvas.drawPath(linePath, linePaint)

        canvas.drawText(maxLabel, left, top, labelPaint)
        canvas.drawText(minLabel, left, bottom, labelPaint)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
