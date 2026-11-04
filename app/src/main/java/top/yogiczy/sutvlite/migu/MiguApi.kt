package top.yogiczy.sutvlite.migu

import top.yogiczy.sutvlite.AppClock
import top.yogiczy.sutvlite.Logx

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
            .dns(top.yogiczy.sutvlite.backend.PublicDns)
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
    
    /** 码率降级链: 720P(3) -> 高清(2) -> 标清(1); 某个码率/节点不可用时依次降级 */
    private val RATE_CHAIN = intArrayOf(3, 2, 1)

    /** 预热: 校时 (HTTP Date) 并让 TLS 栈完成初始化, 之后首次请求快很多 */
    suspend fun warmUp() {
        runCatching { nowMs() }
        runCatching { fetchCategories() }
    }

    suspend fun getStreamUrl720p(pid: String): MiguStreamResult {
        var last: MiguStreamResult? = null
        for (rate in RATE_CHAIN) {
            val r = tryRate(pid, rate)
            if (r.isSuccess && r.url.isNotEmpty()) return r
            last = r
            // 只有"取不到流"才降码率; 时间戳类错误已在 tryRate 内处理
            if (!r.isSuccess) continue
        }
        return last ?: MiguStreamResult(url = "", rateType = 0, content = null, error = "未知错误")
    }

    /** 指定码率取流 (供代理在清单不可用时降码率重试) */
    suspend fun getStreamUrlWithRate(pid: String, rate: Int): MiguStreamResult = tryRate(pid, rate)

    /** 单个码率: 最多 2 次 (第二次针对时间戳过期重建签名) */
    private suspend fun tryRate(pid: String, rate: Int): MiguStreamResult {
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

            val params = "?sign=$sign&rateType=$rate&contId=$pid&timestamp=$timestamp&salt=$salt" +
                    "&flvEnable=true&super4k=true&h265N=false&4kvivid=false&2Kvivid=false&vivid=0"

            val res = requestPlayUrl(PLAYURL_URL + params, headers, rate, pid)
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
    /** 2020-01-01; 早于此说明设备时钟不可信 */
    private val SANE_MS = 1_577_836_800_000L
    @Volatile private var lastLoggedOffset = Long.MIN_VALUE

    /** 设备墙钟是否明显不对 (盒子无 RTC, 开机初期常停在固件日期 2016) */
    private fun deviceClockInsane(): Boolean = System.currentTimeMillis() < SANE_MS

    private fun nowMs(): Long {
        // 统一走 AppClock (应用自带时钟): 开机墙钟错误时也能给出正确的签名时间戳
        if (!AppClock.isCalibrated()) AppClock.calibrate()
        val t = AppClock.now()
        return t
    }

    @Suppress("unused")
    private fun nowMsLegacy(): Long {
        // 关键: 开机初期墙钟可能还是 2016, 此时必须用(持久化的)服务器偏移,
        // 否则签名里的 timestamp 是 2016 -> 咪咕返回的流地址被 CDN 拒绝(661),
        // 表现为"第一次死活打不开, 退出重进就好了"(重进时系统时间已校准)
        var cached = timeOffsetMs ?: restoreOffset()
        if (cached != null && timeOffsetMs == null) timeOffsetMs = cached
        val stale = cached == null || System.currentTimeMillis() - lastSyncAt > SYNC_TTL_MS
        val offset = if (stale) syncTimeOffset() else (cached ?: 0L)
        val signedNow = System.currentTimeMillis() + offset
        Logx.i(
            "校时",
            "设备=${fmtNow(System.currentTimeMillis())} 偏移=${offset / 86_400_000}天 " +
                "签名=${fmtNow(signedNow)} 墙钟异常=${deviceClockInsane()}"
        )
        return signedNow
    }

    private fun fmtNow(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    /** 恢复上次成功校准的偏移 (仅在设备墙钟异常时使用, 避免墙钟跳变后算错) */
    private fun restoreOffset(): Long? = try {
        val saved = top.yogiczy.mytv.core.data.utils.SP.getLong("migu_offset_ms", Long.MIN_VALUE)
        when {
            saved == Long.MIN_VALUE || saved == 0L -> null
            deviceClockInsane() -> {
                Logx.w("校时", "墙钟异常, 使用上次校准偏移 ${saved / 86_400_000}天")
                saved
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun persistOffset(offset: Long) {
        runCatching { top.yogiczy.mytv.core.data.utils.SP.putLong("migu_offset_ms", offset) }
    }

    /** 用服务器给的时间戳刷新偏移 (响应里的 timeStamp 是权威时间) */
    private fun applyServerTime(serverMs: Long) {
        if (serverMs <= 1_000_000_000_000L) return
        val offset = serverMs - System.currentTimeMillis()
        timeOffsetMs = offset
        lastSyncAt = System.currentTimeMillis()
        persistOffset(offset)
        logger.info("按响应校准时钟偏移: ${offset}ms (${offset / 3_600_000}h)")
        Logx.i("校时", "响应校准偏移 ${offset / 86_400_000}天")
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
                if (serverMs != null) {
                    persistOffset(offset)
                    runCatching {
                        top.yogiczy.mytv.core.data.utils.SP.putLong("migu_server_ms", serverMs)
                    }
                }
                Logx.i("校时", "HTTP Date 校准偏移 ${offset / 86_400_000}天")
                offset
            }
        } catch (t: Throwable) {
            logger.warning("时间校准失败: ${t.message}")
            Logx.w("校时", "HTTP Date 校准失败: ${t.message}")
            // 校准失败时: 若墙钟异常, 尽量用持久化偏移兜底
            timeOffsetMs ?: restoreOffset() ?: 0L
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
    /** 序列化 (频道列表本地缓存用) */
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("vomsID", vomsID)
        put("fitArea", org.json.JSONArray(fitArea))
        put("channels", org.json.JSONArray().apply {
            dataList.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): MiguCategory {
            val fitArea = mutableListOf<String>()
            json.optJSONArray("fitArea")?.let { arr ->
                for (i in 0 until arr.length()) fitArea.add(arr.getString(i))
            }
            return MiguCategory(
                name = json.optString("name", ""),
                vomsID = json.optString("vomsID", ""),
                fitArea = fitArea
            )
        }

        /** 从本地缓存反序列化 */
        fun fromCacheJson(json: JSONObject): MiguCategory {
            val fitArea = mutableListOf<String>()
            json.optJSONArray("fitArea")?.let { arr ->
                for (i in 0 until arr.length()) fitArea.add(arr.getString(i))
            }
            val cat = MiguCategory(
                name = json.optString("name", ""),
                vomsID = json.optString("vomsID", ""),
                fitArea = fitArea,
            )
            json.optJSONArray("channels")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { cat.dataList.add(MiguChannel.fromCacheJson(it)) }
                }
            }
            return cat
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

        fun fromCacheJson(json: JSONObject): MiguChannel = MiguChannel(
            name = json.optString("name", ""),
            pid = json.optString("pid", ""),
            logo = json.optString("logo", ""),
            groupName = json.optString("groupName", ""),
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("pid", pid)
        put("logo", logo)
        put("groupName", groupName)
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