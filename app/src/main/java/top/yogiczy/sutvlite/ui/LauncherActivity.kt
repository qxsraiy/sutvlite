package top.yogiczy.sutvlite.ui

/**
 * 桌面入口 (HOME) —— 默认在 AndroidManifest 里 enabled="false"。
 *
 * 设计要点:
 *  - 桌面身份与「普通启动入口」彻底分离: 只有本 Activity 参与 HOME,
 *    LiveActivity 只做 LAUNCHER(应用图标) 入口
 *  - 默认禁用 -> 装上就是普通 app, 永远不会被系统自动选成默认桌面
 *  - 设置里的「锁定为桌面」开关只做一件事: enable / disable 本组件
 *    (PackageManager.setComponentEnabledSetting, 自己的组件无需 root)
 *  - 关闭时它会重新变成普通 app, 系统桌面回落到其它 launcher
 *
 * 内容直接继承 LiveActivity: 成为桌面时按 HOME 就是直播画面。
 */
class LauncherActivity : LiveActivity()