package top.yogiczy.sutvlite

import okhttp3.Request

/**
 * 应用自带时钟 —— **服务器时间锚点 + 单调计时**方案
 *
 * 为什么不用"设备墙钟 + 偏移":
 *   盒子断电后墙钟会停在固件日期(2016), 又会在开机后被系统/NTP 改成 2026。
 *   一旦墙钟跳变, 之前算出的偏移就全错了(签名时间会变成 2036), 咪咕直接拒绝。
 *
 * 现在的做法:
 *   校准时拿到服务器时间 serverMs, 记下当时的开机计时 elapsedRealtime。
 *   之后 now() = serverMs + (elapsedRealtime - 校准时的 elapsedRealtime)
 *   —— 与设备墙钟彻底解耦, 墙钟怎么跳都不影响。
 *
 * 时间源: 依次请求多个站点的 HTTP Date 头 (play.miguvideo.com / baidu / taobao),
 *         任一成功即用; 结果 30 分钟内有效, 过期自动重校。
 */
object AppClock {

    /** 2020-01-01: 早于此认为时间不可信 */
    private const val SANE_MS = 1_577_836_800_000L

    /** 校准有效期 30 分钟 (单调计时, 漂移极小) */
    private const val TTL_MS = 30 * 60 * 1000L

    private val sources = listOf(
        "https://play.miguvideo.com/",
        "https://www.baidu.com/",
        "https://www.taobao.com/",
    )

    /** 锚点: 校准时刻的服务器时间 (ms) */
    @Volatile private var anchorServerMs = 0L
    /** 锚点: 校准时刻的开机计时 (ms, 单调, 不受墙钟影响) */
    @Volatile private var anchorElapsed = 0L
    @Volatile private var syncing = false

    private val client by lazy {
        top.yogiczy.sutvlite.backend.LegacyTls.builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 当前时间: 锚点 + 单调计时增量; 未校准时退回设备墙钟 */
    fun now(): Long =
        if (anchorServerMs > 0) anchorServerMs + (android.os.SystemClock.elapsedRealtime() - anchorElapsed)
        else System.currentTimeMillis()

    fun isCalibrated(): Boolean = anchorServerMs > 0

    fun isStale(): Boolean =
        anchorServerMs <= 0 ||
            android.os.SystemClock.elapsedRealtime() - anchorElapsed > TTL_MS

    /** 设备墙钟是否明显不对 (仅用于日志/提示) */
    fun deviceClockInsane(): Boolean = System.currentTimeMillis() < SANE_MS

    fun nowText(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(now()))

    /** 同步校准 (阻塞, 后台线程调用)。返回是否成功 */
    fun calibrate(force: Boolean = false): Boolean {
        if (!force && !isStale()) return true
        synchronized(this) {
            if (syncing) return isCalibrated()
            syncing = true
        }
        try {
            for (url in sources) {
                val serverMs = try {
                    val req = Request.Builder().url(url).head().build()
                    client.newCall(req).execute().use { resp -> parseDate(resp.header("Date")) }
                } catch (t: Throwable) {
                    Logx.w("时钟", "取时失败($url): ${t.message}")
                    null
                }
                if (serverMs != null && serverMs > SANE_MS) {
                    anchorServerMs = serverMs
                    anchorElapsed = android.os.SystemClock.elapsedRealtime()
                    Logx.i(
                        "时钟",
                        "校准成功($url) 现在=${nowText()} 设备墙钟=${fmt(System.currentTimeMillis())} " +
                            "墙钟异常=${deviceClockInsane()}"
                    )
                    return true
                }
            }
            Logx.e("时钟", "所有时间源都失败, 当前=${if (isCalibrated()) nowText() else "未校准"}")
            return isCalibrated()
        } finally {
            synchronized(this) { syncing = false }
        }
    }

    /** 等待校准可用 (供播放前调用) */
    suspend fun ensureCalibrated(timeoutMs: Long = 8000): Boolean {
        if (!isStale()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (calibrate()) return true
            kotlinx.coroutines.delay(500)
        }
        return isCalibrated()
    }

    /**
     * playurl 响应里的 timeStamp 只是"我们请求时发的 timestamp"的回显,
     * 用它校准会自我强化错误, 所以不采信; 这里仅在偏差过大时记一条日志。
     */
    fun observeServerTime(serverMs: Long) {
        if (serverMs <= SANE_MS) return
        val diff = serverMs - now()
        if (diff > 120_000 || diff < -120_000) {
            Logx.w("时钟", "响应时间戳与本地偏差 ${diff / 1000}s (疑似回显, 忽略)")
        }
    }

    private fun fmt(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(s.trim())?.time
        }.getOrNull()
    }
}