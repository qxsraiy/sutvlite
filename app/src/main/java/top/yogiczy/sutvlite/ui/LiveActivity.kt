package top.yogiczy.sutvlite.ui

import top.yogiczy.sutvlite.AppClock
import top.yogiczy.sutvlite.Logx
import top.yogiczy.sutvlite.SystemClockFixer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.sutvlite.backend.LiteServer
import top.yogiczy.sutvlite.data.Prefs
import top.yogiczy.sutvlite.migu.MiguApi
import top.yogiczy.sutvlite.migu.MiguBackendManager
import top.yogiczy.sutvlite.migu.MiguCategory
import top.yogiczy.sutvlite.migu.MiguChannel
import top.yogiczy.sutvlite.player.LegacyPlayerView

/**
 * 天光云影 Lite — 唯一主界面 = 直播
 *
 * 交互:
 *   ↑/← 上一台   ↓/→ 下一台   OK 选台面板   返回 退出
 *   设置入口: 长按 OK / 连续 5 次菜单键 / 手机长按屏幕
 * 换台只显示横幅 + 频道名
 */
open class LiveActivity : Activity() {

    // ---- 配色: 蓝色为主, 白色为辅 ----
    private val C_BG = 0xFF0B1220.toInt()
    private val C_BLUE = 0xFF2F7BFF.toInt()
    private val C_BLUE_LIGHT = 0xFFA8C7FA.toInt()
    private val C_BLUE_DIM = 0xFF1B2C4A.toInt()
    private val C_WHITE = 0xFFFFFFFF.toInt()

