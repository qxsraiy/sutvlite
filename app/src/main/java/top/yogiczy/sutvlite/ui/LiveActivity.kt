package top.yogiczy.sutvlite.ui

import top.yogiczy.sutvlite.Logx

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
    private var bannerSpinner: android.widget.ProgressBar? = null
    private var bannerStatus: TextView? = null
    /** 加载计时器: 每 500ms 更新"已用 N 秒", 让等待有变化 */
    private var loadingTickJob: Job? = null
    private var loadingStartAt = 0L
    private var loadingBaseText = ""

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
    }

    override fun onResume() {
        super.onResume()
        // 从设置返回: 立即套用分组/频道屏蔽, 不用重启
        if (rawCategories.isNotEmpty()) applyBlockFilterNow()
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
            textSize = 38f
            setTextColor(C_WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        bannerStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(C_BLUE_LIGHT)
            setPadding(0, dp(6), 0, 0)
        }
        // 换台时旋转的圈圈
        bannerSpinner = android.widget.ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(0, 0, dp(14), 0) }
        }
        val bannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bannerSpinner)
            addView(LinearLayout(this@LiveActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(bannerName)
                addView(bannerStatus)
            })
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
        setContentView(root)
    }

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
            // 等网络真正可用: 链路 up ≠ 能上网, 开机后往往还要几十秒
            var waited = 0
            while (true) {
                val linkOk = isNetworkReady() && hasDefaultRoute()
                if (linkOk && probeInternet()) break
                showLoading(
                    if (!linkOk) networkProblemText() else "网络连接中，等待网关就绪…"
                )
                delay(2000)
                waited += 2000
                if (waited % 30_000 == 0) {
                    Logx.w("网络", "等待联网 ${waited / 1000}s: 链路=$linkOk 探测=${runCatching { probeInternet() }.getOrDefault(false)}")
                }
            }
            Logx.i("网络", "联网就绪, 等待 ${waited}ms")
            showLoading("网络已连接，加载中…")
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
        showLoading("连接中…")
        Logx.i("换台", "→ ${ch.name} (${ch.pid}) 代理=$useProxy")
        showBanner(ch.name)

        player?.onError = { msg ->
            runOnUiThread {
                if (alive() && !playedFirstFrame) {
                    hideLoading()
                    failStreak++
                    Logx.e("播放", "失败(${failStreak}): $msg")
                    // 退避重试: 10s -> 20s -> 30s 封顶, 永不放弃 (开机后网络慢也能自愈)
                    autoSwitchJob?.cancel()
                    restartJob?.cancel()
                    autoSwitchJob = scope.launch {
                        val waitMs = minOf(10_000L * failStreak, 30_000L)
                        showHint("播放失败，${waitMs / 1000} 秒后自动重试")
                        delay(waitMs)
                        if (alive() && !playedFirstFrame) {
                            Logx.i("播放", "退避重试 #${failStreak}")
                            failStreak = 0
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
                showLoading("重新加载…")
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
                showLoading("服务启动中…")
                top.yogiczy.sutvlite.backend.LiteServer.start(this@LiveActivity)
                top.yogiczy.sutvlite.backend.LiteServer.awaitReady(10_000)
            }
            if (player == null || !alive()) return@launch
            // 缓存优先时可能网络还没通: 先确认能上网, 否则等 (不再直接失败)
            if (!probeInternet()) {
                showLoading("等待网络就绪…")
                var w = 0
                while (alive() && !probeInternet()) {
                    delay(2000); w += 2000
                    if (w % 30_000 == 0) Logx.w("网络", "换台等待联网 ${w / 1000}s")
                }
                if (!alive()) return@launch
                Logx.i("网络", "换台前联网就绪, 等待 ${w}ms")
            }
            if (!useProxy) {
                val direct = withContext(Dispatchers.IO) { resolveDirect(ch.pid) }
                if (direct != null) {
                    player?.play(direct, isLive = true)
                    armAutoSwitch()
                    return@launch
                }
            }
            // 预热: 自己先拉一次清单 (DNS/TLS/校时/playurl/m3u8 全部热起来并进缓存)
            showLoading("连接中…")
            val warmed = withContext(Dispatchers.IO) { prefetchManifest(url) }
            if (currentChannelList.getOrNull(currentChannelIdx)?.pid != ch.pid) return@launch
            player?.play(url, isLive = true)
            if (!warmed) {
                // 预热失败也照常交给播放器, 由 10 秒硬重启 + 代理/直连互换兜底
                android.util.Log.w("LiveActivity", "预热失败, 直接播放: ${ch.name}")
            }
            armAutoSwitch()
        }
    }

    /** 预热: 用 app 自己的 OkHttp 拉一次代理清单 (把冷启动开销提前吃在"连接中…"里) */
    private fun prefetchManifest(url: String): Boolean = try {
        val client = top.yogiczy.sutvlite.backend.LegacyTls.builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder().url(url).build()
        val t0 = android.os.SystemClock.elapsedRealtime()
        client.newCall(req).execute().use { resp ->
            val ok = resp.isSuccessful
            Logx.i(
                "预热",
                "清单 HTTP ${resp.code()} 耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms"
            )
            ok
        }
    } catch (t: Throwable) {
        Logx.e("预热", "异常: ${t.message}")
        false
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
                showLoading(if (useProxy) "切换为本地代理…" else "切换为直连…")
                switchChannel()
            }
        }
    }

    /** 换台/切换过程中的状态提示 (显示在横幅下方) */
    private fun showSwitchStatus(text: String) {
        if (!alive()) return
        bannerStatus?.text = text
        bannerCard?.visibility = View.VISIBLE
        bannerSpinner?.visibility = View.VISIBLE
    }

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
        bannerStatus?.text = "连接中…"
        bannerSpinner?.visibility = View.VISIBLE
        bannerCard?.visibility = View.VISIBLE
        bannerHideJob?.cancel()
    }

    /** 首帧到达: 停转圈, 显示就绪后 2 秒隐去 */
    private fun onBannerReady() {
        if (!alive()) return
        bannerSpinner?.visibility = View.GONE
        bannerStatus?.text = ""
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

    /** 有无默认路由 (网线插上但没拿到 IP 时为 false) */
    private fun hasDefaultRoute(): Boolean = try {
        java.io.File("/proc/net/route").readLines().drop(1).any { line ->
            val cols = line.split('\t', ' ').filter { it.isNotBlank() }
            cols.size >= 2 && cols[1] == "00000000"
        }
    } catch (_: Throwable) {
        false
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
        if (System.currentTimeMillis() - lastProbeOkAt < 10_000) return true
        val ok = probeInternetNow()
        if (ok) lastProbeOkAt = System.currentTimeMillis()
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
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
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