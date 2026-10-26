package top.yogiczy.sutvlite.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.MimeTypes

/**
 * tv-legacy 播放器 (针对 Hi3798M + Android 4.4 深坑定制):
 * - 强制 H.264 轨道选择 (避开 HEVC 变体)
 * - 首帧看门狗: 15s 未渲染首帧 -> 自动降级系统 MediaPlayer (海思固件私有硬解补丁)
 * - 音频剥离测试开关 (排除音频时钟挂死视频)
 * - 实时诊断覆盖层: 状态/选中格式(codecs/分辨率/drm)/首帧/音频/H264
 */
class LegacyPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "LegacyPlayer"

        // 瞬时错误码: 网络/IO/超时/解析 类错误, 重试通常能恢复 (如 3002 清单解析失败)
        private val TRANSIENT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }

    enum class Backend { EXO, MEDIAPLAYER }

    private var backend = Backend.EXO
    private var mediaFallbackTried = false

    private var trackSelector: DefaultTrackSelector? = null
    private var exoPlayer: ExoPlayer? = null
    private val exoView: PlayerView = PlayerView(context).apply {
        useController = true
        setShowPreviousButton(false)
        setShowNextButton(false)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    private val videoView: VideoView = VideoView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    var isHevcFallback: Boolean = false
        private set
    var onError: ((String) -> Unit)? = null
    var onBackendChange: ((Boolean) -> Unit)? = null
    /** 首帧渲染回调 (用于隐藏缓冲界面) */
    var onFirstFrame: (() -> Unit)? = null

    private var currentUrl: String? = null
    private var currentIsLive = false

    // ---- 本地服务器探测结果 (app 自用 OkHttp 拉 /migufb, 区分 服务器问题 vs ExoPlayer加载器问题) ----
    private var serverProbe = "未探测"
    private var probedUrl = ""

    /** 用 app 的 OkHttp 直接拉一次当前 URL, 结果进诊断层 */
    fun probeCurrentUrl() {
        val url = currentUrl ?: return
        if (url == probedUrl) return
        probedUrl = url
        serverProbe = "探测中…"
        Thread {
            try {
                val req = okhttp3.Request.Builder().url(url).build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val t0 = System.currentTimeMillis()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body()?.string() ?: ""
                    val ms = System.currentTimeMillis() - t0
                    serverProbe = "HTTP ${resp.code()} ${ms}ms 前缀:${body.take(50).replace('\n', ' ')}"
                    Log.i(TAG, "本地探测: $serverProbe")
                }
            } catch (t: Throwable) {
                serverProbe = "失败: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "本地探测失败: $serverProbe")
            }
        }.start()
    }

    // ---- 诊断状态 ----
    private var hasRenderedFirstFrame = false
    private var selectedFormat = "未选择"
    private var audioDisabled = false
    private var h264Only = true

    // ---- 瞬时错误自动重试 (3002 等网络/解析错误, 重试通常恢复) ----
    private var transientRetries = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable {
        exoPlayer?.let { p ->
            Log.w(TAG, "瞬时错误自动重试 #$transientRetries")
            p.prepare()
            p.playWhenReady = true
            scheduleWatchdog()
        }
    }
    private fun scheduleRetry() {
        if (transientRetries < 2) {
            transientRetries++
            retryHandler.removeCallbacks(retryRunnable)
            retryHandler.postDelayed(retryRunnable, 1500)
        }
    }

    // ---- 首帧看门狗: 15s 未渲染第一帧 -> 判定硬解死锁/编码不支持 -> 降级 MediaPlayer ----
    private val watchdog = Handler(Looper.getMainLooper())
    private val timeoutMs = 15_000L
    private val watchdogRunnable = Runnable {
        if (!hasRenderedFirstFrame) {
            val host = runCatching { java.net.URI(currentUrl ?: "").host }.getOrNull()
                ?: currentUrl?.take(50) ?: "?"
            if (currentIsLive && !mediaFallbackTried) {
                mediaFallbackTried = true
                onError?.invoke("硬解15秒无输出(选中:$selectedFormat) 疑似海思解码器死锁/编码不支持\n自动切换系统播放器试播 $host")
                useMediaPlayer(currentUrl ?: return@Runnable)
            } else {
                onError?.invoke("15秒未渲染首帧 [选中:$selectedFormat] 源:$host\n(硬解死锁或编码不兼容)")
            }
        }
    }

    private fun scheduleWatchdog() {
        watchdog.removeCallbacks(watchdogRunnable)
        watchdog.postDelayed(watchdogRunnable, timeoutMs)
    }
    private fun cancelWatchdog() {
        watchdog.removeCallbacks(watchdogRunnable)
    }

    // ---- 诊断覆盖层 (实时刷新) ----
    private val diagTv: TextView = TextView(context).apply {
        textSize = 11f
        setTextColor(android.graphics.Color.YELLOW)
        setBackgroundColor(0x88000000.toInt())
        setPadding(8, 4, 8, 4)
    }
    private val diagHandler = Handler(Looper.getMainLooper())
    private val diagRunnable = object : Runnable {
        override fun run() {
            updateDiagText()
            // 自动播放保险: READY 且 playWhenReady 却未在播 -> 强制 play (修复需点击才开始)
            try {
                val p = exoPlayer
                if (p != null && p.playbackState == Player.STATE_READY && p.playWhenReady && !p.isPlaying) {
                    p.play()
                }
            } catch (_: Throwable) { }
            diagHandler.postDelayed(this, 2000)
        }
    }

    init {
        addView(exoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(diagTv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        })
        videoView.visibility = GONE
        diagTv.visibility = GONE // 默认隐藏诊断层, 按菜单键可切换
        diagHandler.post(diagRunnable)
    }

    private fun updateDiagText() {
        val state = when (exoPlayer?.playbackState) {
            Player.STATE_BUFFERING -> "缓冲中"
            Player.STATE_READY -> "READY"
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_ENDED -> "结束"
            else -> "?"
        }
        // 轮询当前选中的视频格式 (替代 onVideoInputFormatChanged, 兼容 2.19)
        val vf = exoPlayer?.videoFormat
        if (vf != null) {
            selectedFormat = "${vf.sampleMimeType} ${vf.codecs} ${vf.width}x${vf.height}" +
                (if (vf.drmInitData != null) " DRM!" else "")
        }
        diagTv.text = "后端:${backend} 状态:$state pwR:${exoPlayer?.playWhenReady}\n" +
            "编码:${selectedFormat}\n" +
            "源:${runCatching { java.net.URI(currentUrl ?: "").host }.getOrNull() ?: currentUrl?.take(40) ?: "-"}\n" +
            "本服:${serverProbe}\n" +
            "首帧:${if (hasRenderedFirstFrame) "已渲染✅" else "未渲染❌"} 音频:${if (audioDisabled) "关(测试)" else "开"} H264:${if (h264Only) "强制" else "自动"}"
    }

    // ==================== 对外 API ====================

    /** 是否显示 PlayerView 自带控制器 (直播页关闭: 不要暂停/进度条等按键控件) */
    fun setControllerEnabled(enable: Boolean) {
        exoView.useController = enable
    }

    /** 诊断层显示开关 (默认隐藏, 菜单键切换) */
    fun setDiagVisible(visible: Boolean) {
        diagTv.visibility = if (visible) VISIBLE else GONE
    }

    /** H.264 强制开关 (诊断标记; 实际兜底靠首帧看门狗->MediaPlayer) */
    fun setH264Only(enable: Boolean) {
        h264Only = enable
        // ExoPlayer 2.19 无 setVideoMimeTypes (Media3 才有), 不实际限制轨道;
        // HEVC/SEI死锁 统一由首帧看门狗 15s -> 自动切系统 MediaPlayer 兜底
    }

    /** 剥离音频轨 (排除音频时钟挂死视频) */
    fun setAudioDisabled(enable: Boolean) {
        audioDisabled = enable
        trackSelector?.let { ts ->
            ts.setParameters(ts.buildUponParameters().setRendererDisabled(C.TRACK_TYPE_AUDIO, enable))
        }
    }

    fun play(url: String, isLive: Boolean = false) {
        currentUrl = url
        currentIsLive = isLive
        hasRenderedFirstFrame = false
        mediaFallbackTried = false
        probedUrl = ""
        transientRetries = 0
        retryHandler.removeCallbacks(retryRunnable)
        useExo(url)
        scheduleWatchdog()
        // 稍后用 app 自己的 OkHttp 拉一次当前 URL, 区分服务器问题 vs ExoPlayer加载器问题
        watchdog.postDelayed({ probeCurrentUrl() }, 2000)
    }

    // ==================== ExoPlayer ====================

    private fun ensureExoUi() {
        if (exoPlayer == null) {
            val ts = DefaultTrackSelector(context)
            trackSelector = ts
            exoPlayer = ExoPlayer.Builder(context)
                .setTrackSelector(ts)
                .build()
            exoView.player = exoPlayer
            setH264Only(true) // 默认强制 H.264
            setAudioDisabled(false)

            exoPlayer!!.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    cancelWatchdog()
                    if (error.errorCode in TRANSIENT_ERRORS && transientRetries < 2) {
                        // 瞬时错误(3002网络/解析等): 1.5s 后自动重试, 不弹错误
                        scheduleRetry()
                        Log.w(TAG, "瞬时错误 ${error.errorCode} 自动重试 #$transientRetries: ${error.message}")
                    } else {
                        onError?.invoke("播放错误(code=${error.errorCode}): ${error.message}")
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                        // 仅当已渲染过首帧才算真正就绪; 否则看门狗继续盯
                        if (hasRenderedFirstFrame) cancelWatchdog()
                        retryHandler.removeCallbacks(retryRunnable)
                    }
                }

                override fun onRenderedFirstFrame() {
                    hasRenderedFirstFrame = true
                    cancelWatchdog()
                    transientRetries = 0
                    retryHandler.removeCallbacks(retryRunnable)
                    onFirstFrame?.invoke()
                    Log.i(TAG, "第一帧已渲染 ✅")
                }
            })
        }
        backend = Backend.EXO
        exoView.visibility = VISIBLE
        videoView.visibility = GONE
    }

    private fun useExo(url: String) {
        ensureExoUi()
        exoPlayer?.let { p ->
            // 用 HlsMediaSource + 自定义 UA, 更贴近原版行为
            val dataSource = DefaultHttpDataSource.Factory()
                // 用真实浏览器 UA: 部分 CDN(如电影天堂的 play.phimgood.com) 会拒绝非常规 UA
                .setUserAgent("Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .setDefaultRequestProperties(
                    mapOf(
                        "Accept" to "*/*",
                        "Accept-Language" to "zh-CN,zh;q=0.9",
                    )
                )
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true)
            val hls = HlsMediaSource.Factory(dataSource)
                .createMediaSource(MediaItem.fromUri(url))
            p.setMediaSource(hls)
            p.prepare()
            p.playWhenReady = true
        }
    }

    // ==================== MediaPlayer 兜底 (海思固件私有补丁) ====================

    private fun useMediaPlayer(url: String) {
        backend = Backend.MEDIAPLAYER
        exoView.visibility = GONE
        videoView.visibility = VISIBLE
        val mc = MediaController(context)
        videoView.setMediaController(mc)
        mc.setAnchorView(videoView)
        videoView.setOnPreparedListener { mp ->
            Log.i(TAG, "MediaPlayer prepared: ${mp.videoWidth}x${mp.videoHeight}")
            hasRenderedFirstFrame = true
            cancelWatchdog()
            onFirstFrame?.invoke()
        }
        videoView.setOnErrorListener { _, what, extra ->
            onError?.invoke("MediaPlayer 兜底也失败 what=$what extra=$extra")
            true
        }
        try {
            videoView.setVideoURI(Uri.parse(url))
            videoView.start()
        } catch (t: Throwable) {
            onError?.invoke("MediaPlayer 加载异常: ${t.message}")
        }
    }

    // ==================== 控制 ====================

    fun togglePlay() {
        if (backend == Backend.MEDIAPLAYER) {
            if (videoView.isPlaying) videoView.pause() else videoView.start()
        } else {
            exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
        }
    }

    fun getStateText(): String = diagTv.text?.toString() ?: ""

    /** 硬重启: 停掉当前加载, 重新拉取同一地址 (用于"一直转圈"时兜底) */
    fun restart() {
        val url = currentUrl ?: return
        try {
            cancelWatchdog()
            exoPlayer?.stop()
            hasRenderedFirstFrame = false
            useExo(url)
            scheduleWatchdog()
            android.util.Log.i(TAG, "硬重启: 重新拉流 ${url.take(60)}")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "硬重启失败: ${t.message}")
        }
    }

    /** 是否已渲染首帧 (智能换源判断用) */
    fun isRenderedFirstFrame(): Boolean = hasRenderedFirstFrame

    /** 是否正在播放 */
    fun isPlaying(): Boolean = when (backend) {
        Backend.EXO -> exoPlayer?.isPlaying == true
        Backend.MEDIAPLAYER -> videoView.isPlaying
    }

    /** 相对拖动 (毫秒, 正数快进/负数快退) */
    fun seekBy(deltaMs: Long): Long {
        return try {
            when (backend) {
                Backend.EXO -> {
                    val p = exoPlayer ?: return 0
                    val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    val target = (p.currentPosition + deltaMs).coerceIn(0L, dur)
                    p.seekTo(target)
                    target
                }
                Backend.MEDIAPLAYER -> {
                    val dur = videoView.duration.takeIf { it > 0 } ?: Int.MAX_VALUE
                    val target = (videoView.currentPosition + deltaMs).coerceIn(0L, dur.toLong()).toInt()
                    videoView.seekTo(target)
                    target.toLong()
                }
            }
        } catch (_: Throwable) { 0L }
    }

    /** 进度文本 "01:23 / 45:00" */
    fun positionText(): String {
        return try {
            val cur: Long
            val dur: Long
            when (backend) {
                Backend.EXO -> {
                    cur = exoPlayer?.currentPosition ?: 0L
                    dur = exoPlayer?.duration?.takeIf { it > 0 } ?: 0L
                }
                Backend.MEDIAPLAYER -> {
                    cur = videoView.currentPosition.toLong()
                    dur = videoView.duration.toLong().takeIf { it > 0 } ?: 0L
                }
            }
            "${fmt(cur)} / ${if (dur > 0) fmt(dur) else "--:--"}"
        } catch (_: Throwable) { "--:--" }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    /** 暂停播放 (切后台时调用) */
    fun pause() {
        try {
            exoPlayer?.pause()
            if (videoView.isPlaying) videoView.pause()
        } catch (_: Throwable) { }
    }

    fun release() {
        cancelWatchdog()
        diagHandler.removeCallbacks(diagRunnable)
        try { exoPlayer?.release() } catch (_: Throwable) { }
        exoPlayer = null
        exoView.player = null
        try { videoView.stopPlayback() } catch (_: Throwable) { }
    }
}