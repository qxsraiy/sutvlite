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
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.sutvlite.root.RootActions
import top.yogiczy.sutvlite.root.RootManager

/**
 * 特权功能: Root 授权 / 诊断 / 系统应用 / 锁定桌面 / 进程守护 / 开机自启
 */
class PrivilegeActivity : Activity() {

    private val C_BG = 0xFF0B1220.toInt()
    private val C_BLUE = 0xFF2F7BFF.toInt()
    private val C_BLUE_LIGHT = 0xFFA8C7FA.toInt()
    private val C_WHITE = 0xFFFFFFFF.toInt()

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var page: LinearLayout
    private lateinit var rootTv: TextView

    private var cachedSystem = false
    private var cachedOom = false
    private var cachedLauncher = false

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
            text = "特权功能"
            textSize = 24f
            setTextColor(C_WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        page.addView(TextView(this).apply {
            text = "需要盒子已 root（KingRoot / Magisk 等）"
            textSize = 12f
            setTextColor(C_BLUE_LIGHT)
            setPadding(0, dp(6), 0, dp(10))
        })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        setContentView(scroll)
        build()
        refresh()
    }

    private fun build() {
        page.addView(sectionTitle("Root"))

        rootTv = TextView(this).apply {
            text = "Root: 检测中…"
            textSize = 15f
            setTextColor(C_BLUE_LIGHT)
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x14FFFFFF)
            }
            setOnFocusChangeListener { v, f ->
                v.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (f) C_BLUE else 0x14FFFFFF)
                    setStroke(dp(2), if (f) C_WHITE else 0x00000000)
                }
            }
            setOnClickListener { requestRoot() }
        }
        page.addView(rootTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(6))
        })

        page.addView(navRow("Root 诊断", "su 路径 / id / 挂载 / 系统副本 / 默认桌面") {
            scope.launch {
                val report = withContext(Dispatchers.IO) {
                    runCatching { RootActions.diagnose() }.getOrElse { "诊断失败: ${it.message}" }
                }
                AlertDialog.Builder(this@PrivilegeActivity)
                    .setTitle("Root 诊断")
                    .setMessage(report)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        })

        page.addView(sectionTitle("系统权限"))

        page.addView(toggleRow("安装为系统应用", { if (cachedSystem) "开" else "关" }) {
            val on = !cachedSystem
            val r = if (on) RootActions.installAsSystemApp() else RootActions.removeFromSystem()
            cachedSystem = on
            if (on) "开 · $r" else "关 · $r"
        })

        page.addView(toggleRow("锁定为桌面", { if (cachedLauncher) "开" else "关" }) {
            val on = !cachedLauncher
            val r = if (on) RootActions.setAsDefaultLauncher() else RootActions.restoreDefaultLauncher()
            cachedLauncher = on
            if (on) "开 · $r" else "关 · $r"
        })

        page.addView(toggleRow("进程守护", { if (cachedOom) "开" else "关" }) {
            val on = !cachedOom
            val r = RootActions.setOomPriority(on)
            cachedOom = on
            if (on) "开 · $r" else "关 · $r"
        })

        page.addView(sectionTitle("启动"))
        page.addView(toggleRow("开机自启", { if (SP.getBoolean("boot_launch", false)) "开" else "关" }) {
            val on = !SP.getBoolean("boot_launch", false)
            SP.putBoolean("boot_launch", on)
            if (on) "开" else "关"
        })
    }

    private fun requestRoot() {
        rootTv.text = "Root: 请求授权中…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) { RootManager.requestRoot() }
            rootTv.text = if (ok) "Root: 已授权 ✅" else "Root: 无权限（点按重试）"
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val granted = withContext(Dispatchers.IO) { RootManager.isGranted }
            rootTv.text = when {
                granted -> "Root: 已授权 ✅"
                RootManager.isRooted() -> "Root: 已检测到 su，点按获取授权"
                else -> "Root: 未检测到 root"
            }
            if (granted) {
                val (s, o, l) = withContext(Dispatchers.IO) {
                    Triple(
                        runCatching { RootActions.isSystemApp() }.getOrDefault(false),
                        runCatching { RootActions.checkOomPriority() }.getOrDefault(false),
                        runCatching { RootActions.isDefaultLauncher() }.getOrDefault(false),
                    )
                }
                cachedSystem = s; cachedOom = o; cachedLauncher = l
                rebuildValues()
            }
        }
    }

    private val valueViews = mutableListOf<Pair<TextView, String>>()

    private fun rebuildValues() {
        // 值行在点击时已自行刷新, 这里同步一次初始状态
        valueViews.forEach { (tv, kind) ->
            tv.text = when (kind) {
                "sys" -> if (cachedSystem) "开" else "关"
                "oom" -> if (cachedOom) "开" else "关"
                "lau" -> if (cachedLauncher) "开" else "关"
                else -> tv.text
            }
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(C_BLUE_LIGHT)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun toggleRow(title: String, value: () -> String, onToggle: () -> String): LinearLayout {
        val c = container()
        val t = TextView(this).apply { text = title; textSize = 15f; setTextColor(C_WHITE) }
        c.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val v = TextView(this).apply {
            text = value()
            textSize = 15f
            setTextColor(C_BLUE_LIGHT)
        }
        c.addView(v)
        valueViews.add(v to when (title) {
            "安装为系统应用" -> "sys"
            "进程守护" -> "oom"
            "锁定为桌面" -> "lau"
            else -> ""
        })
        bindFocus(c, t, v, null)
        c.setOnClickListener { v.text = onToggle() }
        return c
    }

    private fun navRow(title: String, hint: String, onClick: () -> Unit): LinearLayout {
        val c = container()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply { text = title; textSize = 15f; setTextColor(C_WHITE) }
        val h = TextView(this).apply { text = hint; textSize = 11f; setTextColor(C_BLUE_LIGHT) }
        col.addView(t)
        col.addView(h)
        c.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val arrow = TextView(this).apply {
            text = "›"; textSize = 20f; setTextColor(C_BLUE_LIGHT)
        }
        c.addView(arrow)
        bindFocus(c, t, h, arrow)
        c.setOnClickListener { onClick() }
        return c
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
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(6))
        }
    }

    private fun bindFocus(c: LinearLayout, t: TextView, v: TextView, a: TextView?) {
        c.setOnFocusChangeListener { _, f ->
            c.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (f) C_BLUE else 0x14FFFFFF)
                setStroke(dp(2), if (f) C_WHITE else 0x00000000)
            }
            t.setTextColor(C_WHITE)
            v.setTextColor(if (f) C_WHITE else C_BLUE_LIGHT)
            a?.setTextColor(if (f) C_WHITE else C_BLUE_LIGHT)
        }
    }
}