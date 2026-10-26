package top.yogiczy.sutvlite.ui.components

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * TV 统一 UI 组件: 圆角卡片 + D-pad 焦点高亮 (边框+缩放)
 * 遥控器体验核心, 所有列表/按钮复用
 */
object TvUi {

    /** 焦点色板 */
    const val BG = 0xFF101012.toInt()
    const val CARD = 0xFF1E1E22.toInt()
    const val CARD_FOCUS = 0xFF3A4A8C.toInt()
    const val BORDER = 0xFF88A0FF.toInt()
    const val TEXT = 0xFFEEEEEE.toInt()
    const val TEXT_DIM = 0xFF8888AA.toInt()
    const val ACCENT = 0xFFFFD700.toInt()

    /**
     * 构建一个可聚焦卡片 (支持圆角/边框/缩放高亮)
     * @param onClick 确认键触发
     */
    fun focusableCard(
        context: Context,
        content: View,
        isSquare: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = if (isSquare) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(CARD)
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            isFocusable = onClick != null
            isClickable = onClick != null
            if (onClick != null) setOnClickListener { onClick() }
            setOnFocusChangeListener { _, has ->
                if (has) {
                    setBackgroundColor(CARD_FOCUS)
                    scaleX = 1.06f
                    scaleY = 1.06f
                } else {
                    setBackgroundColor(CARD)
                    scaleX = 1f
                    scaleY = 1f
                }
            }
        }
        card.addView(content)
        return card
    }

    /** 文本行 (列表项) */
    fun textRow(
        context: Context,
        text: String,
        onClick: (() -> Unit)? = null,
        selected: Boolean = false,
    ): TextView {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 17f
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10), 0, dp(context, 10))
            isFocusable = onClick != null
            isClickable = onClick != null
            if (onClick != null) setOnClickListener { onClick() }
            setOnFocusChangeListener { _, has ->
                setBackgroundColor(if (has) CARD_FOCUS else if (selected) 0xFF2A2A50.toInt() else Color.TRANSPARENT)
            }
        }
        if (selected) tv.setBackgroundColor(0xFF2A2A50.toInt())
        return tv
    }

    /** 顶部栏按钮 */
    fun topButton(context: Context, text: String, onClick: () -> Unit): TextView {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF2A2A30.toInt())
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, has ->
                setBackgroundColor(if (has) CARD_FOCUS else 0xFF2A2A30.toInt())
            }
        }
        return tv
    }

    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 22f
        setTextColor(TEXT)
        setTypeface(null, android.graphics.Typeface.BOLD)
    }

    fun dim(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(TEXT_DIM)
    }

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    /** 是否显示图片 (海报/台标)。盒子性能差, 默认关闭, 纯文字更快更稳 */
    fun showImages(): Boolean = try {
        top.yogiczy.mytv.core.data.utils.SP.getBoolean("ui_show_images", true)
    } catch (_: Throwable) {
        false
    }

    fun setShowImages(v: Boolean) {
        try {
            top.yogiczy.mytv.core.data.utils.SP.putBoolean("ui_show_images", v)
        } catch (_: Throwable) {
        }
    }
}