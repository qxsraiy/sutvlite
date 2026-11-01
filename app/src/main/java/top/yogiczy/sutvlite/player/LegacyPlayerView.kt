package top.yogiczy.sutvlite.player

import top.yogiczy.sutvlite.Logx

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

/**
 * Lite 播放器 (为海思 Hi3798M + Android 4.4 定制)
 *
 * 只保留直播必需逻辑:
 *  - ExoPlayer + HlsMediaSource (真实浏览器 UA + 允许跨协议重定向)
 *  - 15 秒未出首帧 -> 降级系统 MediaPlayer (海思硬解死锁/编码不兼容兜底)
 *  - 瞬时错误(3002 等) 1.5 秒自动重试, 最多 2 次
 *  - 每 1 秒自动播放保险: READY 且应播未播 -> 强制 play()
 *  - 硬重启 restart(): 卡住时重新拉流
 *
 * 已剔除所有诊断层/本地探测代码 (Lite 不需要, 且每 2 秒轮询会拖慢老盒子)
 */
class LegacyPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "LitePlayer"

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

        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    enum class Backend { EXO, MEDIAPLAYER }

    /** 首帧渲染回调 (隐藏加载提示) */
    var onFirstFrame: (() -> Unit)? = null
    /** 播放错误回调 */
    var onError: ((String) -> Unit)? = null

    private var backend = Backend.EXO
    private var exoPlayer: ExoPlayer? = null
    private var mediaFallbackTried = false
    private var hasRenderedFirstFrame = false
    private var currentUrl: String? = null
    private var currentIsLive = false

    private val exoView: PlayerView = PlayerView(context).apply {
        useController = false
        setShowPreviousButton(false)
        setShowNextButton(false)
    }

    private val videoView: VideoView = VideoView(context)

    // ---- 瞬时错误重试 ----
    private var transientRetries = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable {
        exoPlayer?.let { p ->
            Log.w(TAG, "瞬时错误重试 #$transientRetries")
            Logx.w("播放器", "瞬时错误重试 #$transientRetries")
            runCatching { p.prepare(); p.playWhenReady = true }
            scheduleWatchdog()
        }
    }

    // ---- 首帧看门狗 ----
    private val watchdog = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        if (!hasRenderedFirstFrame) {
            val host = runCatching { java.net.URI(currentUrl ?: "").host }.getOrNull() ?: "?"
            if (currentIsLive && !mediaFallbackTried) {
                mediaFallbackTried = true
                Log.w(TAG, "15 秒无首帧, 降级 MediaPlayer: $host")
                useMediaPlayer(currentUrl ?: return@Runnable)
            } else {
                onError?.invoke("15秒未出画面 ($host)")
            }
        }
    }

    // ---- 自动播放保险 (1 秒一轮, 只做必要动作, 不拼字符串) ----
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            runCatching {
                val p = exoPlayer
                if (p != null && p.playbackState == Player.STATE_READY &&
                    p.playWhenReady && !p.isPlaying
                ) {
                    p.play()
                }
            }
            tickHandler.postDelayed(this, 1000)
        }
    }

    init {
        addView(exoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER
        })
        videoView.visibility = GONE
        tickHandler.post(tickRunnable)
    }

    // ==================== 对外 API ====================

    /** 是否显示内置控制器 (Lite 关闭) */
    fun setControllerEnabled(enable: Boolean) {
        exoView.useController = enable
    }

    /** 是否已渲染首帧 */
    fun isRenderedFirstFrame(): Boolean = hasRenderedFirstFrame

    /** 是否正在播放 */
    fun isPlaying(): Boolean = when (backend) {
        Backend.EXO -> exoPlayer?.isPlaying == true
        Backend.MEDIAPLAYER -> videoView.isPlaying
    }


    /** 暂停 (切后台) */
    fun pause() {
        runCatching {
            exoPlayer?.pause()
            if (videoView.isPlaying) videoView.pause()
        }
    }

    /** 播放地址 */
    fun play(url: String, isLive: Boolean = false) {
        currentUrl = url
        currentIsLive = isLive
        hasRenderedFirstFrame = false
        mediaFallbackTried = false
        transientRetries = 0
        retryHandler.removeCallbacks(retryRunnable)
        Logx.i("播放器", "play(${url.take(70)})")
        useExo(url)
        scheduleWatchdog()
    }

    /** 硬重启: 停掉卡死的加载, 重新拉同一地址 */
    fun restart() {
        val url = currentUrl ?: return
        runCatching {
            cancelWatchdog()
            hasRenderedFirstFrame = false
            mediaFallbackTried = false
            transientRetries = 0
            retryHandler.removeCallbacks(retryRunnable)
            if (backend == Backend.MEDIAPLAYER) {
                videoView.stopPlayback()
                videoView.visibility = GONE
            }
            exoView.visibility = VISIBLE
            exoPlayer?.stop()
            useExo(url)
            scheduleWatchdog()
            Log.i(TAG, "硬重启: 重新拉流")
        }.onFailure { Log.e(TAG, "硬重启失败: ${it.message}") }
    }

    fun release() {
        cancelWatchdog()
        tickHandler.removeCallbacks(tickRunnable)
        retryHandler.removeCallbacks(retryRunnable)
        runCatching { exoPlayer?.release() }
        exoPlayer = null
        runCatching { videoView.stopPlayback() }
    }

    // ==================== 内部 ====================

    private fun scheduleWatchdog() {
        watchdog.removeCallbacks(watchdogRunnable)
        watchdog.postDelayed(watchdogRunnable, 15_000L)
    }

    private fun cancelWatchdog() = watchdog.removeCallbacks(watchdogRunnable)

    private fun ensureExo() {
        if (exoPlayer != null) {
            backend = Backend.EXO
            return
        }
        val ts = DefaultTrackSelector(context)
        // 缓冲调小: 老盒子 1GB 内存 + 海思解码器, 默认 50 秒缓冲太重
        val loadControl = com.google.android.exoplayer2.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                30_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs
                3_000   // bufferForPlaybackAfterRebufferMs
            )
            .build()
        val p = ExoPlayer.Builder(context)
            .setTrackSelector(ts)
            .setLoadControl(loadControl)
            .build()
        Logx.i("播放器", "ExoPlayer 已创建 (缓冲 15s/30s)")
        exoPlayer = p
        exoView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                cancelWatchdog()
                if (error.errorCode in TRANSIENT_ERRORS && transientRetries < 2) {
                    // 3002 等瞬时错误: 1.5 秒后自动重试
                    transientRetries++
                    retryHandler.removeCallbacks(retryRunnable)
                    retryHandler.postDelayed(retryRunnable, 1500)
                    Log.w(TAG, "瞬时错误 ${error.errorCode}, 自动重试 #$transientRetries")
                } else {
                    onError?.invoke("播放错误(code=${error.errorCode})")
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
                cancelWatchdog()
                transientRetries = 0
                retryHandler.removeCallbacks(retryRunnable)
                Log.i(TAG, "第一帧已渲染")
                Logx.i("播放器", "第一帧已渲染 ✅")
                onFirstFrame?.invoke()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && hasRenderedFirstFrame) cancelWatchdog()
            }
        })
        backend = Backend.EXO
    }

    private fun useExo(url: String) {
        ensureExo()
        val p = exoPlayer ?: return
        exoView.visibility = VISIBLE
        videoView.visibility = GONE
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                )
            )
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
        val hls = HlsMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(url))
        runCatching {
            p.setMediaSource(hls)
            p.prepare()
            p.playWhenReady = true
        }.onFailure { onError?.invoke("ExoPlayer 启动失败: ${it.message}") }
    }

    /** 海思硬解死锁兜底: 走系统 MediaPlayer (该固件 MediaPlayer 能解 HEVC) */
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
            Log.e(TAG, "MediaPlayer 兜底失败 what=$what extra=$extra")
            onError?.invoke("系统播放器也失败(what=$what)")
            true
        }
        runCatching {
            videoView.setVideoURI(Uri.parse(url))
            videoView.start()
        }.onFailure { onError?.invoke("MediaPlayer 加载异常: ${it.message}") }
    }
}