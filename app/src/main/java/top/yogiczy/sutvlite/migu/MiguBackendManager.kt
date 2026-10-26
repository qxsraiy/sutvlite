package top.yogiczy.sutvlite.migu

import android.content.Context
import android.os.FileUtils
import java.io.File

/**
 * 咪咕后端管理器
 * 管理频道数据、M3U 生成、播放地址获取
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
    
    init {
        cacheDir.mkdirs()
        dataDir.mkdirs()
    }
    
    /**
     * 加载所有频道数据
     */
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
    
    /**
     * 获取频道播放地址
     */
    suspend fun getChannelStreamUrl(pid: String): MiguStreamResult {
        return MiguApi.getStreamUrl720p(pid)
    }
    
    /**
     * 清理缓存
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}