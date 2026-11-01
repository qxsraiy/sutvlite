package top.yogiczy.sutvlite

import android.util.Log
import top.yogiczy.mytv.core.data.utils.Globals
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 极简直播 诊断日志
 *
 * 双通道:
 *   1) logcat (tag = JJB)   —— adb logcat -s JJB
 *   2) 落盘 files/lite.log  —— 盒子重启/卡死也不丢, adb pull 可取
 *
 * 重要: 落盘走**独立后台线程 + 有界队列**。
 * 早期版本在调用线程直接 appendText, 主线程打日志会做磁盘 I/O,
 * 在老盒子 eMMC 上足以造成 ANR (整个桌面卡死)。现在主线程只入队, 不碰磁盘。
 */
object Logx {

    private const val TAG = "JJB"
    private const val MAX_BYTES = 256 * 1024L
    private const val QUEUE_CAP = 512

    @Volatile
    var enabled: Boolean = true

    private val startedAt = android.os.SystemClock.elapsedRealtime()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val queue = LinkedBlockingQueue<String>(QUEUE_CAP)

    @Volatile
    private var writerStarted = false

    private fun rel(): String = "${android.os.SystemClock.elapsedRealtime() - startedAt}ms"

    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
            Thread({
                while (true) {
                    try {
                        val line = queue.poll(2, TimeUnit.SECONDS) ?: continue
                        // 一次批量写出, 减少磁盘操作次数
                        val sb = StringBuilder(line).append('\n')
                        while (true) {
                            val more = queue.poll() ?: break
                            sb.append(more).append('\n')
                            if (sb.length > 32 * 1024) break
                        }
                        append(sb.toString())
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (_: Throwable) {
                    }
                }
            }, "lite-log").apply { isDaemon = true }.start()
        }
    }

    private fun append(text: String) {
        try {
            val dir = Globals.fileDir
            val f = File(dir, "lite.log")
            if (f.length() > MAX_BYTES) {
                val bak = File(dir, "lite.log.1")
                bak.delete()
                f.renameTo(bak)
            }
            f.appendText(text)
        } catch (_: Throwable) {
        }
    }

    /** 入队 (不阻塞调用线程, 队列满则丢弃) */
    private fun enqueue(line: String) {
        ensureWriter()
        queue.offer(line)
    }

    fun i(phase: String, msg: String) {
        if (!enabled) return
        Log.i(TAG, "[$phase][${rel()}] $msg")
        enqueue("${fmt.format(Date())} [${rel()}] I/$phase: $msg")
    }

    fun w(phase: String, msg: String) {
        if (!enabled) return
        Log.w(TAG, "[$phase][${rel()}] $msg")
        enqueue("${fmt.format(Date())} [${rel()}] W/$phase: $msg")
    }

    fun e(phase: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$phase][${rel()}] $msg", t)
        enqueue(
            "${fmt.format(Date())} [${rel()}] E/$phase: $msg" +
                (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        )
    }

    fun mark(phase: String) {
        if (!enabled) return
        Log.i(TAG, "[$phase][${rel()}] ----")
        enqueue("${fmt.format(Date())} [${rel()}] M/$phase")
    }

    fun clear() {
        runCatching {
            File(Globals.fileDir, "lite.log").delete()
            File(Globals.fileDir, "lite.log.1").delete()
        }
    }
}