    /** 可统一取消的协程作用域: 页面销毁后所有挂起任务立即停止, 避免碰已销毁的 View */
    private val scopeJob = kotlinx.coroutines.SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + scopeJob)

    /** 页面是否还活着 (协程/回调里改 UI 前必须判断) */
    private fun alive(): Boolean = !isFinishing && !isDestroyed

    private var categories: List<MiguCategory> = emptyList()
    /** 未套用屏蔽的原始分组 (返回设置时重新过滤用) */
    private var rawCategories: List<MiguCategory> = emptyList()
    private var currentGroupIdx = 0
    private var currentChannelList: List<MiguChannel> = emptyList()
    private var currentChannelIdx = 0

    private var player: LegacyPlayerView? = null
    private var rootView: FrameLayout? = null
    private var bannerCard: LinearLayout? = null
    private var bannerName: TextView? = null
    private var hintTv: TextView? = null
    private var loadingTv: TextView? = null
    private var loadingSpinner: android.widget.ProgressBar? = null
    private var loadingBoxView: LinearLayout? = null
    /** 加载计时器: 每 500ms 更新"已用 N 秒", 让等待有变化 */
    private var loadingTickJob: Job? = null
    private var loadingStartAt = 0L
    private var loadingBaseText = ""
    /** 开机等待遮罩 (转圈 + 倒计时 + root 提示) */
    /** 换台时中央大圈圈 (无文字无倒计时) */
    private var bigSpinner: android.widget.ProgressBar? = null
    private var bootBox: LinearLayout? = null
    private var bootCountdown: TextView? = null
    private var bootRing: RingProgressView? = null
    private var bootIssues: TextView? = null
    private var bootTitle: TextView? = null
    private var bootTickJob: Job? = null
    /** 版权屏蔽蓝屏 */
    private var blueBox: LinearLayout? = null
    private var blueTitle: TextView? = null
    private var blueMsg: TextView? = null
    private var blueCountdown: TextView? = null
    private var blueJob: Job? = null

    private var overlay: FrameLayout? = null
    private var groupCol: LinearLayout? = null
    private var channelCol: LinearLayout? = null
    private var groupScroll: ScrollView? = null
    private var channelScroll: ScrollView? = null
    private var overlayVisible = false
    private var panelGroupIdx = 0
    /** 已构建过频道列的分组下标 (重复获焦时跳过重建) */
    private var builtGroupIdx = -1
    private var lastChannelFocusIdx = 0
    private var dismissJob: Job? = null

    private val groupRows = mutableListOf<TextView>()
    private val channelRows = mutableListOf<LinearLayout>()

    private var useProxy = true
    private var playedFirstFrame = false
    private var autoSwitchJob: Job? = null
    private var restartJob: Job? = null
    /** 本次打开是否已做过一次硬重启 (避免死循环) */
    private var hardRestartDone = false
    /** 连续失败次数: 超过上限就停止重试, 避免无限重试风暴把盒子拖死 */
    private var failStreak = 0
    /** 本次会话已自动跳过的坏频道数 (上限 5, 防止全挂时疯狂跳台) */
    private var autoSkipCount = 0
    private var bannerHideJob: Job? = null
    private var hintHideJob: Job? = null
    private var starting = false

    // 设置入口: 长按 OK / 5 次菜单
    private var okLongHandled = false
    private var menuPressTimes = mutableListOf<Long>()

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        instanceCount++
        Logx.mark("LiveActivity.onCreate 实例数=$instanceCount")
        buildUi()
        registerNetReceiver()
        loadAndPlay()
    }

    companion object {
        /** 频道缓存有效期: 6 小时 */
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        /** 联网探测用的地址 (拿到任何 HTTP 响应即算通) */
        private val PROBE_URLS = listOf("https://play.miguvideo.com/", "http://www.baidu.com/")
        /** 当前存活的 LiveActivity 实例数 (>1 说明有双播放器风险) */
        @Volatile
        var instanceCount = 0

        /** 预热结果状态 */
        const val PREFETCH_OK = 0
        const val PREFETCH_FAIL = 1
        const val PREFETCH_SHIELD = 2
    }

    override fun onResume() {
        super.onResume()
        // 从设置返回: 立即套用分组/频道屏蔽, 不用重启
        if (rawCategories.isNotEmpty()) applyBlockFilterNow()

        // 恢复播放: onStop 里暂停过, 返回时必须主动恢复, 否则画面卡住
        if (player != null && currentChannelList.isNotEmpty()) {
            player?.resume()
            scope.launch {
                delay(2000)
                if (!alive()) return@launch
                if (!playedFirstFrame || player?.isPlaying() != true) {
                    Logx.w("播放", "返回后未恢复播放, 硬重启一次")
                    playedFirstFrame = false
                    showBigSpinner()
                    player?.restart()
                    armAutoSwitch()
                } else {
                    Logx.i("播放", "返回后已恢复播放")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceCount = (instanceCount - 1).coerceAtLeast(0)
        Logx.mark("LiveActivity.onDestroy 剩余实例=$instanceCount")
        // 先停协程, 再拆 View/播放器, 避免"视图已销毁仍被回调访问"
        scopeJob.cancel()
        unregisterNetReceiver()
        autoSwitchJob?.cancel()
        dismissJob?.cancel()
        bannerHideJob?.cancel()
        player?.release()
        player = null
    }

    // ==================== UI ====================

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(C_BG)
            isClickable = true
            setOnLongClickListener {
                openSettings()
                true
            }
        }
        rootView = root

        val pv = LegacyPlayerView(this).apply { setControllerEnabled(false) }
        player = pv
        root.addView(pv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 加载提示: 转圈 + 文字 + 已用时 (让等待有变化, 不像是卡死)
        loadingTv = TextView(this).apply {
            textSize = 18f
            setTextColor(C_WHITE)
            gravity = Gravity.CENTER
        }
        loadingSpinner = android.widget.ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { setMargins(0, 0, dp(14), 0) }
        }
        val loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(16), dp(26), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE60E1A2E.toInt())
                setStroke(dp(2), C_BLUE)
            }
            addView(loadingSpinner)
            addView(loadingTv)
        }
        loadingBoxView = loadingBox
        root.addView(loadingBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        // 换台中央大圈圈: 只有一个圆环, 无边框无文字
        bigSpinner = android.widget.ProgressBar(this).apply {
            visibility = View.GONE
        }
        root.addView(bigSpinner, FrameLayout.LayoutParams(dp(88), dp(88)).apply {
            gravity = Gravity.CENTER
        })

        // 换台横幅: 只有频道名
        bannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xDD0E1A2E.toInt())
                setStroke(dp(2), C_BLUE)
            }
            visibility = View.GONE
        }
        bannerName = TextView(this).apply {
            textSize = 40f
            setTextColor(C_WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, dp(4), 0)
        }
        // 横幅: 左侧蓝色竖条 + 频道名 (换台只在屏幕中央转圈, 不放文字/倒计时)
        val bannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@LiveActivity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(C_BLUE)
                }
            }, LinearLayout.LayoutParams(dp(5), dp(54)).apply { setMargins(0, 0, dp(18), 0) })
            addView(bannerName)
        }
        bannerCard!!.addView(bannerRow)
        root.addView(bannerCard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(40), 0, 0, dp(40))
        })

        // 底部小提示 (出错时用)
        hintTv = TextView(this).apply {
            textSize = 13f
            setTextColor(C_BLUE_LIGHT)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            background = GradientDrawable().apply { setColor(0x990B1220.toInt()) }
            visibility = View.GONE
        }
        root.addView(hintTv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        buildPanel(root)
        buildBootBox(root)
        buildBlueScreen(root)
        setContentView(root)
    }

    /** 换台: 中央大圈圈 (无文字、无倒计时) */
    private fun showBigSpinner() {
        if (!alive()) return
        loadingTickJob?.cancel()
        loadingStartAt = 0L
        loadingBoxView?.visibility = View.GONE
        bigSpinner?.visibility = View.VISIBLE
    }

    private fun hideBigSpinner() {
        bigSpinner?.visibility = View.GONE
    }

    /** 开机等待遮罩: 不透明底 + 标题 + 预计提示 + 圆环进度 + 问题列表 */
    private fun buildBootBox(root: FrameLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0E1116.toInt())   // 不透明深灰黑
            visibility = View.GONE
            isClickable = true
        }
        bootTitle = TextView(this).apply {
            text = "设备重启后网络重置等待中"
            textSize = 26f
            setTextColor(C_WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        bootCountdown = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF9AA7BD.toInt())        // 灰色
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            text = "预计 120 秒后进入播放"
        }
        bootRing = RingProgressView(this).apply {
            progress = 0f
        }
        bootIssues = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE5484D.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(18), dp(40), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
        }
        box.addView(bootTitle)
        box.addView(bootCountdown)
        box.addView(bootRing, LinearLayout.LayoutParams(dp(132), dp(132)).apply {
            setMargins(0, dp(34), 0, 0)
        })
        box.addView(bootIssues)
        bootBox = box
        root.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** 显示开机等待 + 倒计时 */
    /** 显示开机等待: 圆环按 120 秒预算推进, 文字显示预计剩余 */
    private fun showBootWait(seconds: Int) {
        if (!alive()) return
        bootBox?.visibility = View.VISIBLE
        bootRing?.isError = false
        bootRing?.progress = 0f
        bootIssues?.visibility = View.GONE
        bootCountdown?.text = "预计 ${seconds} 秒后进入播放"
        var left = seconds
        bootTickJob?.cancel()
        bootTickJob = scope.launch {
            while (alive() && left > 0) {
                delay(1000)
                left--
                bootCountdown?.text = if (left > 0) "预计 ${left} 秒后进入播放" else "即将进入播放…"
                bootRing?.progress = ((seconds - left).toFloat() / seconds.toFloat()).coerceIn(0.02f, 0.98f)
            }
        }
    }

    /** 就绪: 圆环瞬间涨满然后隐去 */
    private fun finishBootWait(issues: List<String>? = null) {
        bootTickJob?.cancel()
        if (issues != null && issues.isNotEmpty()) {
            // 出了问题: 圆环变红 + 列出问题
            bootRing?.isError = true
            bootRing?.progress = 1f
            bootCountdown?.text = "未能正常就绪，请检查以下问题："
            bootIssues?.text = issues.joinToString("\n") { "• $it" }
            bootIssues?.visibility = View.VISIBLE
            Logx.w("启动", "开机等待异常: ${issues.joinToString(" / ")}")
            // 留 4 秒给用户看, 然后继续尝试播放
            scope.launch {
                delay(4000)
                if (alive()) bootBox?.visibility = View.GONE
            }
            return
        }
        bootRing?.isError = false
        bootRing?.progress = 1f
        bootCountdown?.text = "已就绪"
        scope.launch {
            delay(220)
            if (alive()) bootBox?.visibility = View.GONE
        }
    }

    private fun hideBootWait() {
        bootTickJob?.cancel()
        bootBox?.visibility = View.GONE
    }

    /** 版权屏蔽蓝屏: 提示换台, 10 秒后自动换到下一台 */
    private fun buildBlueScreen(root: FrameLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0B2A5B.toInt())
            visibility = View.GONE
            // 关键: 不抢焦点、不消费按键 -> 蓝屏时遥控仍可直接换台
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }
        blueTitle = TextView(this).apply {
            text = "受运营商版权限制该频道暂无法播放"
            textSize = 34f
            setTextColor(C_WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        blueMsg = TextView(this).apply {
            textSize = 16f
            setTextColor(C_BLUE_LIGHT)
            gravity = Gravity.CENTER
            setPadding(dp(80), dp(16), dp(80), 0)
        }
        blueCountdown = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF8FA3C0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        box.addView(blueTitle)
        box.addView(blueMsg)
        box.addView(blueCountdown)
        blueBox = box
        root.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** 显示蓝屏并 10 秒后自动换台 */
    private fun showShieldScreen(message: String) {
        if (!alive()) return
        hideLoading()
        hideBigSpinner()
        shieldStreak++
        blueMsg?.text = "换个频道看看，10 秒后会自动切换"
        blueBox?.visibility = View.VISIBLE
        isShieldShowing = true
        blueJob?.cancel()

        if (shieldStreak >= 3) {
            // 连续多个频道都没节目: 不再无限跳台, 提示用户手动换
            blueTitle?.text = "当前多个频道暂无节目"
            blueCountdown?.text = "按 ↑/↓ 可换台，或稍后再试"
            return
        }

        blueTitle?.text = "受运营商版权限制该频道暂无法播放"
        blueJob = scope.launch {
            var left = 10
            while (alive() && left > 0) {
                blueCountdown?.text = "${left} 秒后自动换到下一台（按 ↑/↓ 可立即换台）"
                delay(1000)
                left--
            }
            if (!alive() || !isShieldShowing) return@launch
            Logx.i("换台", "版权屏蔽自动跳台")
            hideShieldScreen()
            nextChannel()
        }
    }

    private fun hideShieldScreen() {
        blueJob?.cancel()
        blueBox?.visibility = View.GONE
        isShieldShowing = false
    }

    /** 蓝屏是否正在显示 (按键优先处理) */
    private var isShieldShowing = false

    /** 连续屏蔽计数: 连续多个频道都没节目时停止自动跳台 */
    private var shieldStreak = 0
    /** 三段式选台面板: 分组 | 竖线 | 频道 */
    private fun buildPanel(root: FrameLayout) {
        val ov = FrameLayout(this).apply {
            // 面板本身已不透明, 遮罩用深色即可
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
        }
        overlay = ov

        // 不透明灰色三栏面板 (靠左, 高度占屏幕 92%)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFF2A2A30.toInt())
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        groupCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        groupScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(groupCol)
        }
        body.addView(groupScroll, LinearLayout.LayoutParams(
            dp(240), LinearLayout.LayoutParams.MATCH_PARENT))

        body.addView(View(this).apply { setBackgroundColor(0x55FFFFFF) },
            LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(16), 0, dp(16), 0)
            })

        channelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(channelCol)
        }
        body.addView(channelScroll, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // body 撑满面板剩余高度
        card.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val screenH = resources.displayMetrics.heightPixels
        val vMargin = (screenH * 0.04f).toInt() // 上下各 4% → 面板占 92%

        ov.addView(card, FrameLayout.LayoutParams(dp(900), FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
            setMargins(dp(44), vMargin, 0, vMargin)
        })
        root.addView(ov, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- 复用的行背景 (避免每次焦点变化 new 一堆 GradientDrawable, 老盒子 GC 压力大) ----
    private val bgRowFocus by lazy {
        GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(0xFFFFFFFF.toInt()) }
    }
    private val bgRowSelected by lazy {
        GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(0x33FFFFFF) }
    }
    private val bgRowNone by lazy {
        GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(0x00000000) }
    }

    // ==================== 加载 + 播放 ====================

    /** 缓存优先启动: 有缓存就立刻开播, 网络刷新放后台 */
    private var loadJob: Job? = null

    private fun loadAndPlay() {
        if (starting || loadJob?.isActive == true) return
        starting = true
        showLoading("启动中…")
        loadJob = scope.launch {
            val manager = MiguBackendManager.getInstance(this@LiveActivity)

            // ---------- 0) 缓存优先 ----------
            val cached = withContext(Dispatchers.IO) { manager.loadCache() }
            if (cached.isNotEmpty()) {
                val filtered = Prefs.filter(cached)
                if (filtered.isNotEmpty()) {
                    rawCategories = cached
                    categories = filtered
                    applyLastChannel()
                    starting = false
                    Logx.i(
                        "启动",
                        "缓存启动: ${filtered.size} 组 ${filtered.sumOf { it.dataList.size }} 台, " +
                            "缓存年龄 ${manager.cacheAgeMs() / 1000}s"
                    )
                    switchChannel()
                    maybeRefreshCache(manager)
                    return@launch
                }
            }
            Logx.i("启动", "无频道缓存, 走网络加载")
            // 等链路就绪即可 (是否真能上网由后面的"拉频道"结果证明, 比探针可靠)
            var waited = 0
            while (!(isNetworkReady() && hasDefaultRoute())) {
                showLoading(networkProblemText())
                delay(2000)
                waited += 2000
                if (waited % 30_000 == 0) Logx.w("网络", "等待链路 ${waited / 1000}s")
            }
            if (waited > 0) Logx.i("网络", "链路就绪, 等待 ${waited}ms")
            showLoading("网络已连接，加载中…")
            // 校准应用时钟 (签名必需), 最多等 6 秒
            if (!AppClock.isCalibrated() || AppClock.isStale()) {
                showBigSpinner()
                val ok = AppClock.ensureCalibrated(6000)
                Logx.i("时钟", "启动校准结果=$ok 现在=${AppClock.nowText()}")
            }
            ensureLocalServer()

            Logx.i("启动", "开始加载频道列表")
            var attempt = 0
            while (attempt < 24) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                attempt++
                showLoading(if (attempt == 1) "加载频道…" else "加载频道…($attempt)")
                categories = withContext(Dispatchers.IO) {
                    runCatching {
                        val all = MiguBackendManager.getInstance(this@LiveActivity).loadAllChannelsAndCache()
                        rawCategories = all
                        Prefs.filter(all)
                    }.getOrElse { emptyList() }
                }
                Logx.i(
                    "启动",
                    "频道加载 第${attempt}次: ${categories.size} 组, 耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms"
                )
                if (categories.isNotEmpty()) break
                // 失败: 先确认还能上网, 再退避重试
                delay(5000)
                if (!probeInternet()) {
                    showLoading("网络中断，重新等待…")
                    kotlinx.coroutines.delay(3000)
                }
            }
            starting = false
            if (categories.isEmpty()) {
                showLoading("频道加载失败：${networkProblemText()}，自动重试中")
                return@launch
            }

            applyLastChannel()
            switchChannel()
            starting = false
        }
    }

    private var prefetchedNextPid: String? = null

    /** 首帧后预取"下一台": 让下一次换台命中缓存, 接近无缝 */
    private fun prefetchNeighbor() {
        val next = currentChannelList.getOrNull(currentChannelIdx + 1) ?: return
        if (next.pid == prefetchedNextPid) return
        prefetchedNextPid = next.pid
        scope.launch(Dispatchers.IO) {
            runCatching {
                prefetchManifest("http://127.0.0.1:10481/migufb/${next.pid}")
            }
            Logx.i("预取", "下一台已预热: ${next.name}")
        }
    }

    /** 选中上次播放的频道 (找不到就第一组第一台) */
    private fun applyLastChannel() {
        if (categories.isEmpty()) return
        val lastPid = SP.getString("last_pid", "")
        var g = 0
        var c = 0
        var found = false
        outer@ for ((gi, cat) in categories.withIndex()) {
            for ((ci, ch) in cat.dataList.withIndex()) {
                if (ch.pid == lastPid) { g = gi; c = ci; found = true; break@outer }
            }
        }
        currentGroupIdx = g.coerceIn(0, categories.size - 1)
        currentChannelList = categories[currentGroupIdx].dataList
        currentChannelIdx = if (found) c else 0
    }

    /**
     * 缓存自动刷新策略:
     *  - 缓存超过 6 小时
     *  - 跨天 (上次刷新不是今天)
     *  - 网络刚恢复
     */
    private fun maybeRefreshCache(manager: MiguBackendManager, force: Boolean = false) {
        val today = System.currentTimeMillis() / 86_400_000L
        val lastDay = SP.getLong("last_cache_day", 0L)
        val stale = manager.isCacheStale(CACHE_TTL_MS)
        val needRefresh = force || stale || lastDay != today
        Logx.i(
            "刷新",
            "缓存策略: stale=$stale(>${CACHE_TTL_MS / 3600_000}h) 跨天=${lastDay != today} force=$force -> ${if (needRefresh) "刷新" else "跳过"}"
        )
        if (needRefresh) refreshInBackground()
    }

    /** 后台刷新频道列表 (不打断当前播放) */
    private fun refreshInBackground() {
        scope.launch(Dispatchers.IO) {
            repeat(3) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val fresh = runCatching {
                    MiguBackendManager.getInstance(this@LiveActivity).loadAllChannelsAndCache()
                }.getOrNull()
                if (!fresh.isNullOrEmpty() && fresh.any { c -> c.dataList.isNotEmpty() }) {
                    Logx.i("刷新", "后台刷新成功 ${fresh.size} 组, 耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                    SP.putLong("last_cache_day", System.currentTimeMillis() / 86_400_000L)
                    withContext(Dispatchers.Main) {
                        if (!alive()) return@withContext
                        rawCategories = fresh
                        val filtered = Prefs.filter(fresh)
                        if (filtered.isEmpty()) return@withContext
                        val curPid = currentChannelList.getOrNull(currentChannelIdx)?.pid
                        categories = filtered
                        if (!overlayVisible) {
                            // 当前台仍在 -> 只更新下标; 不在 -> 不动播放, 面板里会重建
                            var gi = -1; var ci = 0
                            outer@ for ((g, cat) in filtered.withIndex()) {
                                for ((c, ch) in cat.dataList.withIndex()) {
                                    if (ch.pid == curPid) { gi = g; ci = c; break@outer }
                                }
                            }
                            if (gi >= 0) {
                                currentGroupIdx = gi
                                currentChannelList = filtered[gi].dataList
                                currentChannelIdx = ci
                            }
                        }
                    }
                    return@launch
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    private fun switchChannel() {
        val ch = currentChannelList.getOrNull(currentChannelIdx) ?: return
        SP.putString("last_pid", ch.pid)
        playedFirstFrame = false
        hardRestartDone = false
        failStreak = 0
        // 换台先收起蓝屏/等待遮罩, 只留中央大圈圈
        hideShieldScreen()
        showBigSpinner()
        Logx.i("换台", "→ ${ch.name} (${ch.pid}) 代理=$useProxy")
        showBanner(ch.name)

        player?.onError = { msg ->
            runOnUiThread {
                if (alive() && !playedFirstFrame) {
                    hideLoading()
                    failStreak++
                    Logx.e("播放", "失败(${failStreak}): $msg")
                    // 失败即换路径重试: 代理 <-> 直连 交替, 间隔 3s/6s/10s 封顶
                    // (以前等 30 秒才切, 而播放器 24 秒就报错了, 切换永远来不及)
                    autoSwitchJob?.cancel()
                    restartJob?.cancel()
                    autoSwitchJob = scope.launch {
                        // 同一频道连续失败 2 次 -> 判定为坏频道, 自动跳到下一个可播频道
                        if (failStreak >= 2 && autoSkipCount < 5) {
                            autoSkipCount++
                            val skipped = currentChannelList.getOrNull(currentChannelIdx)?.name ?: "?"
                            nextChannel()
                            showHint("「$skipped」暂不可用，已切到下一个频道")
                            Logx.w("换台", "跳过坏频道「$skipped」($autoSkipCount)")
                            delay(1000)
                            return@launch
                        }
                        val waitMs = minOf(1_500L * failStreak, 8_000L)
                        val nextProxy = !useProxy
                        showHint(
                            "播放失败，${waitMs / 1000} 秒后改用${if (nextProxy) "本地代理" else "直连"}重试"
                        )
                        delay(waitMs)
                        if (alive() && !playedFirstFrame) {
                            useProxy = nextProxy
                            Logx.i("播放", "失败重试 #${failStreak} → ${if (useProxy) "代理" else "直连"}")
                            switchChannel()
                        }
                    }
                }
            }
        }
        player?.onFirstFrame = {
            playedFirstFrame = true
            failStreak = 0
            autoSwitchJob?.cancel()
            restartJob?.cancel()
            runOnUiThread {
                if (alive()) {
                    hideLoading()
                    hideBigSpinner()   // 出画面立刻收起中央圆圈
                    onBannerReady()
                }
            }
            prefetchNeighbor()
        }

        // 10 秒还没出画面 -> 硬重启一次 (停掉卡死的加载, 重新拉流)
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(10_000)
            if (!playedFirstFrame && !hardRestartDone) {
                hardRestartDone = true
                showBigSpinner()
                player?.restart()
            }
        }
        autoSwitchJob?.cancel()
        // 关键: 冷启动时本地服务可能还没 listen, 首次请求还要做 TLS/DNS/校时/playurl/拉清单,
        // 直接甩给播放器会超时 -> "一直加载中"。这里先等就绪 + 预热, 再播放。
        scope.launch {
            val url = "http://127.0.0.1:10481/migufb/${ch.pid}"
            val ready = top.yogiczy.sutvlite.backend.LiteServer.awaitReady(15_000)
            if (!ready) {
                showBigSpinner()
                top.yogiczy.sutvlite.backend.LiteServer.start(this@LiveActivity)
                top.yogiczy.sutvlite.backend.LiteServer.awaitReady(10_000)
            }
            if (player == null || !alive()) return@launch
            // 盒子开机后系统时间要几十秒才被校准(2016->2026)。墙钟错误期间, 咪咕 CDN
            // 对走我们 OkHttp 的请求一律给 661。两条对策:
            //   1) 有 root: 直接用网络时间把系统时钟改对(瞬间解决, 不影响其它应用逻辑)
            //   2) 没 root: 改用"直连"——让 ExoPlayer 自己去拉 CDN, 它的指纹与 OkHttp 不同,
            //      常常能正常返回; 不行再退回代理
            // 开机初期: 系统时间未同步 + 运营商出口 IP 未稳定, 咪咕 CDN 会一律返回 661。
            // 这段时间不必硬撞(重试也没用), 显示"网络初始化中", 每 5 秒轻量试探一次;
            // 一旦 CDN 接受(或系统时间同步完成) 立刻正常播放。
            if (AppClock.deviceClockInsane()) {
                Logx.w("时钟", "开机初期(墙钟=${AppClock.nowTextDev()}), 显示等待遮罩")
                SystemClockFixer.ensureSystemTime() // 已授权则顺手修时钟, 不阻塞
                showBootWait(120)
                var waited = 0
                while (alive() && AppClock.deviceClockInsane() && waited < 120_000) {
                    delay(2000)
                    waited += 2000
                }
                hideBootWait()
                if (!alive()) return@launch
                Logx.i("时钟", "开机初始化完成(等待 ${waited}ms), 开始播放")
            }
            // 不再用探针做门控: 探针(timeout 短/只测一次)在老盒子上会误判, 把能用的网络
            // 当成没网, 结果卡死在播放之前。改为直接播放, 失败走退避重试(10/20/30s)。
            if (!useProxy) {
                val direct = withContext(Dispatchers.IO) { resolveDirect(ch.pid) }
                if (direct != null) {
                    player?.play(direct, isLive = true)
                    armAutoSwitch()
                    return@launch
                }
            }
            // 签名需要正确的时间戳: 时钟没校准先等它 (开机初期墙钟可能是 2016)
            if (!AppClock.isCalibrated() || AppClock.isStale()) {
                showBigSpinner()
                val clockOk = AppClock.ensureCalibrated(8000)
                if (!clockOk) {
                    Logx.w("时钟", "校准失败, 时钟异常=${AppClock.deviceClockInsane()}, 仍尝试播放")
                }
            }
            // 预热: 自己先拉一次清单 (DNS/TLS/校时/playurl/m3u8 全部热起来并进缓存)
            showBigSpinner()
            val warm = withContext(Dispatchers.IO) { prefetchManifest(url) }
            if (currentChannelList.getOrNull(currentChannelIdx)?.pid != ch.pid) return@launch
            if (warm == PREFETCH_SHIELD) {
                // 版权屏蔽 / 节目调整: 蓝屏提示 + 10 秒后自动换台
                hideLoading()
                showShieldScreen(shieldDetail)
                return@launch
            }
            player?.play(url, isLive = true)
            if (warm == PREFETCH_FAIL) {
                Logx.w("LiveActivity", "预热失败, 直接播放: ${ch.name}")
            }
            armAutoSwitch()
        }
    }

    /** 预热结果状态 */

    /** 版权屏蔽文案 (供蓝屏显示) */
    private var shieldDetail = "节目播出调整，换个内容看看吧"

    private fun prefetchManifest(url: String): Int = try {
        val client = top.yogiczy.sutvlite.backend.LegacyTls.builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder().url(url).build()
        val t0 = android.os.SystemClock.elapsedRealtime()
        client.newCall(req).execute().use { resp ->
            val code = resp.code()
            val body = runCatching { resp.body()?.string() ?: "" }.getOrDefault("")
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            if (code == 598 || body.startsWith("SHIELD|")) {
                shieldDetail = body.removePrefix("SHIELD|").ifBlank { "节目播出调整，换个内容看看吧" }
                Logx.w("预热", "版权屏蔽(${ms}ms): $shieldDetail")
                PREFETCH_SHIELD
            } else {
                Logx.i("预热", "清单 HTTP $code 耗时 ${ms}ms")
                if (resp.isSuccessful) PREFETCH_OK else PREFETCH_FAIL
            }
        }
    } catch (t: Throwable) {
        Logx.e("预热", "异常: ${t.message}")
        PREFETCH_FAIL
    }

    /** 8 秒无首帧 → 代理/直连互换 */
    /** 30 秒无首帧 → 代理/直连互换 (带提示, 避免"一直转圈"没有反馈) */
    private fun armAutoSwitch() {
        autoSwitchJob?.cancel()
        autoSwitchJob = scope.launch {
            delay(30_000)
            if (!playedFirstFrame) {
                useProxy = !useProxy
                Logx.w("换台", "30 秒无画面, 切换${if (useProxy) "代理" else "直连"}")
                switchChannel()
            }
        }
    }

    /** 换台/切换过程中的状态提示 (显示在横幅下方) */
    private fun resolveDirect(pid: String): String? = try {
        val client = top.yogiczy.sutvlite.backend.LegacyTls.builder()
            .followRedirects(false)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder().url("http://127.0.0.1:10481/migu/$pid").build()
        client.newCall(req).execute().use { r -> r.header("Location")?.takeIf { it.isNotEmpty() } }
    } catch (_: Throwable) {
        null
    }

    private fun prevChannel() {
        if (currentChannelIdx > 0) currentChannelIdx--
        else if (currentGroupIdx > 0) {
            currentGroupIdx--
            currentChannelList = categories[currentGroupIdx].dataList
            currentChannelIdx = (currentChannelList.size - 1).coerceAtLeast(0)
        } else return
        switchChannel()
    }

    private fun nextChannel() {
        if (currentChannelIdx < currentChannelList.size - 1) currentChannelIdx++
        else if (currentGroupIdx < categories.size - 1) {
            currentGroupIdx++
            currentChannelList = categories[currentGroupIdx].dataList
            currentChannelIdx = 0
        } else return
        switchChannel()
    }

    // ==================== 提示 ====================

    private fun showLoading(text: String) {
        if (!alive()) return
        loadingBoxView?.visibility = View.VISIBLE
        // 每个阶段重新计时 (换台/加载/预热各自计时更直观)
        loadingStartAt = android.os.SystemClock.elapsedRealtime()
        startLoadingTick()
        loadingBaseText = text
        loadingTv?.text = text
    }

    /** 每 500ms 刷新"已用 N 秒", 让加载过程有变化感 */
    private fun startLoadingTick() {
        loadingTickJob?.cancel()
        loadingTickJob = scope.launch {
            while (alive()) {
                val sec = (android.os.SystemClock.elapsedRealtime() - loadingStartAt) / 1000
                loadingTv?.text = "$loadingBaseText  ${sec}s"
                delay(500)
            }
        }
    }

    private fun hideLoading() {
        if (!alive()) return
        loadingTickJob?.cancel()
        loadingStartAt = 0L
        loadingBoxView?.visibility = View.GONE
    }

    /** 底部提示: 独立计时, 5 秒后必定隐藏 (不受换台横幅影响) */
    private fun showHint(text: String) {
        if (!alive()) return
        hintTv?.text = text
        hintTv?.visibility = View.VISIBLE
        hintHideJob?.cancel()
        hintHideJob = scope.launch {
            delay(5000)
            if (alive()) hintTv?.visibility = View.GONE
        }
    }

    /** 换台横幅: 频道名 + 旋转圈圈 + 状态; 出画面后 2 秒隐去 */
    private fun showBanner(name: String) {
        if (!alive()) return
        bannerName?.text = name
        bannerCard?.visibility = View.VISIBLE
        bannerHideJob?.cancel()
    }

    /** 首帧到达: 停转圈, 显示就绪后 2 秒隐去 */
    private fun onBannerReady() {
        if (!alive()) return
        bannerHideJob?.cancel()
        bannerHideJob = scope.launch {
            delay(2000)
            if (alive()) bannerCard?.visibility = View.GONE
        }
    }

    /** 立即套用屏蔽: 若正在播的台被隐藏, 自动切到同组第一个可见台 */
    private fun applyBlockFilterNow() {
        val filtered = Prefs.filter(rawCategories)
        if (filtered.isEmpty()) return // 全被隐藏时保持现状, 避免黑屏
        if (filtered == categories) return // 没有变化

        val curPid = currentChannelList.getOrNull(currentChannelIdx)?.pid
        val oldGroupName = categories.getOrNull(currentGroupIdx)?.name

        categories = filtered
        val stillThere = filtered.indexOfFirst { cat -> cat.dataList.any { it.pid == curPid } }
        if (curPid != null && stillThere >= 0) {
            // 当前台还在: 只更新下标
            currentGroupIdx = stillThere
            currentChannelList = filtered[stillThere].dataList
            currentChannelIdx = filtered[stillThere].dataList.indexOfFirst { it.pid == curPid }
                .coerceAtLeast(0)
        } else {
            // 当前台被隐藏: 优先留在原分组, 否则第一组
            val g = filtered.indexOfFirst { it.name == oldGroupName }.takeIf { it >= 0 } ?: 0
            currentGroupIdx = g
            currentChannelList = filtered[g].dataList
            currentChannelIdx = 0
            switchChannel()
        }
        // 面板开着就重建, 立刻反映
        if (overlayVisible) {
            overlayVisible = false
            showPanel()
        }
    }
    // ==================== 选台面板 ====================

    private fun showPanel() {
        if (overlayVisible || categories.isEmpty()) return
        overlayVisible = true
        panelGroupIdx = currentGroupIdx
        builtGroupIdx = currentGroupIdx
        lastChannelFocusIdx = currentChannelIdx
        bannerCard?.visibility = View.GONE
        buildGroupRows()
        buildChannelRows()
        overlay?.visibility = View.VISIBLE
        resetDismiss()
        channelScroll?.post {
            val idx = lastChannelFocusIdx.coerceIn(0, (channelRows.size - 1).coerceAtLeast(0))
            channelRows.getOrNull(idx)?.requestFocus()
        }
    }

    private fun hidePanel() {
        if (!overlayVisible) return
        overlayVisible = false
        dismissJob?.cancel()
        overlay?.visibility = View.GONE
    }

    private fun resetDismiss() {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(10_000)
            if (overlayVisible) hidePanel()
        }
    }

    private fun buildGroupRows() {
        val col = groupCol ?: return
        col.removeAllViews()
        groupRows.clear()
        categories.forEachIndexed { idx, cat ->
            val tv = TextView(this).apply {
                id = View.generateViewId()
                text = cat.name
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(14), dp(16), dp(14), dp(16))
                isFocusable = true
                isClickable = true
                setOnClickListener { channelRows.firstOrNull()?.requestFocus() }
                setOnFocusChangeListener { _, focused ->
                    if (focused && idx != panelGroupIdx) {
                        panelGroupIdx = idx
                        builtGroupIdx = idx
                        buildChannelRows()
                        linkFocus()
                    }
                    repaintGroups()
                }
            }
            col.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(5))
            })
            groupRows.add(tv)
        }
        repaintGroups()
        linkFocus()
    }

    private fun repaintGroups() {
        groupRows.forEachIndexed { idx, tv ->
            val selected = idx == panelGroupIdx
            val focused = tv.isFocused
            tv.background = when {
                focused -> bgRowFocus
                selected -> bgRowSelected
                else -> bgRowNone
            }
            tv.setTextColor(
                when {
                    focused -> 0xFF101010.toInt()
                    selected -> 0xFFFFFFFF.toInt()
                    else -> 0xFFB9BDC7.toInt()
                }
            )
        }
    }

    private fun buildChannelRows() {
        val col = channelCol ?: return
        col.removeAllViews()
        channelRows.clear()
        val list = categories.getOrNull(panelGroupIdx)?.dataList ?: emptyList()
        list.forEachIndexed { idx, ch ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                id = View.generateViewId()
                isFocusable = true
                isClickable = true
                setPadding(dp(14), dp(16), dp(14), dp(16))
                setOnClickListener {
                    lastChannelFocusIdx = idx
                    currentGroupIdx = panelGroupIdx
                    currentChannelList = list
                    currentChannelIdx = idx
                    hidePanel()
                    switchChannel()
                }
                setOnFocusChangeListener { _, focused ->
                    if (focused) lastChannelFocusIdx = idx
                    repaintChannels()
                }
            }
            row.addView(TextView(this).apply {
                text = ch.name.ifBlank { ch.pid }
                textSize = 24f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(5))
            })
            channelRows.add(row)
        }
        repaintChannels()
        linkFocus()
    }

    private fun repaintChannels() {
        channelRows.forEach { row ->
            val focused = row.isFocused
            row.background = if (focused) bgRowFocus else bgRowNone
            (row.getChildAt(0) as? TextView)?.setTextColor(
                if (focused) 0xFF101010.toInt() else 0xFFE6E8EE.toInt()
            )
        }
    }

    /** 焦点链: 上下本列(边界自锁), 左右跨列 */
    private fun linkFocus() {
        groupRows.forEachIndexed { i, tv ->
            tv.nextFocusUpId = if (i > 0) groupRows[i - 1].id else tv.id
            tv.nextFocusDownId = if (i < groupRows.size - 1) groupRows[i + 1].id else tv.id
            tv.nextFocusLeftId = tv.id
            tv.nextFocusRightId = channelRows.firstOrNull()?.id ?: tv.id
        }
        val left = groupRows.getOrNull(panelGroupIdx)?.id
        channelRows.forEachIndexed { i, row ->
            row.nextFocusUpId = if (i > 0) channelRows[i - 1].id else row.id
            row.nextFocusDownId = if (i < channelRows.size - 1) channelRows[i + 1].id else row.id
            row.nextFocusLeftId = left ?: row.id
            row.nextFocusRightId = row.id
        }
    }

    // ==================== 设置入口 ====================

    private fun openSettings() {
        hidePanel()
        startActivity(Intent(this, LiteSettingsActivity::class.java))
    }

    // ==================== 按键 ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (overlayVisible && event.action == KeyEvent.ACTION_DOWN) {
            resetDismiss()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                hidePanel(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 连续 5 次菜单键 → 设置
        if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = System.currentTimeMillis()
            menuPressTimes.add(now)
            menuPressTimes.removeAll { now - it > 4000 }
            if (menuPressTimes.size >= 5) {
                menuPressTimes.clear()
                openSettings()
                return true
            }
        }
        if (overlayVisible) return super.onKeyDown(keyCode, event)

        // 蓝屏显示时: 任何方向键/OK 都先收起蓝屏并立即换台, 保证遥控一定有效
        if (isShieldShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    hideShieldScreen()
                    shieldStreak = 0
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        prevChannel()
                    } else {
                        nextChannel()
                    }
                    return true
                }
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { prevChannel(); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> { nextChannel(); true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { prevChannel(); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { nextChannel(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // 长按 = 设置; 短按 = 选台面板 (在 onKeyUp/onKeyLongPress 判定)
                if (event.repeatCount == 0) {
                    okLongHandled = false
                    event.startTracking()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                // 作为默认桌面(HOME)时, BACK 直接 finish 会让系统立刻重启自己,
                // 快速连按就会连环重建 -> 崩溃。这里改为忽略并给提示。
                if (isDefaultHome()) {
                    showHint("已设为桌面，返回键无需退出")
                } else {
                    finish()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            okLongHandled = true
            openSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!overlayVisible &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            if (!okLongHandled) showPanel()
            okLongHandled = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** 当前是否为本机默认桌面 (HOME) */
    private fun isDefaultHome(): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ri?.activityInfo?.packageName == packageName
    } catch (_: Throwable) {
        false
    }

    /** HOME 被连按时的重复启动: 忽略, 不重建不重播 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    // ==================== 网络 ====================

    /**
     * 有无默认路由 (网线插上但没拿到 IP 时为 false)
     *
     * 注意: Android 5.0+ 用策略路由, 默认路由在各网络的专属表 (table 10xx) 里,
     * 部分新设备/ROM 不再往 main 表回写, /proc/net/route 里根本没有 00000000,
     * 只读 /proc 会把"有网"误判成"未获取到 IP"。
     * API 21+ 改问 ConnectivityManager 的 LinkProperties; 老系统才读 /proc。
     */
    private fun hasDefaultRoute(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.any { n ->
                cm.getLinkProperties(n)?.routes?.any {
                    it.isDefaultRoute || it.destination.prefixLength == 0
                } == true
            }
        } else {
            java.io.File("/proc/net/route").readLines().drop(1).any { line ->
                val cols = line.split('\t', ' ').filter { it.isNotBlank() }
                cols.size >= 2 && cols[1] == "00000000"
            }
        }
    } catch (_: Throwable) {
        // 读不出来时不误报: 能不能上网由后面的频道加载结果证明
        true
    }

    /** 无网络时的明确提示 (区分: 没插网线 / 没拿到IP / 正常) */
    private fun networkProblemText(): String = when {
        !isNetworkReady() -> "网线未连接，请检查网线/路由器"
        !hasDefaultRoute() -> "网络未就绪（未获取到 IP）"
        else -> "网络已连接"
    }

    private var lastProbeOkAt = 0L

    /** 真实连通性探针: 链路 up 不代表能上网 (开机后 DHCP/PPPoE/网关可能还没通) */
    private fun probeInternet(): Boolean {
        // 10 秒内探测成功过就直接复用, 避免每次换台都加一次 TCP 握手
        if (android.os.SystemClock.elapsedRealtime() - lastProbeOkAt < 10_000) return true
        val ok = probeInternetNow()
        if (ok) lastProbeOkAt = android.os.SystemClock.elapsedRealtime()
        return ok
    }

    /**
     * 真正的联网判定: 用 OkHttp 发一次 HEAD。
     * 不能自己起 Socket 连"DNS 第一个 IP" —— DNS 常返回多个 IP(含 IPv6/不可达边缘节点),
     * 第一个不通就误判"没网", 而 OkHttp 会自己换 IP 并重试, 真实请求其实是成功的。
     * 只要拿到任何 HTTP 响应(哪怕是 4xx/5xx)就说明网络通。
     */
    private fun probeInternetNow(): Boolean {
        for (url in PROBE_URLS) {
            try {
                val req = okhttp3.Request.Builder().url(url).head().build()
                probeClient.newCall(req).execute().use { r ->
                    if (r.code() > 0) return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private val probeClient by lazy {
        top.yogiczy.sutvlite.backend.LegacyTls.builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun isNetworkReady(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        info != null && info.isConnected
    } catch (_: Throwable) {
        true
    }

    private suspend fun ensureLocalServer() {
        val ok = withContext(Dispatchers.IO) {
            try {
                val c = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val r = okhttp3.Request.Builder().url("http://127.0.0.1:10481/api/info").build()
                c.newCall(r).execute().use { it.isSuccessful }
            } catch (_: Throwable) {
                false
            }
        }
        if (!ok) {
            runCatching { LiteServer.start(this) }
            delay(1200)
        }
    }

    private val netReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (categories.isEmpty()) {
                starting = false
                loadAndPlay()
            } else {
                // 网络恢复: 顺手检查缓存是否该刷新
                runCatching {
                    maybeRefreshCache(MiguBackendManager.getInstance(this@LiveActivity), force = true)
                }
            }
        }
    }

    private var receiverRegistered = false

    private fun registerNetReceiver() {
        if (receiverRegistered) return
        runCatching {
            registerReceiver(netReceiver, IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"))
            receiverRegistered = true
        }
    }

    private fun unregisterNetReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(netReceiver) }
        receiverRegistered = false
    }
}