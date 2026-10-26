package top.yogiczy.sutvlite.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.sutvlite.data.Prefs

/**
 * 设置首页: 只有两项
 *   分组和频道管理 / 特权功能
 * 配色: 蓝色为主, 白色为辅
 */
class LiteSettingsActivity : Activity() {

    private val C_BG = 0xFF0B1220.toInt()
    private val C_BLUE = 0xFF2F7BFF.toInt()
    private val C_BLUE_LIGHT = 0xFFA8C7FA.toInt()
    private val C_BLUE_DIM = 0xFF1B2C4A.toInt()
    private val C_WHITE = 0xFFFFFFFF.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(dp(48), dp(28), dp(48), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "设置"
            textSize = 26f
            setTextColor(C_WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val cards = listOf(
            makeCard("分组和频道管理", "显示 / 隐藏 分组与频道") {
                startActivity(Intent(this, GroupChannelActivity::class.java))
            },
            makeCard("特权功能", "Root · 系统应用 · 锁定桌面 · 进程守护") {
                startActivity(Intent(this, PrivilegeActivity::class.java))
            },
        )
        cards.forEachIndexed { i, card ->
            row.addView(card, LinearLayout.LayoutParams(0, dp(280), 1f).apply {
                if (i == 0) setMargins(0, 0, dp(28), 0)
            })
        }
        cards.forEachIndexed { i, c ->
            c.nextFocusLeftId = cards[(i - 1 + cards.size) % cards.size].id
            c.nextFocusRightId = cards[(i + 1) % cards.size].id
        }

        val info = TextView(this).apply {
            textSize = 12f
            setTextColor(C_BLUE_LIGHT)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            text = "屏蔽分组 ${runCatching { Prefs.blockedGroups().size }.getOrDefault(0)} 个   " +
                "屏蔽频道 ${runCatching { Prefs.blockedChannels().size }.getOrDefault(0)} 个"
        }
        root.addView(info)

        setContentView(root)
        cards.first().post { cards.first().requestFocus() }
    }

    private fun makeCard(title: String, hint: String, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val t = TextView(this).apply {
            text = title
            textSize = 21f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val h = TextView(this).apply {
            text = hint
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(t)
        card.addView(h)

        fun paint(f: Boolean) {
            card.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (f) C_BLUE else C_BLUE_DIM)
                setStroke(dp(3), if (f) C_WHITE else C_BLUE)
            }
            t.setTextColor(C_WHITE)
            h.setTextColor(if (f) C_WHITE else C_BLUE_LIGHT)
            card.scaleX = if (f) 1.05f else 1f
            card.scaleY = if (f) 1.05f else 1f
        }
        paint(false)
        card.setOnFocusChangeListener { _, f -> paint(f) }
        card.setOnClickListener { onClick() }
        return card
    }
}