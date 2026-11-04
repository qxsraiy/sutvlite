package top.yogiczy.sutvlite.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.sutvlite.data.Prefs

/**
 * 设置首页 —— 现代化竖向卡片列表
 *
 * 配色: 黑(#0E1116) 底 + 深灰(#161A21) 卡片 + 白字 + 蓝(#2F7BFF) 强调
 * 焦点: 卡片填充蓝色, 文字纯白, 轻微放大
 */
class LiteSettingsActivity : Activity() {

    private val C_BG = 0xFF0E1116.toInt()      // 黑
    private val C_SURFACE = 0xFF161A21.toInt() // 深灰
    private val C_LINE = 0xFF232833.toInt()    // 分隔线/描边灰
    private val C_BLUE = 0xFF2F7BFF.toInt()    // 蓝
    private val C_WHITE = 0xFFFFFFFF.toInt()
    private val C_TEXT2 = 0xFF9AA7BD.toInt()   // 次要文字灰

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
            clipChildren = false
            clipToPadding = false
            setPadding(dp(90), dp(40), dp(90), dp(36))
        }

        // 标题区
        root.addView(TextView(this).apply {
            text = "设置"
            textSize = 30f
            setTextColor(C_WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "方向键选择，OK 进入，返回键退出"
            textSize = 13f
            setTextColor(C_TEXT2)
            setPadding(0, dp(8), 0, dp(24))
        })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bg = runCatching { Prefs.blockedGroups().size }.getOrDefault(0)
        val bc = runCatching { Prefs.blockedChannels().size }.getOrDefault(0)
        list.addView(
            makeCard(
                "分组和频道管理",
                "显示 / 隐藏 分组与频道 · 已屏蔽 分组 $bg 个 / 频道 $bc 个",
                "直播",
            ) { startActivity(Intent(this, GroupChannelActivity::class.java)) }
        )
        list.addView(
            makeCard(
                "特权功能",
                "Root 授权与诊断、安装为系统应用、锁定为桌面",
                "特权",
            ) { startActivity(Intent(this, PrivilegeActivity::class.java)) }
        )
        list.addView(
            makeCard(
                "关于",
                "极简直播 ${packageManager.getPackageInfo(packageName, 0).versionName} · 仅直播 / 仅咪咕源",
                "信息",
            ) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("极简直播")
                    .setMessage(
                        "版本: ${packageManager.getPackageInfo(packageName, 0).versionName}\n" +
                            "设备: ${android.os.Build.MODEL}  Android ${android.os.Build.VERSION.RELEASE}\n\n" +
                            "遥控: ↑/↓ 换台 · OK 选台面板\n" +
                            "长按 OK / 连按 5 次菜单键 / 长按屏幕 = 设置"
                    )
                    .setPositiveButton("关闭", null)
                    .show()
            }
        )



        setContentView(root)
    }

    private fun makeCard(
        title: String,
        subtitle: String,
        tag: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(C_SURFACE)
                setStroke(dp(1), C_LINE)
            }
        }

        // 左侧标签块
        val tagTv = TextView(this).apply {
            text = tag
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(C_BLUE)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x1A2F7BFF)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        card.addView(tagTv)

        // 中间: 标题 + 副标题
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 21f
            setTextColor(C_WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subTv = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(C_TEXT2)
            setPadding(0, dp(5), 0, 0)
            maxLines = 1
        }
        col.addView(titleTv)
        col.addView(subTv)
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 右侧箭头
        val arrow = TextView(this).apply {
            text = "›"
            textSize = 26f
            setTextColor(C_TEXT2)
        }
        card.addView(arrow)

        card.setOnFocusChangeListener { _, focused ->
            card.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (focused) C_BLUE else C_SURFACE)
                setStroke(dp(1), if (focused) C_BLUE else C_LINE)
            }
            titleTv.setTextColor(C_WHITE)
            subTv.setTextColor(if (focused) 0xCCFFFFFF.toInt() else C_TEXT2)
            tagTv.setTextColor(if (focused) C_WHITE else C_BLUE)
            tagTv.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (focused) 0x33FFFFFF else 0x1A2F7BFF)
            }
            arrow.setTextColor(if (focused) C_WHITE else C_TEXT2)
            card.scaleX = if (focused) 1.01f else 1f
            card.scaleY = if (focused) 1.01f else 1f
        }
        card.setOnClickListener { onClick() }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(12))
        card.layoutParams = lp
        return card
    }
}