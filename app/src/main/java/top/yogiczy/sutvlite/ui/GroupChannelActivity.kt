package top.yogiczy.sutvlite.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yogiczy.sutvlite.data.Prefs
import top.yogiczy.sutvlite.migu.MiguBackendManager
import top.yogiczy.sutvlite.migu.MiguCategory

/**
 * 分组和频道管理: 显示/隐藏分组与频道
 */
class GroupChannelActivity : Activity() {

    private val C_BG = 0xFF0B1220.toInt()
    private val C_BLUE = 0xFF2F7BFF.toInt()
    private val C_BLUE_LIGHT = 0xFFA8C7FA.toInt()
    private val C_BLUE_DIM = 0xFF1B2C4A.toInt()
    private val C_WHITE = 0xFFFFFFFF.toInt()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var categories: List<MiguCategory> = emptyList()
    private var manageIdx = 0

    private lateinit var page: LinearLayout
    private lateinit var channelSection: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(dp(48), dp(24), dp(48), dp(24))
        }
        page.addView(TextView(this).apply {
            text = "分组和频道管理"
            textSize = 24f
            setTextColor(C_WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        page.addView(TextView(this).apply {
            text = "点一下切换 显示 / 隐藏（隐藏后不会出现在选台面板）"
            textSize = 12f
            setTextColor(C_BLUE_LIGHT)
            setPadding(0, dp(6), 0, dp(10))
        })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        setContentView(scroll)
        load()
    }

    private fun load() {
        page.addView(TextView(this).apply {
            text = "加载频道列表…"
            setTextColor(C_BLUE_LIGHT)
            textSize = 14f
        })
        scope.launch {
            categories = withContext(Dispatchers.IO) {
                runCatching { MiguBackendManager.getInstance(this@GroupChannelActivity).loadAllChannels() }
                    .getOrElse { emptyList() }
            }
            while (page.childCount > 2) page.removeViewAt(2)
            buildUi()
        }
    }

    private fun buildUi() {
        page.addView(sectionTitle("分组"))
        if (categories.isEmpty()) {
            page.addView(TextView(this).apply {
                text = "没有获取到频道（检查网络后重进）"
                setTextColor(C_BLUE_LIGHT); textSize = 13f
            })
            return
        }
        categories.forEach { cat ->
            addRow(page, row(
                title = cat.name,
                value = { if (cat.name in Prefs.blockedGroups()) "隐藏" else "显示" },
                onToggle = {
                    val blocked = Prefs.toggleGroup(cat.name)
                    if (blocked) "隐藏" else "显示"
                },
            ))
        }

        page.addView(sectionTitle("频道"))
        channelSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(channelSection)
        rebuildChannels()
    }

    private fun rebuildChannels() {
        channelSection.removeAllViews()
        val cat = categories.getOrNull(manageIdx) ?: return

        addRow(channelSection, navRow("当前分组: ${cat.name}", "点一下切换要管理的分组") {
            val names = categories.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("选择分组")
                .setItems(names) { _, which ->
                    manageIdx = which
                    rebuildChannels()
                }
                .show()
        })

        cat.dataList.forEach { ch ->
            addRow(channelSection, row(
                title = ch.name.ifBlank { ch.pid },
                value = { if (ch.pid in Prefs.blockedChannels()) "隐藏" else "显示" },
                onToggle = {
                    val blocked = Prefs.toggleChannel(ch.pid)
                    if (blocked) "隐藏" else "显示"
                },
            ))
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(C_BLUE_LIGHT)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    /** 开关行 */
    private fun row(title: String, value: () -> String, onToggle: () -> String): LinearLayout {
        val c = container()
        val (t, h) = texts(c, title, "")
        val v = TextView(this).apply {
            text = value()
            textSize = 15f
            setTextColor(C_BLUE_LIGHT)
        }
        c.addView(v)
        bindFocus(c, t, h, v, null)
        c.setOnClickListener { v.text = onToggle() }
        return c
    }

    /** 导航行 */
    private fun navRow(title: String, hint: String, onClick: () -> Unit): LinearLayout {
        val c = container()
        val (t, h) = texts(c, title, hint)
        val arrow = TextView(this).apply {
            text = "›"; textSize = 20f; setTextColor(C_BLUE_LIGHT)
            setPadding(dp(10), 0, 0, 0)
        }
        c.addView(arrow)
        bindFocus(c, t, h, null, arrow)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun addRow(container: LinearLayout, view: LinearLayout) {
        container.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(6))
        })
    }

    private fun container(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(dp(16), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x14FFFFFF)
        }
    }

    private fun texts(c: LinearLayout, title: String, hint: String): Pair<TextView, TextView> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply { text = title; textSize = 15f; setTextColor(C_WHITE) }
        val h = TextView(this).apply { text = hint; textSize = 11f; setTextColor(C_BLUE_LIGHT) }
        col.addView(t)
        if (hint.isNotEmpty()) col.addView(h)
        c.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return t to h
    }

    private fun bindFocus(c: LinearLayout, t: TextView, h: TextView, v: TextView?, a: TextView?) {
        c.setOnFocusChangeListener { _, f ->
            c.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (f) C_BLUE else 0x14FFFFFF)
                setStroke(dp(2), if (f) C_WHITE else 0x00000000)
            }
            t.setTextColor(C_WHITE)
            h.setTextColor(if (f) C_WHITE else C_BLUE_LIGHT)
            v?.setTextColor(if (f) C_WHITE else C_BLUE_LIGHT)
            a?.setTextColor(if (f) C_WHITE else C_BLUE_LIGHT)
        }
    }
}