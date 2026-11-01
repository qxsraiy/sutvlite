package top.yogiczy.sutvlite.backend

import top.yogiczy.sutvlite.Logx

import android.content.Context
import android.util.Log
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.http.server.AsyncHttpServerRequest
import com.koushikdutta.async.http.server.AsyncHttpServerResponse
import com.koushikdutta.async.http.server.HttpServerRequestCallback
import com.koushikdutta.async.AsyncServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.sutvlite.migu.MiguApi

/**
 * 极简本地代理服务 (只服务直播):
 *   GET /api/info            自检
 *   GET /migu/{pid}          302 到最终 CDN 地址 (直连模式)
 *   GET /migufb/{pid}        代理主清单 (把 m3u8 内 URL 改写到本机 /miguprox/)
 *   GET /miguprox/{urlenc}   代理子清单 / TS 分片
 *
 * 为什么要代理: 盒子走 127.0.0.1 明文 HTTP, 完全绕开系统 TLS 与 CDN 的 UA/证书限制。
 */
object LiteServer {

    private const val TAG = "LiteServer"
    private const val PORT = 10481
    private val serverUrl = "http://127.0.0.1:$PORT"

    private var startRetry = 0

    /** 共用 IO 作用域 (避免每个请求都新建 scope) */
    private val io = CoroutineScope(Dispatchers.IO)

    /** 服务是否已成功 listen (冷启动时 Activity 要等它, 否则首次请求直接 Connection refused) */
    @Volatile private var ready = false

    fun isReady(): Boolean = ready

