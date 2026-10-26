package top.yogiczy.sutvlite.root

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.SP

/**
 * Root 动作：安装/移除系统应用 + OOM 防清理 + 锁定桌面 + 诊断。
 * 所有函数必须在 IO 线程调用；返回值为给用户看的结果文案。
 */
object RootActions {

    private const val TAG = "RootActions"
    private const val SP_DISABLED_LAUNCHERS = "disabled_launchers"

    private fun ctx() = Globals.appContext
    private fun ourPkg() = ctx().packageName
    private fun su(cmd: String, timeout: Long = 20000) = RootManager.execRoot(cmd, timeout)

    private fun ok(cmd: String, timeout: Long = 20000): Boolean = su(cmd, timeout)?.success == true

    private fun systemsDir(): String = "/system/priv-app/${ourPkg()}"
    private fun systemApk(): String = "${systemsDir()}/${ourPkg()}.apk"

    /** 让 /system 可写：多种 remount 语法逐个尝试，并用写文件真实校验 */
    private fun remountSystemRw(): String? {
        val variants = listOf(
            "mount -o rw,remount /system",
            "mount -o remount,rw /system",
            "mount -o rw,remount -t yaffs2 /dev/block/mtdblock3 /system",
            "mount -o rw,remount /",
        )
        for (v in variants) {
            su(v, 15000)
            // 真实可写校验
            val test = su("touch /system/.rwtest_${System.nanoTime()} && rm -f /system/.rwtest_* && echo RWOK", 12000)
            if (test?.output?.contains("RWOK") == true) return null
        }
        // 部分盒子 rootfs 只读, 但 /system 是独立分区, 用重新挂载整分区兜底
        su("mount -o rw,remount /dev/block/mmcblk0p12 /system", 12000)
        return "无法将 /system 挂载为可写（该机型可能锁死系统分区）"
    }

    // ─── 系统应用状态 ───

    fun isSystemApp(): Boolean {
        val src = ctx().applicationInfo.sourceDir
        if (src.startsWith("/system/")) return true
        if (!RootManager.isGranted) return false
        return ok("test -f ${systemApk()} && echo YES")
    }

    // ─── 安装为系统应用 ───

    fun installAsSystemApp(): String {
        val src = ctx().applicationInfo.sourceDir
        if (src.startsWith("/system/")) return "已是系统应用（运行于 $src）"
        if (!RootManager.isGranted && !RootManager.requestRoot()) return "失败: 未获得 root 权限"
        if (!ok("test -f $src && echo YES")) return "失败: 找不到当前 APK: $src"

        remountSystemRw()?.let { return "失败: $it" }

        val dir = systemsDir()
        val dest = systemApk()

        if (!ok("mkdir -p $dir")) return "失败: 创建 $dir 失败"
        ok("chmod 755 $dir")

        // 复制（cp 失败时用 cat 兜底）
        var copied = ok("cp -f \"$src\" \"$dest\"") || ok("cat \"$src\" > \"$dest\"")
        if (!copied) return "失败: 复制 APK 到 $dest 失败"

        ok("chmod 644 \"$dest\"")
        ok("chown 0:0 \"$dest\"")
        ok("chmod 755 $dir")
        ok("sync", 30000)

        // 校验大小一致
        val srcSize = su("stat -c %s \"$src\" 2>/dev/null || wc -c < \"$src\"")?.output?.trim()
        val dstSize = su("stat -c %s \"$dest\" 2>/dev/null || wc -c < \"$dest\"")?.output?.trim()
        val sizeOk = !srcSize.isNullOrBlank() && srcSize == dstSize

        return if (sizeOk) {
            "安装完成 ✅  APK 已复制到 $dest（${srcSize} 字节）\n请重启盒子生效；重启后可在应用管理里删除原数据版"
        } else {
            "复制完成但大小校验不一致(src=$srcSize dst=$dstSize)，请重启后确认是否生效"
        }
    }

    fun removeFromSystem(): String {
        if (!RootManager.isGranted && !RootManager.requestRoot()) return "失败: 未获得 root 权限"
        val dir = systemsDir()
        if (!ok("test -d $dir && echo YES")) return "当前没有系统副本"

        remountSystemRw()?.let { return "失败: $it" }
        if (!ok("rm -rf \"$dir\"")) return "失败: 删除 $dir 失败"
        ok("sync", 30000)

        return if (!ok("test -d $dir && echo YES")) {
            "已移除系统副本 ✅  重启后恢复为普通应用"
        } else {
            "删除后仍存在，可能被系统保护，请重启后再试"
        }
    }

    // ─── OOM 防清理 ───

    fun setOomPriority(enable: Boolean): String {
        if (!RootManager.isGranted && !RootManager.requestRoot()) return "失败: 未获得 root 权限"
        val pid = Process.myPid()
        val adj = if (enable) "-17" else "0"
        val scoreAdj = if (enable) "-1000" else "0"
        val r1 = su("echo $adj > /proc/$pid/oom_adj")
        val r2 = su("echo $scoreAdj > /proc/$pid/oom_score_adj")
        val now = su("cat /proc/$pid/oom_adj 2>/dev/null")?.output?.trim()
        return when {
            r1?.success == true || r2?.success == true ->
                if (enable) "进程守护已开启（当前 oom_adj=$now）" else "进程守护已关闭"
            else -> "设置失败: ${r1?.output ?: ""} ${r2?.output ?: ""}".trim()
        }
    }

