package top.yogiczy.sutvlite.root

import android.util.Log
import top.yogiczy.mytv.core.data.utils.Globals
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root 管理：检测 + 授权 + 命令执行
 *
 * 策略：
 *  - 优先长驻 su 会话（授权一次后不再反复弹窗，快）
 *  - 会话读写带超时；超时/异常自动销毁会话并降级为 `su -c` 一次性执行
 *  - 一次性执行也带超时（读流放独立线程，主流程 waitFor 轮询），保证永不卡死
 *  - 所有 exec 必须在 IO 线程调用
 */
object RootManager {
    private const val TAG = "RootManager"

    data class RootResult(val output: String, val exitCode: Int) {
        val success: Boolean get() = exitCode == 0
        override fun toString(): String = "exit=$exitCode | $output"
    }

    private val SU_PATHS = listOf(
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
    )

    private val ROOT_MANAGER_PACKAGES = listOf(
        "com.kingroot.kinguser", "com.kingo.root", "com.topjohnwu.magisk",
        "com.superuser", "eu.chainfire.supersu", "me.weishu.kernelsu",
    )

    private var suProcess: Process? = null
    private var suOut: DataOutputStream? = null
    private var suIn: BufferedReader? = null
    private val lock = Any()
    private val granted = AtomicBoolean(false)
    private val useOneShot = AtomicBoolean(false)

    // ─── 检测 ───

    fun isRooted(): Boolean = suBinary() != null || hasRootManagerApp()

    private fun hasRootManagerApp(): Boolean = try {
        val pm = Globals.appContext.packageManager
        ROOT_MANAGER_PACKAGES.any { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }
    } catch (_: Exception) { false }

    fun suBinary(): String? {
        for (path in SU_PATHS) if (File(path).exists()) return path
        for (dir in (System.getenv("PATH") ?: "").split(":")) {
            if (dir.isNotBlank() && File(dir, "su").exists()) return "$dir/su"
        }
        return null
    }

    val isGranted: Boolean get() = granted.get()

    fun statusText(): String = when {
        !isRooted() -> "Root: 未检测到 su"
        granted.get() -> "Root: 已授权 ✅"
        else -> "Root: 已检测到 su, 点按获取授权"
    }

    // ─── 授权 ───

    /** 返回 true 表示已拿到 root 权限；会尝试建立长驻会话 */
    fun requestRoot(): Boolean {
        synchronized(lock) {
            if (granted.get() && verifyAlive()) return true
            val su = suBinary() ?: run {
                Log.w(TAG, "找不到 su")
                return false
            }

            // 一次性 su -c 验证（可能弹授权窗）
            val oneShot = execOneShot(su, "id", 20000)
            if (oneShot == null || !oneShot.output.contains("uid=0")) {
                Log.w(TAG, "su -c id 失败: ${oneShot?.output}")
                return false
            }
            Log.i(TAG, "一次性 su 验证通过")

            // 尝试长驻会话（失败就用一次性模式）
            if (openSession(su)) {
                granted.set(true)
                useOneShot.set(false)
                Log.i(TAG, "长驻 su 会话已建立")
            } else {
                granted.set(true)
                useOneShot.set(true)
                Log.i(TAG, "降级为 su -c 一次性模式")
            }
            return true
        }
    }

    private fun openSession(su: String): Boolean = try {
        closeSession()
        val proc = ProcessBuilder(su).redirectErrorStream(true).start()
        suProcess = proc
        suOut = DataOutputStream(proc.outputStream)
        suIn = BufferedReader(InputStreamReader(proc.inputStream))
        val r = execInSession("id", 8000)
        r != null && r.output.contains("uid=0")
    } catch (t: Throwable) {
        Log.w(TAG, "长驻会话建立失败: ${t.message}")
        closeSession()
        false
    }

    private fun verifyAlive(): Boolean = when {
        useOneShot.get() -> true
        else -> (suProcess?.let { try { it.exitValue(); false } catch (_: IllegalThreadStateException) { true } } ?: false)
    }

    // ─── 执行 ───

    /**
     * 执行 root 命令。未授权时会自动尝试获取授权。
     * 长驻会话超时会自动降级并重试一次。
     */
    fun execRoot(cmd: String, timeoutMs: Long = 15000): RootResult? {
        synchronized(lock) {
            if (!granted.get() && !requestRoot()) return null

            if (useOneShot.get()) {
                val su = suBinary() ?: return null
                return execOneShot(su, cmd, timeoutMs)
            }

            val r = execInSession(cmd, timeoutMs)
            if (r != null) return r

            // 会话失效：降级为一次性并重试
            Log.w(TAG, "会话执行失败/超时, 降级 su -c 重试")
            closeSession()
            useOneShot.set(true)
            val su = suBinary() ?: return null
            return execOneShot(su, cmd, timeoutMs)
        }
    }

    /** 执行并返回输出（失败也返回输出，便于展示原因） */
    fun execText(cmd: String, timeoutMs: Long = 15000): String =
        execRoot(cmd, timeoutMs)?.output ?: "未获得 root 权限"

    // ─── 长驻会话实现（带超时） ───

    private fun execInSession(cmd: String, timeoutMs: Long): RootResult? {
        val inp = suIn ?: return null
        val out = suOut ?: return null

        drainBuffer(inp)
        val marker = "__RC_${System.nanoTime()}__"
        try {
            out.writeBytes("$cmd\n")
            out.writeBytes("echo $marker:\$?\n")
            out.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "会话写入失败: ${t.message}")
            return null
        }

        val sb = StringBuilder()
        val holder = arrayOfNulls<RootResult>(1)
        val latch = CountDownLatch(1)
        val reader = Thread {
            try {
                while (true) {
                    val line = inp.readLine() ?: break
                    if (line.startsWith(marker)) {
                        val code = line.substringAfter(":").trim().toIntOrNull() ?: -1
                        holder[0] = RootResult(sb.toString().trim(), code)
                        break
                    }
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!finished) {
            Log.w(TAG, "会话命令超时(${timeoutMs}ms): ${cmd.take(60)}")
            closeSession()
            return null
        }
        return holder[0]
    }

    private fun drainBuffer(inp: BufferedReader) {
        try {
            var n = 0
            while (inp.ready() && n < 200) {
                inp.readLine() ?: break
                n++
            }
        } catch (_: Throwable) {
        }
    }

    // ─── 一次性 su -c 实现（带超时） ───

    private fun execOneShot(su: String, cmd: String, timeoutMs: Long): RootResult? {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(sb) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(line)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()

            val code = waitExit(proc, timeoutMs)
            if (code == null) {
                Log.w(TAG, "一次性命令超时(${timeoutMs}ms): ${cmd.take(60)}")
                runCatching { proc.destroy() }
                synchronized(sb) { RootResult(sb.toString().trim() + "\n[超时]", -1) }
            } else {
                try { reader.join(800) } catch (_: Throwable) { }
                synchronized(sb) { RootResult(sb.toString().trim(), code) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "一次性命令异常: ${t.message}")
            runCatching { proc?.destroy() }
            RootResult("执行异常: ${t.message}", -1)
        }
    }

    private fun waitExit(proc: Process, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return proc.exitValue()
            } catch (_: IllegalThreadStateException) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    // ─── 释放 ───

    fun release() {
        synchronized(lock) {
            closeSession()
            granted.set(false)
            useOneShot.set(false)
        }
    }

    private fun closeSession() {
        runCatching { suOut?.writeBytes("exit\n"); suOut?.flush() }
        runCatching { suProcess?.destroy() }
        suProcess = null
        suOut = null
        suIn = null
    }
}