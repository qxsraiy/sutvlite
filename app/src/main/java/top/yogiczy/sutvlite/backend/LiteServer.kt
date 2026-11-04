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
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
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

    /** 每 pid 已失败的码率档位 (用于清单不可用时降码率) */
    /** 最近一次 CDN 报告的本机出口 IP (响应头 Host-Ip) */
    @Volatile private var lastHostIp: String? = null

    private val rateAttempt = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val rateChain = intArrayOf(3, 2, 1)

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
            // 盒子 OpenSSL 不支持 GCM, 默认套件会 SSL handshake aborted; 限定 CBC 套件
            // 必须保留 CLEARTEXT: 咪咕 CDN 大量走 HTTP(80/8080), 只给 TLS 会报 "CLEARTEXT not enabled"
            .connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT, LegacyTls.CBC_SPEC))
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        LegacyTls.builder()
            .followRedirects(false)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var starting = false

    fun start(context: Context) {
        synchronized(this) {
            if (starting || ready) {
                return
            }
            starting = true
        }
        io.launch {
            try {
                runCatching {
                    Globals.cacheDir.listFiles()?.filter { it.name.startsWith("tsproxy_") }?.forEach { it.delete() }
                }
                val server = AsyncHttpServer()

                server.get("/api/info") { _, response ->
                    response.setContentType("application/json")
                    response.send("""{"app":"sutvlite","port":$PORT,"ready":$ready}""")
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
                Logx.i("服务", "本地代理已就绪: $serverUrl")
            } catch (ex: Exception) {
                Log.e(TAG, "本地服务启动失败: ${ex.message}")
                Logx.e("服务", "启动失败(${startRetry + 1}): ${ex.message}")
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
                    proxyM3u8(pid, finalUrl, response)
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
            if (android.os.SystemClock.elapsedRealtime() - t < PLAYURL_TTL) return u
        }
        val mutex = inFlight.getOrPut(pid) { kotlinx.coroutines.sync.Mutex() }
        return mutex.withLock {
            playUrlCache[pid]?.let { (t, u) ->
                if (android.os.SystemClock.elapsedRealtime() - t < PLAYURL_TTL) return@withLock u
            }
            upstreamGate.withPermit {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val idx = (rateAttempt[pid] ?: 0).coerceIn(0, rateChain.size - 1)
                val rate = rateChain[idx]
                val result = MiguApi.getStreamUrlWithRate(pid, rate)
                if (!result.isSuccess || result.url.isEmpty()) {
                    Logx.e("代理", "playurl 失败: ${result.error}")
                    null
                } else {
                    playUrlCache[pid] = android.os.SystemClock.elapsedRealtime() to result.url
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
                // 保持原协议: 咪咕 CDN 走明文端口(80/8080), 强行 HTTPS 只会握手失败(502)
                val targetUrl = java.net.URLDecoder.decode(encoded, "UTF-8")

                val req = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.miguvideo.com/")
                    .build()
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

    /**
     * 拉主清单并改写。
     * 上游返回非 m3u8 (403/HTML 错误页/空) 时 → 换新节点重试，绝不把坏响应丢给播放器。
     * 以前直接回 502 文本，ExoPlayer 会报 code=2004(内容类型不合法)，表现为"第一次打不开"。
     */
    /**
     * 拉主清单并改写。
     *
     * 重要: 盒子(尤其开机未校时阶段)会对 HTTP 明文做拦截, 直接返回 661。
     * 所以这里**优先用 HTTPS**(同主机同路径)拉清单, 不通再回落 HTTP;
     * 两者都失败才降码率/换节点重试, 绝不把坏响应丢给播放器。
     */
    /**
     * 拉主清单并改写。
     *
     * 注意: 咪咕 CDN 是**明文 HTTP**(80/8080), 不能对它做 HTTPS 握手
     * (会报 SSL23_GET_SERVER_HELLO:unknown protocol, 还多耗 0.5~1.5 秒)。
     * 所以这里固定用 HTTP; 失败时: 重新签发 URL(换出口IP/节点) + 降码率, 最多 6 轮。
     */
    private suspend fun proxyM3u8(pid: String, remoteUrl: String, response: AsyncHttpServerResponse, depth: Int = 0) {
        try {
            masterCache[remoteUrl]?.let { (t, body) ->
                if (android.os.SystemClock.elapsedRealtime() - t < MASTER_TTL) {
                    response.setContentType("application/vnd.apple.mpegurl")
                    response.send(body)
                    return
                }
            }

            val fr = fetchRobust(remoteUrl, wantM3u8 = true)
            val base = fr.base
            if (fr.code == 200 && base != null) {
                val rewritten = rewriteM3u8(String(fr.bytes, Charsets.UTF_8), base)
                response.setContentType("application/vnd.apple.mpegurl")
                response.send(rewritten)
                masterCache[remoteUrl] = android.os.SystemClock.elapsedRealtime() to rewritten
                rateAttempt.remove(pid)
                return
            }

            if (depth == 0) {
                Logx.e("代理", "清单不可用 code=${fr.code} hostIp=${fr.hostIp} (将重新签发重试)")
            }
            // 降码率
            val cur = rateAttempt[pid] ?: 0
            if (cur < rateChain.size - 1) {
                rateAttempt[pid] = cur + 1
                Logx.w("代理", "降码率 ${rateChain[cur]} -> ${rateChain[cur + 1]}")
            }
            // 重新签发 URL (新 URL 会绑定当前出口 IP) 并重试
            if (depth < 6) {
                kotlinx.coroutines.delay(300)
                val fresh = retryFreshUrl(pid, remoteUrl)
                if (fresh != null) {
                    proxyM3u8(pid, fresh, response, depth + 1)
                    return
                }
            }
            response.code(502)
            response.setContentType("text/plain")
            response.send("上游不可用(HTTP ${fr.code})")
        } catch (e: Exception) {
            Logx.e("代理", "代理M3U8失败: ${e.message}")
            if (depth < 2) {
                val fresh = retryFreshUrl(pid, remoteUrl)
                if (fresh != null) {
                    proxyM3u8(pid, fresh, response, depth + 1)
                    return
                }
            }
            response.code(502)
            response.setContentType("text/plain")
            response.send("代理M3U8失败: ${e.message}")
        }
    }

    private suspend fun retryFreshUrl(pid: String, oldUrl: String): String? = try {
        if (pid.isBlank()) {
            null
        } else {
            playUrlCache.remove(pid)
            val ridx = (rateAttempt[pid] ?: 0).coerceIn(0, rateChain.size - 1)
            val r = MiguApi.getStreamUrlWithRate(pid, rateChain[ridx])
            if (r.isSuccess && r.url.isNotEmpty()) {
                val finalUrl = resolveRedirect(r.url).ifEmpty { r.url }
                playUrlCache[pid] = android.os.SystemClock.elapsedRealtime() to r.url
                // 即使地址相同也返回: 降码率/重签后同一 URL 也可能变可用
                finalUrl
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 抗 DNS 污染/劫持的 GET。
     *
     * 盒子开机初期系统时间错 -> 系统解析器(DoT/证书)失效 -> 公共 DNS 兜底可能返回假地址,
     * 假服务器会回 661 之类的东西。这里把域名解析成"所有已知 IP", 然后逐个尝试,
     * 拿到真正内容(#EXTM3U 或二进制分片)为止。
     */
    private data class FetchResult(val code: Int, val bytes: ByteArray, val base: String?, val hostIp: String?)
    private fun fetchRobust(url: String, wantM3u8: Boolean, extraHeaders: Map<String, String> = emptyMap()): FetchResult {
        // 1) 正常按域名请求
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.miguvideo.com/")
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            proxyClient.newCall(req).execute().use { r ->
                val bytes = r.body()?.bytes() ?: ByteArray(0)
                val text = String(bytes, Charsets.UTF_8)
                val okContent = if (wantM3u8) text.trimStart().startsWith("#EXTM3U") else bytes.isNotEmpty()
                val hostIp = r.header("Host-Ip")
                if (r.code() == 200 && okContent) {
                    return FetchResult(200, bytes, r.request().url().toString(), hostIp)
                }
                Logx.w("DNS", "按域名请求失败 code=${r.code()} Host-Ip=$hostIp, 改为逐 IP 重试")
                if (hostIp != null) lastHostIp = hostIp
            }
        }.onFailure { Logx.w("DNS", "按域名请求异常: ${it.message}") }

        // 2) 逐 IP 尝试 (HTTP 可用 Host 头伪装域名)
        val uri = java.net.URI(url)
        val host = uri.host ?: return FetchResult(0, ByteArray(0), null, lastHostIp)
        val port = if (uri.port > 0) uri.port else 80
        val pathAndQuery = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        for (ip in PublicDns.resolveAll(host)) {
            val ipUrl = "http://${ip.hostAddress}:$port$pathAndQuery"
            val r = runCatching {
                val req = Request.Builder()
                    .url(ipUrl)
                    .header("Host", if (port == 80) host else "$host:$port")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.miguvideo.com/")
                    .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                    .get()
                    .build()
                proxyClient.newCall(req).execute().use { resp ->
                    val bytes = resp.body()?.bytes() ?: ByteArray(0)
                    val text = String(bytes, Charsets.UTF_8)
                    val okContent = if (wantM3u8) text.trimStart().startsWith("#EXTM3U") else bytes.isNotEmpty()
                    if (resp.code() == 200 && okContent) {
                        Logx.i("DNS", "命中可用 IP: ${ip.hostAddress} (code=${resp.code()})")
                        FetchResult(200, bytes, url, resp.header("Host-Ip"))
                    } else {
                        Logx.w("DNS", "${ip.hostAddress} code=${resp.code()} 内容不合法")
                        null
                    }
                }
            }.getOrNull()
            if (r != null) return r
        }
        return FetchResult(0, ByteArray(0), null, lastHostIp)
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