    fun checkOomPriority(): Boolean {
        if (!RootManager.isGranted) return false
        val pid = Process.myPid()
        val v = su("cat /proc/$pid/oom_adj 2>/dev/null")?.output?.trim()?.toIntOrNull()
        return v != null && v < 0
    }

    // ─── 锁定为桌面 (桌面身份 = LauncherActivity, 默认禁用) ───

    private fun homeComp() = android.content.ComponentName(
        ctx(), top.yogiczy.sutvlite.ui.LauncherActivity::class.java
    )

    /** 我们的桌面入口组件当前是否启用 (启用 = 参与 HOME 竞争) */
    fun isHomeComponentEnabled(): Boolean = try {
        ctx().packageManager.getActivityInfo(homeComp(), 0)
        true
    } catch (_: Throwable) {
        false
    }

    /** 开关状态 = 桌面入口是否启用 */
    fun isDefaultLauncher(): Boolean = isHomeComponentEnabled()

    /** 当前系统解析到的默认桌面是不是我们自己 */
    fun isResolvedHome(): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = ctx().packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ri?.activityInfo?.packageName == ourPkg()
    } catch (_: Throwable) {
        false
    }

    private fun setHomeComponent(enabled: Boolean): Boolean = try {
        ctx().packageManager.setComponentEnabledSetting(
            homeComp(),
            if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        true
    } catch (t: Throwable) {
        android.util.Log.e("RootActions", "切换桌面组件失败: ${t.message}")
        false
    }

    /**
     * 开启: 启用我们的桌面入口组件 (无需 root) -> 成为桌面候选
     * 若能拿到 root, 再尝试直接指为默认 + 禁用其它桌面, 保证"开了就是默认桌面"
     */
    fun setAsDefaultLauncher(): String {
        if (!setHomeComponent(true)) return "失败: 无法启用桌面组件"
        Thread.sleep(300)

        // 没有 root 也能"成为候选"; 有 root 则进一步锁定
        if (!RootManager.isGranted && !RootManager.requestRoot()) {
            return if (isResolvedHome()) "已设为桌面 ✅" else "已启用桌面入口，按 HOME 键选择本应用"
        }

        su("pm set-home-activity ${homeComp().flattenToString()}", 15000)
        Thread.sleep(300)
        if (isResolvedHome()) return "已设为默认桌面 ✅"

        val pm = ctx().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val others = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .filter { it != ourPkg() }
            .distinct()
        val disabledNow = mutableListOf<String>()
        for (pkg in others) {
            val s1 = su("pm disable $pkg", 12000)
            val s2 = if (s1?.success != true) su("pm disable-user $pkg", 12000) else s1
            val s3 = if (s2?.success != true) su("pm disable-user --user 0 $pkg", 12000) else s2
            if (s3?.success == true) disabledNow.add(pkg)
        }
        if (disabledNow.isNotEmpty()) SP.putString(SP_DISABLED_LAUNCHERS, disabledNow.joinToString(","))

        Thread.sleep(600)
        return when {
            isResolvedHome() -> "已设为默认桌面 ✅（已禁用其它桌面: ${disabledNow.joinToString()}）"
            disabledNow.isNotEmpty() -> "已启用桌面入口并禁用其它桌面(${disabledNow.joinToString()})，按 HOME 键确认"
            else -> "已启用桌面入口，按 HOME 键选择本应用为桌面"
        }
    }

    /**
     * 关闭: 先恢复其它桌面 (有 root 才需要), 再禁用我们自己的桌面入口
     * -> 本 app 立刻失去 HOME 身份, 变回普通应用
     */
    fun restoreDefaultLauncher(): String {
        // 1) 先恢复之前被我们禁用的桌面, 避免系统一下子没有桌面
        var restored = 0
        val recorded = SP.getString(SP_DISABLED_LAUNCHERS, "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (recorded.isNotEmpty() && RootManager.isGranted) {
            for (pkg in recorded) {
                if (su("pm enable $pkg", 12000)?.success == true) restored++
            }
            SP.putString(SP_DISABLED_LAUNCHERS, "")
        }

        // 2) 禁用我们自己的桌面入口 -> 变回普通 app
        val ok = setHomeComponent(false)
        Thread.sleep(300)

        return when {
            !ok -> "失败: 无法禁用桌面组件"
            restored > 0 -> "已取消桌面身份 ✅（恢复 $restored 个桌面应用），现在是普通应用"
            else -> "已取消桌面身份 ✅，现在是普通应用（按 HOME 键回系统桌面）"
        }
    }

    // ─── 诊断 ───

    fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("su 路径: ${RootManager.suBinary() ?: "未找到"}")
        sb.appendLine("已授权: ${RootManager.isGranted}")
        sb.appendLine("id: ${su("id")?.output ?: "执行失败"}")
        sb.appendLine("APK: ${ctx().applicationInfo.sourceDir}")
        sb.appendLine("系统副本: ${if (isSystemApp()) systemApk() else "无"}")
        val mountLine = su("mount | grep -E ' /system ' ")?.output ?: ""
        sb.appendLine("system挂载: ${mountLine.take(120)}")
        val home = su("dumpsys package | grep -A1 'android.intent.category.HOME' | head -5")?.output ?: ""
        if (home.isNotBlank()) sb.appendLine("HOME候选: ${home.replace("\n", " ").take(160)}")
        sb.appendLine("桌面组件启用: ${isHomeComponentEnabled()}")
        sb.appendLine("系统默认桌面是本应用: ${isResolvedHome()}")
        return sb.toString().trim()
    }
}