    /** 等待服务就绪, 返回是否就绪 */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ready) return true
            kotlinx.coroutines.delay(100)
        }
        return ready
    }

    /** playurl 结果短缓存 (20s): 让"预热请求"和"播放器请求"命中同一 CDN 节点, 主清单缓存才能生效 */
    /** 上游并发上限: 老盒子单核/双核, 同时太多上游请求会把 CPU 打满 -> 卡死 */
    private val upstreamGate = kotlinx.coroutines.sync.Semaphore(4)

    /** 同一 pid 的并发合并: ExoPlayer 重试时不会重复走 playurl */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private val playUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val PLAYURL_TTL = 20_000L

    fun invalidateChannel(pid: String) {
        playUrlCache.remove(pid)
    }

    /** 低内存时释放清单缓存 */
    fun trimCaches() {
        masterCache.clear()
        playUrlCache.clear()
        inFlight.clear()
    }

    /** 主清单缓存 (30 秒): 直播子清单必须实时, 但主清单可以缓存, 减少 CDN 请求 */
    private val masterCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val MASTER_TTL = 30_000L

    /** 宽松 TLS + 强制 TLS1.2 的客户端 */
    private val proxyClient: OkHttpClient by lazy {
        LegacyTls.builder()
            .followRedirects(true)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        LegacyTls.builder()
            .followRedirects(false)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun start(context: Context) {
        io.launch {
            try {
                runCatching {
                    Globals.cacheDir.listFiles()?.filter { it.name.startsWith("tsproxy_") }?.forEach { it.delete() }
                }
                val server = AsyncHttpServer()

                server.get("/api/info") { _, response ->
                    response.setContentType("application/json")
                    response.send("""{"app":"sutvlite","port":$PORT}""")
                }

                server.get("/migu/(.+)") { request, response ->
                    handleChannel(request, response, fallback = false)
                }

                server.get("/migufb/(.+)") { request, response ->
                    handleChannel(request, response, fallback = true)
                }

                server.get("/miguprox/(.+)") { request, response ->
                    handleProxy(request, response)
                }

                server.listen(AsyncServer.getDefault(), PORT)
                ready = true
                Log.i(TAG, "本地服务已启动: $serverUrl")
            } catch (ex: Exception) {
                Log.e(TAG, "本地服务启动失败: ${ex.message}")
                startRetry++
                if (startRetry <= 10) {
                    Log.i(TAG, "2 秒后重试启动 (第 $startRetry 次)")
                    kotlinx.coroutines.delay(2000)
                    start(context)
                }
            }
        }
    }

    /** 咪咕频道: 直连(302) 或 代理清单 */
    private fun handleChannel(
        request: AsyncHttpServerRequest,
        response: AsyncHttpServerResponse,
        fallback: Boolean,
    ) {
        io.launch {
            val pid = request.path.substringAfterLast("/")
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                val streamUrl = fetchStreamUrl(pid)
                if (streamUrl == null) {
                    response.code(502)
                    response.setContentType("text/plain")
                    response.send("获取播放地址失败")
                    Logx.e("代理", "pid=$pid 取流失败")
                    return@launch
                }
                val finalUrl = resolveRedirect(streamUrl).ifEmpty { streamUrl }
                if (!fallback) {
                    response.redirect(finalUrl)
                } else {
                    proxyM3u8(finalUrl, response)
                }
                Logx.i(
                    "代理",
                    "pid=$pid 模式=${if (fallback) "代理" else "302"} 耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms"
                )
            } catch (e: Exception) {
                Logx.e("代理", "pid=$pid 异常: ${e.message}", e)
                invalidateChannel(pid)
                response.code(502)
                response.setContentType("text/plain")
                response.send("错误: ${e.message}")
            }
        }
    }

    /** 同一 pid 串行 + 全局上游并发上限: 避免 ExoPlayer 重试风暴把 CPU 打满 (老盒子卡死主因) */
    private suspend fun fetchStreamUrl(pid: String): String? {
        playUrlCache[pid]?.let { (t, u) ->
            if (System.currentTimeMillis() - t < PLAYURL_TTL) return u
        }
        val mutex = inFlight.getOrPut(pid) { kotlinx.coroutines.sync.Mutex() }
        return mutex.withLock {
            playUrlCache[pid]?.let { (t, u) ->
                if (System.currentTimeMillis() - t < PLAYURL_TTL) return@withLock u
            }
            upstreamGate.withPermit {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val result = MiguApi.getStreamUrl720p(pid)
                if (!result.isSuccess || result.url.isEmpty()) {
                    Logx.e("代理", "playurl 失败: ${result.error}")
                    null
                } else {
                    playUrlCache[pid] = System.currentTimeMillis() to result.url
                    Logx.i("代理", "playurl ok 耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                    result.url
                }
            }
        }
    }

    /** 代理 m3u8 (重写内部 URL) 或 TS 分片 */
    private fun handleProxy(request: AsyncHttpServerRequest, response: AsyncHttpServerResponse) {
        io.launch {
            try {
                val encoded = request.path.substringAfter("/miguprox/")
                val targetUrl = java.net.URLDecoder.decode(encoded, "UTF-8")

                val req = Request.Builder().url(targetUrl).get().build()
                proxyClient.newCall(req).execute().use { resp ->
                    val contentType = resp.header("Content-Type") ?: ""
                    val bytes = resp.body()?.bytes() ?: ByteArray(0)
                    val text = String(bytes, Charsets.UTF_8)

                    if (text.trimStart().startsWith("#EXTM3U") || contentType.contains("mpegurl")) {
                        val baseUrl = resp.request().url().toString()
                        val rewritten = rewriteM3u8(text, baseUrl)
                        response.setContentType("application/vnd.apple.mpegurl")
                        response.send(rewritten)
                    } else {
                        // TS 分片直接发送二进制, 不再写临时文件 (省 eMMC 写入与延迟)
                        response.send("video/mp2t", bytes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "代理失败: ${e.message}")
                response.code(502)
                response.setContentType("text/plain")
                response.send("代理失败: ${e.message}")
            }
        }
    }

    private fun proxyM3u8(remoteUrl: String, response: AsyncHttpServerResponse) {
        try {
            masterCache[remoteUrl]?.let { (t, body) ->
                if (System.currentTimeMillis() - t < MASTER_TTL) {
                    response.setContentType("application/vnd.apple.mpegurl")
                    response.send(body)
                    return
                }
            }
            val req = Request.Builder().url(remoteUrl).get().build()
            proxyClient.newCall(req).execute().use { resp ->
                val body = resp.body()?.string() ?: ""
                val baseUrl = resp.request().url().toString()
                if (!body.trimStart().startsWith("#EXTM3U")) {
                    Log.e(TAG, "上游非M3U8(HTTP ${resp.code()}): ${body.take(120)}")
                    response.code(502)
                    response.setContentType("text/plain")
                    response.send("上游不是M3U8(HTTP ${resp.code()})")
                    return
                }
                val rewritten = rewriteM3u8(body, baseUrl)
                response.setContentType("application/vnd.apple.mpegurl")
                response.send(rewritten)
                masterCache[remoteUrl] = System.currentTimeMillis() to rewritten
            }
        } catch (e: Exception) {
            Log.e(TAG, "代理M3U8失败: ${e.message}")
            response.code(502)
            response.setContentType("text/plain")
            response.send("代理M3U8失败: ${e.message}")
        }
    }

    /** 把 m3u8 里的 URL 行改写到本机 /miguprox/ */
    private fun rewriteM3u8(content: String, baseUrl: String): String {
        val serverBase = "$serverUrl/miguprox/"
        val sb = StringBuilder()
        for (line in content.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                sb.append(line.trimEnd('\r')).append("\n")
            } else {
                val absolute = try {
                    java.net.URL(java.net.URL(baseUrl), trimmed).toString()
                } catch (_: Exception) {
                    trimmed
                }
                val enc = java.net.URLEncoder.encode(absolute, "UTF-8")
                sb.append(serverBase).append(enc).append("\n")
            }
        }
        return sb.toString()
    }

    /** 请求带 ddCalcu 的地址, 读 Location 得到最终 CDN 地址 */
    private fun resolveRedirect(streamUrl: String): String = try {
        val req = Request.Builder().url(streamUrl).get().build()
        noRedirectClient.newCall(req).execute().use { resp ->
            val loc = resp.header("Location") ?: ""
            if (loc.startsWith("http")) loc else ""
        }
    } catch (e: Exception) {
        Log.e(TAG, "重定向解析失败: ${e.message}")
        ""
    }
}