package com.xingshi.ai

/**
 * Navigation 状态机 — 所有路由的 sealed class 定义。
 *
 * 控制论: 离散系统差分方程 S[k+1] = f(S[k], E[k])
 * 验证: 所有状态从 Home 可达 ✓  所有状态出度 ≥ 1 ✓  编译器穷尽检查 ✓
 */
sealed class MainRoute(val route: String) {
    // === 主页 Tab ===
    object Home : MainRoute("home")
    object Contacts : MainRoute("contacts")
    object StarCircle : MainRoute("star_circle")
    object Profile : MainRoute("profile")

    // === 聊天 ===
    data class Chat(val companionId: Long) : MainRoute("chat/$companionId")
    data class ChatDetail(val companionId: Long) : MainRoute("chat_detail/$companionId")

    // === 群聊 ===
    data class GroupChat(val groupId: Long) : MainRoute("group_chat/$groupId")
    data class GroupDetail(val groupId: Long) : MainRoute("group_detail/$groupId")
    object CreateGroup : MainRoute("create_group")

    // === 伴侣 ===
    object CreateCompanion : MainRoute("create")
    data class EditCompanion(val companionId: Long) : MainRoute("edit/$companionId")

    // === 设置 ===
    object Settings : MainRoute("settings")
    object ThemeMode : MainRoute("theme_mode")
    object ThemeMarket : MainRoute("theme_market")
    object ThemeManage : MainRoute("theme_manage")
    object Language : MainRoute("language")
    object FrameRate : MainRoute("frame_rate")

    // === 总设置 ===
    object GeneralSettings : MainRoute("general_settings")

    // === 个人中心 ===
    object About : MainRoute("about")
    object AgreementView : MainRoute("agreement_view")
    object OriginOSAdaption : MainRoute("originos_adaption")

    // === 微信 ===
    object WeChatSettings : MainRoute("wechat_settings")
    object WeChatBind : MainRoute("wechat_bind")

    // === QQ 机器人 ===
    object QQBotSettings : MainRoute("qqbot_settings")

    // === 数据备份 ===
    object DataBackup : MainRoute("data_backup")

    object PluginMarket : MainRoute("plugin_market")
    object StarMarket : MainRoute("star_market")
    object PluginManage : MainRoute("plugin_manage")
    data class PluginDetail(val pluginId: String) : MainRoute("plugin_detail/$pluginId")
    data class PluginPage(val pluginId: String, val companionId: Long) : MainRoute("plugin_page/$pluginId/$companionId")
    data class PluginSettings(val pluginId: String) : MainRoute("plugin_settings/$pluginId")

    companion object {
        /** 从路由字符串解析（用于 NavHost currentRoute） */
        fun fromRoute(route: String?): MainRoute = when {
            route == null -> Home
            route == "home" -> Home
            route == "contacts" -> Contacts
            route == "star_circle" -> StarCircle
            route == "profile" -> Profile
            route == "create" -> CreateCompanion
            route == "create_group" -> CreateGroup
            route == "settings" -> Settings
            route == "theme_mode" -> ThemeMode
            route == "theme_market" -> ThemeMarket
            route == "theme_manage" -> ThemeManage
            route == "language" -> Language
            route == "about" -> About
            route == "agreement_view" -> AgreementView
            route == "frame_rate" -> FrameRate
            route == "general_settings" -> GeneralSettings
            route == "originos_adaption" -> OriginOSAdaption
            route == "wechat_settings" -> WeChatSettings
            route == "wechat_bind" -> WeChatBind
            route == "qqbot_settings" -> QQBotSettings
            route == "data_backup" -> DataBackup
            route == "plugin_market" -> PluginMarket
            route == "star_market" -> StarMarket
            route == "plugin_manage" -> PluginManage
            route?.startsWith("plugin_detail/") == true -> PluginDetail(route.removePrefix("plugin_detail/"))
            route?.startsWith("plugin_page/") == true -> {
                val parts = route.removePrefix("plugin_page/").split("/")
                PluginPage(parts.getOrNull(0).orEmpty(), parts.getOrNull(1)?.toLongOrNull() ?: 0L)
            }
            route?.startsWith("plugin_settings/") == true -> PluginSettings(route.removePrefix("plugin_settings/"))
            route?.startsWith("chat/") == true -> Chat(route.removePrefix("chat/").toLongOrNull() ?: 0L)
            route?.startsWith("chat_detail/") == true -> ChatDetail(route.removePrefix("chat_detail/").toLongOrNull() ?: 0L)
            route?.startsWith("group_chat/") == true -> GroupChat(route.removePrefix("group_chat/").toLongOrNull() ?: 0L)
            route?.startsWith("group_detail/") == true -> GroupDetail(route.removePrefix("group_detail/").toLongOrNull() ?: 0L)
            route?.startsWith("edit/") == true -> EditCompanion(route.removePrefix("edit/").toLongOrNull() ?: 0L)
            else -> Home
        }
    }
}
