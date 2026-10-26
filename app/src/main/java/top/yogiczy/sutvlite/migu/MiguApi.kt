package top.yogiczy.sutvlite.migu

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * 咪咕视频直播源后端服务
 * 从 migu_video Node.js 后端移植到 Android
 */
object MiguApi {
    const val CATEGORY_LIST_URL = "https://program-sc.miguvideo.com/live/v2/tv-data/1ff892f2b5ab4a79be6e25b69d2f5d05"
    const val CATEGORY_URL = "https://program-sc.miguvideo.com/live/v2/tv-data/"
    const val PLAYURL_URL = "https://play.miguvideo.com/playurl/v1/play/playurl"
    
    const val APP_VERSION = "26000370"
    const val APP_VERSION_720P = "2600034600"
    
    private const val ANDROID_KEYS = "cdabyzwxkl"
    private const val ANDROID_SUFFIX = "&sv=10004&ct=android"
    
    const val RATE_720P = 3
    const val RATE_1080P = 4
    
    val logger: Logger = Logger.getLogger("MiguApi")
    
    private val client: OkHttpClient by lazy {
        top.yogiczy.sutvlite.backend.LegacyTls.builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 老盒子专用: 宽松TLS + CBC套件 (探针实锤 CBC 握手能通, GCM 会 SSLProtocolException) */
    private val cbcClient: OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .sslSocketFactory(
                top.yogiczy.mytv.core.data.network.TrustAllSSLSocketFactory.sslSocketFactory,
                top.yogiczy.mytv.core.data.network.TrustAllSSLSocketFactory.trustManager
            )
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(top.yogiczy.sutvlite.backend.LegacyTls.CBC_SPEC))
            .build()
    }
    
    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    suspend fun fetchCategories(): List<MiguCategory> {
        return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(CATEGORY_LIST_URL)
                .header("User-Agent", "okhttp/4.12.0")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                
                val body = response.body()?.string() ?: return@use emptyList()
                val json = JSONObject(body)
                val bodyObj = json.getJSONObject("body")
                val liveList = bodyObj.getJSONArray("liveList")
                
                val categories = mutableListOf<MiguCategory>()
                for (i in 0 until liveList.length()) {
                    categories.add(MiguCategory.fromJson(liveList.getJSONObject(i)))
                }
                
                categories.filter { it.name != "热门" }
                    .sortedBy { if (it.name == "央视") 0 else 1 }
            }
        } catch (e: Exception) {
            logger.severe("获取频道列表失败: ${e.message}")
            emptyList()
        }
        }
    }
    
    suspend fun fetchChannels(categoryId: String): List<MiguChannel> {
        return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$CATEGORY_URL$categoryId")
                .header("User-Agent", "okhttp/4.12.0")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                
                val body = response.body()?.string() ?: return@use emptyList()
                val json = JSONObject(body)
                val bodyObj = json.getJSONObject("body")
                val dataList = bodyObj.getJSONArray("dataList")
                
                val channels = mutableListOf<MiguChannel>()
                for (i in 0 until dataList.length()) {
                    channels.add(MiguChannel.fromJson(dataList.getJSONObject(i)))
                }
                
                channels
            }
        } catch (e: Exception) {
            logger.severe("获取频道列表失败: ${e.message}")
            emptyList()
        }
        }
    }
    
    suspend fun getStreamUrl720p(pid: String): MiguStreamResult {
        var last: MiguStreamResult? = null
        for (attempt in 0..1) {
            val timestamp = nowMs().toString()
            val appVersion = APP_VERSION_720P
            val clientId = md5(System.currentTimeMillis().toString())
            val headers = mutableMapOf<String, String>(
                "AppVersion" to appVersion,
                "TerminalId" to "android",
                "X-UP-CLIENT-CHANNEL-ID" to "$appVersion-99000-201600010010028",
                "ClientId" to clientId
            )

            if (pid != "641886683" && pid != "641886773") {
                headers["appCode"] = "miguvideo_default_android"
            }

            val str = timestamp + pid + appVersion.substring(0, 8)
            val md5Hash = md5(str)

            val salt = String.format("%06d", (Math.random() * 1000000).toInt()) + "25"
            val suffix = "2cac4f2c6c3346a5b34e085725ef7e33migu" + salt.substring(0, 4)
            val sign = md5(md5Hash + suffix)

            val params = "?sign=$sign&rateType=$RATE_720P&contId=$pid&timestamp=$timestamp&salt=$salt" +
                    "&flvEnable=true&super4k=true&h265N=false&4kvivid=false&2Kvivid=false&vivid=0"

            val res = requestPlayUrl(PLAYURL_URL + params, headers, RATE_720P, pid)
            last = res
            // 时间戳过期: doPlayUrlRequest 已用服务器 timeStamp 校准, 这里重建签名重试一次
            if (res.error?.contains("ERROR_TS_OVER_DUE") == true && attempt == 0) {
                logger.warning("时间戳过期, 已校准(${timeOffsetMs}ms), 重建签名重试")
                continue
            }
            return res
        }
        return last ?: MiguStreamResult(url = "", rateType = 0, content = null, error = "未知错误")
    }
    
    // ==================== 时间校准 ====================
    // 咪咕校验签名时间戳, 设备时钟偏差 -> ERROR_TS_OVER_DUE(401)。
    // 盒子(无 RTC 电池)时钟会持续漂移, 所以偏移必须周期性重算, 不能只算一次:
    //  1) HTTP Date 头校准 (每 5 分钟过期重算)
    //  2) playurl 响应里的 timeStamp 是服务器权威时间, 每次用它刷新偏移
    //  3) 命中 ERROR_TS_OVER_DUE 时立刻用响应时间校准并重试

    @Volatile private var timeOffsetMs: Long? = null
    @Volatile private var lastSyncAt = 0L
    private val SYNC_TTL_MS = 5 * 60 * 1000L

    private fun nowMs(): Long {
        val cached = timeOffsetMs
        val stale = System.currentTimeMillis() - lastSyncAt > SYNC_TTL_MS
        val offset = if (cached == null || stale) syncTimeOffset() else cached
        return System.currentTimeMillis() + offset
    }

    /** 用服务器给的时间戳刷新偏移 (响应里的 timeStamp 是权威时间) */
    private fun applyServerTime(serverMs: Long) {
        if (serverMs <= 1_000_000_000_000L) return
        val offset = serverMs - System.currentTimeMillis()
        timeOffsetMs = offset
        lastSyncAt = System.currentTimeMillis()
        logger.info("按响应校准时钟偏移: ${offset}ms (${offset / 3_600_000}h)")
    }

    private fun syncTimeOffset(): Long {
        return try {
            val req = Request.Builder()
                .url("https://play.miguvideo.com/")
                .head()
                .build()
            client.newCall(req).execute().use { resp ->
                val serverMs = parseHttpDate(resp.header("Date"))
                val deviceMs = System.currentTimeMillis()
                val offset = if (serverMs != null) serverMs - deviceMs else 0L
                logger.info("时间校准: 服务器=$serverMs 本机=$deviceMs 偏移=${offset}ms (${offset / 3_600_000}h)")
                timeOffsetMs = offset
                lastSyncAt = deviceMs
                offset
            }
        } catch (t: Throwable) {
            logger.warning("时间校准失败: ${t.message}")
            timeOffsetMs ?: 0L
        }
    }

    private fun parseHttpDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(s.trim())?.time
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getStreamUrl(userId: String, token: String, pid: String, rateType: Int): MiguStreamResult {
        val timestamp = nowMs().toString()
        val appVersion = APP_VERSION
        
        val headers = mutableMapOf<String, String>(
            "AppVersion" to appVersion,
            "TerminalId" to "android",
            "X-UP-CLIENT-CHANNEL-ID" to "$appVersion-99000-200300220100002"
        )
        
        if (pid != "641886683" && pid != "641886773") {
            headers["appCode"] = "miguvideo_default_android"
        }
        
        if (rateType != 2 && userId.isNotEmpty() && token.isNotEmpty()) {
            headers["UserId"] = userId
            headers["UserToken"] = token
        }
        
        val str = timestamp + pid + appVersion
        val md5Hash = md5(str)
        
        val salt = 1230024
        val sign = md5(md5Hash + "3ce941cc3cbc40528bfd1c64f9fdf6c0migu0123")
        
        val params = "?sign=$sign&rateType=$rateType&contId=$pid&timestamp=$timestamp&salt=$salt" +
                "&flvEnable=true&super4k=true${if (rateType == 9) "&ott=true" else ""}" +
                "&h265N=true&4kvivid=true&2Kvivid=true&vivid=2"
        
        return requestPlayUrl(PLAYURL_URL + params, headers, rateType, pid)
    }
    
        private suspend fun requestPlayUrl(url: String, headers: Map<String, String>, rateType: Int, pid: String): MiguStreamResult {
        return try {
            doPlayUrlRequest(client, url, headers, rateType, pid)
        } catch (e: Exception) {
            // 老盒子 OpenSSL GCM 套件坏 -> SSLProtocolException; 用 CBC 套件重试
            if (e is javax.net.ssl.SSLException) {
                logger.warning("默认客户端SSL失败(" + e.message + "), 尝试CBC套件重试")
                try {
                    doPlayUrlRequest(cbcClient, url, headers, rateType, pid)
                } catch (e2: Exception) {
                    logger.severe("CBC重试也失败: " + e2.message)
                    MiguStreamResult(url = "", rateType = 0, content = null, error = e2.message)
                }
            } else {
                logger.severe("请求播放地址失败: " + e.message)
                MiguStreamResult(url = "", rateType = 0, content = null, error = e.message)
            }
        }
    }

    private suspend fun doPlayUrlRequest(c: OkHttpClient, url: String, headers: Map<String, String>, rateType: Int, pid: String): MiguStreamResult {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()
        c.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return MiguStreamResult(url = "", rateType = 0, content = null, error = "HTTP " + response.code())
            }
            val body = response.body()?.string() ?: ""
            logger.info("playurl 原始响应(${body.length}B): ${body.take(600)}")
            val json = JSONObject(body)
            // 每次响应都用服务器时间刷新偏移 (盒子时钟漂移, 这样永远自动纠正)
            applyServerTime(json.optLong("timeStamp", 0L))
            val rid = json.optString("rid", "")
            var finalRateType = rateType
            if (rid == "TIPS_NEED_MEMBER") {
                logger.info("无会员，降级画质")
                finalRateType = 3
            }
            val bodyObj = json.optJSONObject("body")
            val urlInfo = bodyObj?.optJSONObject("urlInfo")
            if (urlInfo == null || urlInfo == JSONObject.NULL) {
                val msg = json.optString("message", "")
                val desc = bodyObj?.optString("message", "").orEmpty()
                val detail = "urlInfo=null rid=$rid msg=$msg bodyMsg=$desc"
                logger.severe("播放地址为空: $detail")
                return MiguStreamResult(url = "", rateType = 0, content = json, error = detail)
            }
            val streamUrl = urlInfo.optString("url", "")
            val actualRateType = urlInfo.optInt("rateType", rateType)
            val contId = bodyObj.optJSONObject("content")?.optString("contId") ?: pid
            if (streamUrl.isEmpty()) {
                return MiguStreamResult(url = "", rateType = 0, content = json, error = "获取地址失败")
            }
            val encryptedUrl = addDdCalcu(streamUrl, contId, "android", finalRateType)
            return MiguStreamResult(url = encryptedUrl, rateType = actualRateType, content = json, error = null)
        }
    }

    fun addDdCalcu(puDataURL: String, programId: String, clientType: String, rateType: Int, userId: String = ""): String {
        val puDataSplit = puDataURL.split("&puData=")
        if (puDataSplit.size < 2) return puDataURL
        
        val puData = puDataSplit[1]
        val ddCalcu = generateDdCalcu(puData, programId, clientType, rateType, userId)
        val suffix = if (clientType == "android") ANDROID_SUFFIX else "&sv=10000&ct=www"
        
        return "$puDataURL&ddCalcu=$ddCalcu$suffix"
    }
    
    private fun generateDdCalcu(puData: String, programId: String, clientType: String, rateType: Int, userId: String = ""): String {
        val keys = ANDROID_KEYS
        var word0 = "v"
        val word1 = "a"
        val word2 = "0"
        val word3 = "a"
        
        // 根据 userId 调整 word0
        if (userId.isNotEmpty() && userId.length > 7) {
            val idx = userId[7].toString().toIntOrNull() ?: 0
            if (idx < keys.length) {
                word0 = keys[idx].toString()
            }
        }
        
        // android 标清用 "v"
        if (clientType == "android" && rateType == 2) {
            word0 = "v"
        }
        
        // userId 长度判断
        if (userId.length in 4..8) {
            word0 = "e"
        }
        
        val thirdReplaceIndex = 6
        val puDataLength = puData.length
        val ddCalcu = StringBuilder()
        
        for (i in 0 until puDataLength / 2) {
            ddCalcu.append(puData[puDataLength - i - 1])
            ddCalcu.append(puData[i])
            
            when (i) {
                1 -> ddCalcu.append(word0)
                2 -> {
                    val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
                    // 原始 JS 使用 getDateString(new Date())[2] — 第三位字符
                    if (dateStr.length > 2 && dateStr[2].toString().toIntOrNull() != null) {
                        ddCalcu.append(keys[dateStr[2].toString().toInt()])
                    }
                }
                3 -> {
                    if (programId.isNotEmpty() && programId.length > thirdReplaceIndex) {
                        val ch = programId[thirdReplaceIndex]
                        if (ch.toString().toIntOrNull() != null) {
                            ddCalcu.append(keys[ch.toString().toInt()])
                        }
                    }
                }
                4 -> ddCalcu.append(word3) // word3 = "a", 对应 JS words[3]
            }
        }
        
        return ddCalcu.toString()
    }
}

