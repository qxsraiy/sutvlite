package top.yogiczy.sutvlite.ui

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
class LiveActivity : Activity() {

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

    private var overlay: FrameLayout? = null
    private var groupCol: LinearLayout? = null
    private var channelCol: LinearLayout? = null
    private var groupScroll: ScrollView? = null
    private var channelScroll: ScrollView? = null
    private var overlayVisible = false
    private var panelGroupIdx = 0
    private var lastChannelFocusIdx = 0
    private var dismissJob: Job? = null

    private val groupRows = mutableListOf<TextView>()
    private val channelRows = mutableListOf<LinearLayout>()

    private var useProxy = true
    private var playedFirstFrame = false
    private var autoSwitchJob: Job? = null
    private var bannerHideJob: Job? = null
    private var starting = false

    // 设置入口: 长按 OK / 5 次菜单
    private var okLongHandled = false
    private var menuPressTimes = mutableListOf<Long>()

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        buildUi()
        registerNetReceiver()
        loadAndPlay()
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

        val pv = LegacyPlayerView(this).apply {
            setControllerEnabled(false)
            setDiagVisible(false)
        }
        player = pv
        root.addView(pv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 加载/等待提示 (居中)
        loadingTv = TextView(this).apply {
            textSize = 17f
            setTextColor(C_BLUE_LIGHT)
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(14), dp(26), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xCC0B1220.toInt())
                setStroke(dp(1), C_BLUE)
            }
        }
        root.addView(loadingTv, FrameLayout.LayoutParams(
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
            textSize = 30f
            setTextColor(C_WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        bannerCard!!.addView(bannerName)
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
            setBackgroundColor(0xB30B1220.toInt())
            visibility = View.GONE
        }
        overlay = ov

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF0E1A2E.toInt())
                setStroke(dp(2), C_BLUE)
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        groupCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        groupScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(groupCol)
        }
        body.addView(groupScroll, LinearLayout.LayoutParams(dp(180), dp(360)))

        body.addView(View(this).apply { setBackgroundColor(0x662F7BFF) },
            LinearLayout.LayoutParams(dp(2), dp(360)).apply { setMargins(dp(12), 0, dp(12), 0) })

        channelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(channelCol)
        }
        body.addView(channelScroll, LinearLayout.LayoutParams(0, dp(360), 1f))

        card.addView(body)
        ov.addView(card, FrameLayout.LayoutParams(
            dp(760), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        root.addView(ov, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 加载 + 播放 ====================

    private fun loadAndPlay() {
        if (starting) return
        starting = true
        showLoading("启动中…")
        scope.launch {
            // 等网络就绪 (开机后 DHCP 可能没完成)
            var waited = 0
            while (!isNetworkReady() && waited < 45_000) {
                showLoading("等待网络…")
                delay(1000); waited += 1000
            }
            ensureLocalServer()

            var attempt = 0
            while (attempt < 5) {
                attempt++
                showLoading(if (attempt == 1) "加载频道…" else "加载频道…($attempt)")
                categories = withContext(Dispatchers.IO) {
                    runCatching {
                        val all = MiguBackendManager.getInstance(this@LiveActivity).loadAllChannels()
                        rawCategories = all
                        Prefs.filter(all)
                    }.getOrElse { emptyList() }
                }
                if (categories.isNotEmpty()) break
                delay(3000)
            }
            starting = false
            if (categories.isEmpty()) {
                showLoading("频道加载失败，网络恢复后自动重试")
                return@launch
            }

            // 上次频道 or 第一个
            val lastPid = SP.getString("last_pid", "")
            var g = 0
            var c = 0
            var found = false
            outer@ for ((gi, cat) in categories.withIndex()) {
                for ((ci, ch) in cat.dataList.withIndex()) {
                    if (ch.pid == lastPid) { g = gi; c = ci; found = true; break@outer }
                }
            }
            currentGroupIdx = g
            currentChannelList = categories[g].dataList
            currentChannelIdx = if (found) c else 0
            switchChannel()
        }
    }

    private fun switchChannel() {
        val ch = currentChannelList.getOrNull(currentChannelIdx) ?: return
        SP.putString("last_pid", ch.pid)
        playedFirstFrame = false
        showLoading("连接中…")
        showBanner(ch.name)

        player?.onError = { msg ->
            runOnUiThread {
                if (alive() && !playedFirstFrame) {
                    hideLoading()
                    showHint("播放失败: $msg")
                }
            }
        }
        player?.onFirstFrame = {
            playedFirstFrame = true
            autoSwitchJob?.cancel()
            runOnUiThread { if (alive()) hideLoading() }
        }

        autoSwitchJob?.cancel()
        if (useProxy) {
            player?.play("http://127.0.0.1:10481/migufb/${ch.pid}", isLive = true)
            armAutoSwitch()
        } else {
            scope.launch {
                val direct = withContext(Dispatchers.IO) { resolveDirect(ch.pid) }
                val url = direct ?: "http://127.0.0.1:10481/migufb/${ch.pid}"
                player?.play(url, isLive = true)
                armAutoSwitch()
            }
        }
    }

    /** 8 秒无首帧 → 代理/直连互换 */
    private fun armAutoSwitch() {
        autoSwitchJob?.cancel()
        autoSwitchJob = scope.launch {
            delay(8000)
            if (!playedFirstFrame) {
                useProxy = !useProxy
                switchChannel()
            }
        }
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
        loadingTv?.text = text
        loadingTv?.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        if (!alive()) return
        loadingTv?.visibility = View.GONE
    }

    private fun showHint(text: String) {
        hintTv?.text = text
        hintTv?.visibility = View.VISIBLE
        bannerHideJob?.cancel()
        bannerHideJob = scope.launch {
            delay(5000)
            hintTv?.visibility = View.GONE
        }
    }

    /** 换台横幅: 只有频道名, 3.5 秒隐去 */
    private fun showBanner(name: String) {
        bannerName?.text = name
        bannerCard?.visibility = View.VISIBLE
        bannerHideJob?.cancel()
        bannerHideJob = scope.launch {
            delay(3500)
            bannerCard?.visibility = View.GONE
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
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                setOnClickListener { channelRows.firstOrNull()?.requestFocus() }
                setOnFocusChangeListener { _, focused ->
                    if (focused) {
                        panelGroupIdx = idx
                        buildChannelRows()
                        linkFocus()
                    }
                    repaintGroups()
                }
            }
            col.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(3))
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
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(
                    when {
                        focused -> C_BLUE
                        selected -> C_BLUE_DIM
                        else -> 0x00000000
                    }
                )
                if (focused) setStroke(dp(2), C_WHITE)
            }
            tv.setTextColor(if (focused) C_WHITE else if (selected) C_BLUE_LIGHT else 0xFF8FA3C0.toInt())
            tv.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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
                setPadding(dp(12), dp(10), dp(12), dp(10))
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
                textSize = 17f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(3))
            })
            channelRows.add(row)
        }
        repaintChannels()
        linkFocus()
    }

    private fun repaintChannels() {
        channelRows.forEach { row ->
            val focused = row.isFocused
            row.background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (focused) C_BLUE else 0x00000000)
                if (focused) setStroke(dp(2), C_WHITE)
            }
            (row.getChildAt(0) as? TextView)?.setTextColor(if (focused) C_WHITE else 0xFFE6ECF5.toInt())
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