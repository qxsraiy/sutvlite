package top.yogiczy.sutvlite

import okhttp3.Request
import top.yogiczy.mytv.core.data.utils.SP

/**
 * 应用自带时钟 (解决盒子无 RTC / 开机初期墙钟错误)
 *
 * 为什么需要:
 *   盒子断电后时钟回到固件日期(2016), 咪咕 playurl 的签名里带 timestamp,
 *   用 2016 的时间签名 -> 返回的流地址会被 CDN 拒绝(661),
 *   表现为"开机第一次死活打不开, 退出重进(系统时间校准后)就好了"。
 *
 * 做法:
 *   - 用 HTTP 响应的 Date 头校准(多域名依次尝试), 算出与真实时间的偏移
 *   - 偏移持久化: 下次开机墙钟又错时, 直接用上次的服务器绝对时间兜底
 *   - 所有签名统一走 AppClock.now(), 不再各处自己算
 *   - 开机第一件事就校准, 需要的地方 ensureCalibrated() 等它就绪
 */
object AppClock {

    /** 2020-01-01: 早于此认为设备墙钟不可信 */
    private const val SANE_MS = 1_577_836_800_000L

    /** 校准有效期 5 分钟 */
    private const val TTL_MS = 5 * 60 * 1000L

    private val urls = listOf(
        "https://play.miguvideo.com/",
        "https://www.baidu.com/",
        "https://www.taobao.com/",
    )

    @Volatile private var offsetMs: Long? = null
    @Volatile private var calibratedElapsed = 0L
    @Volatile private var syncing = false

    private val client by lazy {
        top.yogiczy.sutvlite.backend.LegacyTls.builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 当前校准过的时间 */
    fun now(): Long = System.currentTimeMillis() + (offsetMs ?: 0L)

    fun isCalibrated(): Boolean = offsetMs != null

    fun isStale(): Boolean =
        offsetMs == null ||
            android.os.SystemClock.elapsedRealtime() - calibratedElapsed > TTL_MS

    /** 设备墙钟是否明显不对 */
    fun deviceClockInsane(): Boolean = System.currentTimeMillis() < SANE_MS

    /** 已校准时间可读形式 (日志用) */
    fun nowText(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(now()))

    /**
     * 同步校准 (阻塞, 只能在后台线程调用)
     * @return 是否校准成功
     */
    fun calibrate(force: Boolean = false): Boolean {
        if (!force && !isStale()) return true
        synchronized(this) {
            if (syncing) return isCalibrated()
            syncing = true
        }
        try {
            // 1) 先用持久化的服务器时间兜底 (墙钟错的时候也能立刻拿到合理时间)
            if (offsetMs == null && deviceClockInsane()) restoreFromDisk()

            // 2) 依次尝试多个域名取 Date 头
            for (url in urls) {
                val serverMs = try {
                    val req = Request.Builder().url(url).head().build()
                    client.newCall(req).execute().use { resp ->
                        parseDate(resp.header("Date"))
                    }
                } catch (t: Throwable) {
                    Logx.w("时钟", "校准失败($url): ${t.message}")
                    null
                }
                if (serverMs != null && serverMs > SANE_MS) {
                    val offset = serverMs - System.currentTimeMillis()
                    offsetMs = offset
                    calibratedElapsed = android.os.SystemClock.elapsedRealtime()
                    runCatching {
                        SP.putLong("clock_server_ms", serverMs)
                        SP.putLong("clock_offset_ms", offset)
                    }
                    Logx.i(
                        "时钟",
                        "校准成功($url) 偏移=${offset / 86_400_000}天 现在=${nowText()} " +
                            "墙钟异常=${deviceClockInsane()}"
                    )
                    return true
                }
            }
            Logx.e("时钟", "全部域名校准失败, 当前偏移=${(offsetMs ?: 0L) / 86_400_000}天")
            return isCalibrated()
        } finally {
            synchronized(this) { syncing = false }
        }
    }

    /** 等待校准完成 (校验到超时), 供直播启动/换台前调用 */
    suspend fun ensureCalibrated(timeoutMs: Long = 6000): Boolean {
        if (!isStale()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        calibrate()
        while (System.currentTimeMillis() < deadline) {
            if (!isStale()) return true
            kotlinx.coroutines.delay(400)
            calibrate()
        }
        return isCalibrated()
    }

    /** 用服务器返回的权威时间戳刷新 (playurl 响应里的 timeStamp) */
    fun applyServerTime(serverMs: Long) {
        if (serverMs <= SANE_MS) return
        val offset = serverMs - System.currentTimeMillis()
        offsetMs = offset
        calibratedElapsed = android.os.SystemClock.elapsedRealtime()
        runCatching {
            SP.putLong("clock_server_ms", serverMs)
            SP.putLong("clock_offset_ms", offset)
        }
        Logx.i("时钟", "按服务器响应校准 偏移=${offset / 86_400_000}天")
    }

    /** 墙钟异常时, 用上次保存的服务器时间兜底 (近似"现在") */
    private fun restoreFromDisk() {
        val savedServer = runCatching { SP.getLong("clock_server_ms", 0L) }.getOrDefault(0L)
        if (savedServer > SANE_MS) {
            val offset = savedServer - System.currentTimeMillis()
            offsetMs = offset
            calibratedElapsed = android.os.SystemClock.elapsedRealtime()
            Logx.w(
                "时钟",
                "墙钟异常, 用上次服务器时间兜底 偏移≈${offset / 86_400_000}天 " +
                    "(上次=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(savedServer))})"
            )
        }
    }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(s.trim())?.time
        }.getOrNull()
    }
}