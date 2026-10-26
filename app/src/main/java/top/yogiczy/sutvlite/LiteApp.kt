package top.yogiczy.sutvlite

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.multidex.MultiDex
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.SP
import java.io.File

/**
 * Lite 版 Application: 只初始化 数据层全局 + 宽松TLS + SP + 本地直播代理服务
 */
class LiteApp : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        Globals.appContext = applicationContext
        Globals.cacheDir = cacheDir
        Globals.fileDir = filesDir
        Globals.nativeLibraryDir = File(applicationInfo.nativeLibraryDir)
        Globals.resources = resources
        Globals.deviceName = Build.MODEL
        Globals.deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "Unknown"

        // 宽松证书 + 强制 TLS1.2 (Android 4.4 盒子必需)
        top.yogiczy.mytv.core.data.network.TrustAllSSLSocketFactory

        SP.init(this)
        // 启动本地直播代理服务 (把咪咕 m3u8/TS 转成 127.0.0.1 明文, 避免盒子 TLS 问题)
        top.yogiczy.sutvlite.backend.LiteServer.start(this)
    }
}

/** 开机自启 (设置里可关) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SP.getBoolean("boot_launch", false)) return
        runCatching {
            val i = Intent(context, top.yogiczy.sutvlite.ui.LiveActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context?.startActivity(i)
        }
    }
}