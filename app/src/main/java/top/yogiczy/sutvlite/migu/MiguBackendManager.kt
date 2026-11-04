package top.yogiczy.sutvlite.migu

import android.content.Context
import org.json.JSONArray
import top.yogiczy.sutvlite.Logx
import java.io.File

/**
 * 咪咕频道数据管理 + **本地缓存**
 *
 * 冷启动优化:
 *   开机/首次打开时先读缓存 (毫秒级, 立即出画面),
 *   后台再拉网络刷新 (10+ 个分组的 HTTPS 请求在老盒子上要十几秒)。
 */
class MiguBackendManager(private val context: Context) {

    companion object {
        private const val TAG = "MiguBackendManager"
        private var instance: MiguBackendManager? = null

        fun getInstance(context: Context): MiguBackendManager {
            return instance ?: MiguBackendManager(context.applicationContext).also { instance = it }
        }
    }

    private val cacheDir: File = File(context.cacheDir, "migu")
    private val dataDir: File = File(context.filesDir, "migu")
    private val channelCacheFile: File = File(dataDir, "channels_v1.json")

    init {
        cacheDir.mkdirs()
        dataDir.mkdirs()
    }

    /** 网络拉取全部频道 (慢, 10+ 请求) */
    suspend fun loadAllChannels(): List<MiguCategory> {
        val categories = MiguApi.fetchCategories()

        for (category in categories) {
            if (category.vomsID.isNotEmpty()) {
                val channels = MiguApi.fetchChannels(category.vomsID)
                category.dataList.clear()
                category.dataList.addAll(channels)
            }
        }

        return categories
    }

    /** 网络拉取 + 成功后写缓存 */
    suspend fun loadAllChannelsAndCache(): List<MiguCategory> {
        val list = loadAllChannels()
        if (list.isNotEmpty() && list.any { it.dataList.isNotEmpty() }) {
            saveCache(list)
        }
        return list
    }

    // ==================== 本地缓存 ====================

    /** 读缓存 (同步, 毫秒级); 没有缓存返回空 */
    fun loadCache(): List<MiguCategory> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        return try {
            if (!channelCacheFile.exists()) return emptyList()
            val text = channelCacheFile.readText()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            val out = ArrayList<MiguCategory>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(MiguCategory.fromCacheJson(it)) }
            }
            Logx.i(
                "缓存",
                "频道缓存命中: ${out.size} 组 ${out.sumOf { it.dataList.size }} 台, " +
                    "耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms, " +
                    "文件 ${channelCacheFile.length() / 1024}KB"
            )
            out
        } catch (t: Throwable) {
            Logx.w("缓存", "读取失败: ${t.message}")
            emptyList()
        }
    }

    fun saveCache(list: List<MiguCategory>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            // 先写临时文件再 rename, 避免写一半掉电导致缓存损坏
            val tmp = File(dataDir, "channels_v1.json.tmp")
            tmp.writeText(arr.toString())
            if (channelCacheFile.exists()) channelCacheFile.delete()
            if (!tmp.renameTo(channelCacheFile)) {
                channelCacheFile.writeText(arr.toString())
                tmp.delete()
            }
            Logx.i(
                "缓存",
                "频道缓存已保存: ${list.size} 组 ${list.sumOf { it.dataList.size }} 台, " +
                    "${channelCacheFile.length() / 1024}KB"
            )
        } catch (t: Throwable) {
            Logx.w("缓存", "保存失败: ${t.message}")
        }
    }

    fun hasCache(): Boolean = channelCacheFile.exists() && channelCacheFile.length() > 1024

    fun clearCache() {
        runCatching { channelCacheFile.delete() }
    }
}