data class MiguCategory(
    val name: String = "",
    val vomsID: String = "",
    val fitArea: List<String> = emptyList(),
    val dataList: MutableList<MiguChannel> = mutableListOf()
) {
    companion object {
        fun fromJson(json: JSONObject): MiguCategory {
            val fitArea = mutableListOf<String>()
            val fitAreaJson = json.optJSONArray("fitArea")
            if (fitAreaJson != null) {
                for (i in 0 until fitAreaJson.length()) {
                    fitArea.add(fitAreaJson.getString(i))
                }
            }
            
            return MiguCategory(
                name = json.optString("name", ""),
                vomsID = json.optString("vomsID", ""),
                fitArea = fitArea
            )
        }
    }
}

data class MiguChannel(
    val name: String = "",
    val pid: String = "",
    val logo: String = "",
    val groupName: String = ""
) {
    companion object {
        fun fromJson(json: JSONObject): MiguChannel {
            // 从 pics 对象提取 logo
            var logo = json.optString("logo", "")
            if (logo.isEmpty()) {
                val pics = json.optJSONObject("pics")
                if (pics != null) {
                    logo = pics.optString("highResolutionH", pics.optString("lowResolutionH", ""))
                }
            }
            
            return MiguChannel(
                name = json.optString("name", ""),
                pid = json.optString("pID", ""),
                logo = logo
            )
        }
    }
}

data class MiguStreamResult(
    val url: String = "",
    val rateType: Int = 0,
    val content: JSONObject? = null,
    val error: String? = null
) {
    val isSuccess: Boolean get() = url.isNotEmpty() && error == null
}