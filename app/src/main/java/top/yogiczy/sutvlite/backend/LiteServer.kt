package top.yogiczy.sutvlite.backend

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
        CoroutineScope(Dispatchers.IO).launch {
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pid = request.path.substringAfterLast("/")
                val result = MiguApi.getStreamUrl720p(pid)
                if (!result.isSuccess) {
                    Log.e(TAG, "获取播放地址失败: ${result.error}")
                    response.code(502)
                    response.setContentType("text/plain")
                    response.send("获取播放地址失败: ${result.error}")
                    return@launch
                }
                val finalUrl = resolveRedirect(result.url).ifEmpty { result.url }
                if (!fallback) {
                    response.redirect(finalUrl)
                } else {
                    proxyM3u8(finalUrl, response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理频道请求失败: ${e.message}")
                response.code(502)
                response.setContentType("text/plain")
                response.send("错误: ${e.message}")
            }
        }
    }

    /** 代理 m3u8 (重写内部 URL) 或 TS 分片 */
    private fun handleProxy(request: AsyncHttpServerRequest, response: AsyncHttpServerResponse) {
        CoroutineScope(Dispatchers.IO).launch {
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
                        // TS 分片写临时文件, 保证二进制完整
                        val temp = java.io.File(Globals.cacheDir, "tsproxy_${System.currentTimeMillis()}.ts")
                        temp.writeBytes(bytes)
                        response.setContentType("video/mp2t")
                        response.sendFile(temp)
                        temp.delete()
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
                response.setContentType("application/vnd.apple.mpegurl")
                response.send(rewriteM3u8(body, baseUrl))
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