package top.yogiczy.sutvlite.data

import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.sutvlite.migu.MiguCategory

/**
 * 极简本地设置: 只保留 分组屏蔽 / 频道屏蔽
 */
object Prefs {
    private const val K_BLOCK_GROUPS = "block_groups_v1"
    private const val K_BLOCK_CHANNELS = "block_channels_v1"

    fun blockedGroups(): Set<String> = SP.getStringSet(K_BLOCK_GROUPS, emptySet()).toSet()
    fun setBlockedGroups(s: Set<String>) = SP.putStringSet(K_BLOCK_GROUPS, s)

    /** 按 pid 屏蔽 */
    fun blockedChannels(): Set<String> = SP.getStringSet(K_BLOCK_CHANNELS, emptySet()).toSet()
    fun setBlockedChannels(s: Set<String>) = SP.putStringSet(K_BLOCK_CHANNELS, s)

    fun toggleGroup(name: String): Boolean {
        val cur = blockedGroups().toMutableSet()
        val now = if (name in cur) { cur.remove(name); false } else { cur.add(name); true }
        setBlockedGroups(cur)
        return now
    }

    fun toggleChannel(pid: String): Boolean {
        val cur = blockedChannels().toMutableSet()
        val now = if (pid in cur) { cur.remove(pid); false } else { cur.add(pid); true }
        setBlockedChannels(cur)
        return now
    }

    /** 应用屏蔽 */
    fun filter(list: List<MiguCategory>): List<MiguCategory> {
        val bg = blockedGroups()
        val bc = blockedChannels()
        if (bg.isEmpty() && bc.isEmpty()) return list
        return list
            .filter { it.name !in bg }
            .map { cat -> cat.copy(dataList = cat.dataList.filter { it.pid !in bc }.toMutableList()) }
            .filter { it.dataList.isNotEmpty() }
    }
}