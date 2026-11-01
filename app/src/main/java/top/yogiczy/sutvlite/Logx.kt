package top.yogiczy.sutvlite

import android.util.Log
import top.yogiczy.mytv.core.data.utils.Globals
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简直播 诊断日志
 *
 * 双通道:
 *   1) logcat (tag = JJB)  —— adb logcat -s JJB
 *   2) 落盘 files/lite.log —— 盒子重启/卡死也不丢, adb pull 可取
 *
 * 用法: 记录关键阶段与耗时, 便于定位"卡在加载中"和"盒子卡死"
 *   Logx.mark("启动")     // 记录相对进程启动的毫秒数
 *   Logx.i("预热", "HTTP 200 1234ms")
 */
object Logx {

    private const val TAG = "JJB"
    private const val MAX_BYTES = 256 * 1024L

    @Volatile
    var enabled: Boolean = true

    /** 进程启动时刻 (SystemClock.elapsedRealtime, 不受墙钟漂移影响) */
    private val startedAt = android.os.SystemClock.elapsedRealtime()

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun rel(): String = "${android.os.SystemClock.elapsedRealtime() - startedAt}ms"

    @Synchronized
    private fun write(line: String) {
        try {
            val dir = Globals.fileDir
            val f = File(dir, "lite.log")
            if (f.length() > MAX_BYTES) {
                val bak = File(dir, "lite.log.1")
                bak.delete()
                f.renameTo(bak)
            }
            f.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    fun i(phase: String, msg: String) {
        if (!enabled) return
        val line = "${fmt.format(Date())} [${rel()}] I/$phase: $msg"
        Log.i(TAG, "[$phase][${rel()}] $msg")
        write(line)
    }

    fun w(phase: String, msg: String) {
        if (!enabled) return
        val line = "${fmt.format(Date())} [${rel()}] W/$phase: $msg"
        Log.w(TAG, "[$phase][${rel()}] $msg")
        write(line)
    }

    fun e(phase: String, msg: String, t: Throwable? = null) {
        val line = "${fmt.format(Date())} [${rel()}] E/$phase: $msg" +
            (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        Log.e(TAG, "[$phase][${rel()}] $msg", t)
        write(line)
    }

    /** 关键节点标记 (打印堆栈上下文, 便于确认是否重复创建实例) */
    fun mark(phase: String) {
        if (!enabled) return
        val line = "${fmt.format(Date())} [${rel()}] M/$phase"
        Log.i(TAG, "[$phase][${rel()}] ----")
        write(line)
    }

    fun clear() {
        runCatching {
            File(Globals.fileDir, "lite.log").delete()
            File(Globals.fileDir, "lite.log.1").delete()
        }
    }
}