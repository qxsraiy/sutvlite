package top.yogiczy.sutvlite

import top.yogiczy.sutvlite.root.RootManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 系统时钟修正器
 *
 * 现象与原因:
 *   盒子断电后系统时间停在固件日期 2016-05-09, 要 40~80 秒后才被系统自己校准。
 *   在这段时间里, 咪咕 CDN 对所有请求一律返回 661(与 app 的签名时间无关, 我们签名
 *   已经是正确时间), 所以怎么重试都播不了 —— 表现为"开机第一次死活打不开"。
 *
 * 解法:
 *   既然我们有 root, 就用 AppClock(已校准的网络时间) 直接**把盒子的系统时钟改对**,
 *   一秒内解决, 之后一切正常。
 *
 * 命令: busybox date MMDDhhmmYYYY.ss  (该 busybox 版本按 UTC 解释参数)
 *   实测: `su -c "busybox date 110411562026.00"` -> 系统时间被正确设置
 */
object SystemClockFixer {

    @Volatile private var lastTryAt = 0L
    private const val RETRY_INTERVAL_MS = 10_000L

    /** 时间不可信下限 (2020-01-01) */
    private const val SANE_MS = 1_577_836_800_000L

    /**
     * 若系统时钟明显错误, 尝试用校准过的网络时间修正它。
     * @return 修正后系统时钟是否可信
     */
    fun ensureSystemTime(): Boolean {
        if (!AppClock.deviceClockInsane()) return true
        if (!AppClock.isCalibrated()) return false

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTryAt < RETRY_INTERVAL_MS) return false
        lastTryAt = now

        // 只在**已授权**时直接修时钟; 不去申请 root(申请会弹窗/超时, 反而拖慢播放)。
        // 没有 root 也没关系: 签名用 AppClock, CDN 的 IP 校验由"重新签发+重试"处理。
        if (!RootManager.isGranted) {
            Logx.w("时钟", "系统时间异常(无 root), 不阻塞播放, 交给 CDN 重试机制")
            return false
        }

        val targetMs = AppClock.now()
        if (targetMs < SANE_MS) return false

        // busybox 按 UTC 解释: 取 UTC 的年月日时分
        val fmt = SimpleDateFormat("MMddHHmmyyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val arg = fmt.format(Date(targetMs)) + ".00"

        val attempts = listOf(
            "busybox date $arg",
            "/system/bin/busybox date $arg",
            "date -u $arg",
        )
        for (cmd in attempts) {
            val r = runCatching { RootManager.execRoot(cmd, 8000) }.getOrNull()
            if (!AppClock.deviceClockInsane()) {
                Logx.i("时钟", "已用网络时间修正系统时钟 ($cmd) -> 现在=${AppClock.nowTextDev()}")
                return true
            }
            Logx.w("时钟", "修正失败($cmd): ${r?.output?.take(80) ?: "无输出"}")
        }
        return false
    }
}