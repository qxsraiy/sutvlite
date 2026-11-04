package top.yogiczy.sutvlite.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 圆环进度条 (API19 可用, 不依赖 androidx)
 *
 *  - 灰色轨道 + 蓝色进度弧
 *  - 出错时调用 setError(true) -> 弧变红
 *  - setProgress(0f..1f) 平滑推进
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2A2F3A.toInt()
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2F7BFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    /** 0f..1f */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var isError: Boolean = false
        set(value) {
            field = value
            barPaint.color = if (value) 0xFFE5484D.toInt() else 0xFF2F7BFF.toInt()
            trackPaint.color = if (value) 0xFF3A2226.toInt() else 0xFF2A2F3A.toInt()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = (kotlin.math.min(w, h) * 0.09f).coerceAtLeast(6f)
        trackPaint.strokeWidth = stroke
        barPaint.strokeWidth = stroke
        val inset = stroke / 2f + 2f
        rect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 轨道整圈
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        // 进度弧: 从 12 点方向顺时针
        if (progress > 0f) {
            canvas.drawArc(rect, -90f, 360f * progress, false, barPaint)
        }
    }

    companion object {
        @Suppress("unused")
        val DEFAULT_COLOR = Color.parseColor("#2F7BFF")
    }
}