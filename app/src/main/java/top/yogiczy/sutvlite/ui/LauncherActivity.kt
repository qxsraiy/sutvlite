package top.yogiczy.sutvlite.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import top.yogiczy.sutvlite.Logx

/**
 * 桌面入口 (HOME) —— 默认在 AndroidManifest 里 enabled="false"。
 *
 * 重要: 这里**不能**继承 LiveActivity。
 * 之前继承 LiveActivity 时, 作为桌面的实例会和已存在的 LiveActivity 同时存活,
 * 两个 ExoPlayer 抢海思硬件解码器 -> 整机卡死。
 * 所以现在它只是一个"空壳转发": 启动 LiveActivity(singleTask, 复用已有实例) 后立即结束自己。
 */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.mark("LauncherActivity.onCreate (HOME 被按下)")
        // singleTask + CLEAR_TOP: 复用已在运行的直播实例, 不会产生第二个播放器
        val i = Intent(this, LiveActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(i) }
            .onFailure { Logx.e("Launcher", "转发 LiveActivity 失败", it) }
        finish()
        overridePendingTransition(0, 0)
    }
}