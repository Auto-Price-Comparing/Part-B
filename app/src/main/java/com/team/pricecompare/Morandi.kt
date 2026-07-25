package com.team.pricecompare

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/**
 * 莫兰迪色板（移植自 C 侧）：主屏与悬浮窗两套色值分离，
 * 色彩语义化命名，改动配色只动这里。
 */
object Morandi {

    val screenBg = Color.parseColor("#EDE7DE")
    val surface = Color.parseColor("#F6F1E8")
    val textMain = Color.parseColor("#4E4A52")
    val textSub = Color.parseColor("#8B8590")
    val bestRow = Color.parseColor("#E3E8DD")
    val bestText = Color.parseColor("#6E8B5E")
    val priceText = Color.parseColor("#9C7A5A")
    val warnText = Color.parseColor("#B0706A")
    val divider = Color.parseColor("#D7CFC4")

    val overlayBg = Color.parseColor("#EE3D3942")
    val overlayText = Color.parseColor("#ECE6DC")
    val overlaySub = Color.parseColor("#B0AEA8")
    val overlayBest = Color.parseColor("#A9BFA0")
    val overlayPrice = Color.parseColor("#D8BC9A")
    val overlayWarn = Color.parseColor("#D4928B")
    val overlayDivider = Color.parseColor("#5A5560")
    val overlayStroke = Color.parseColor("#22FFFFFF")
    val overlayOnAccent = Color.parseColor("#3A3740")

    /** 圆角卡片背景生成；[stroke] 非空时带描边。 */
    fun card(
        context: Context,
        color: Int,
        radiusDp: Float,
        stroke: Int? = null,
        strokeWidthDp: Float = 0f,
    ): GradientDrawable {
        val dm = context.resources.displayMetrics
        val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, dm)
        val sw = if (strokeWidthDp > 0f) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, strokeWidthDp, dm).toInt()
        } else {
            0
        }
        return GradientDrawable().apply {
            cornerRadius = r
            setColor(color)
            if (stroke != null && sw > 0) setStroke(sw, stroke)
        }
    }
}
