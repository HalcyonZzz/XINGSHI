package com.xingshi.ai

 
sealed class MainRoute(val route: String) {
    
    object Home : MainRoute("home")
    object Contacts : MainRoute("contacts")
    object StarCircle : MainRoute("star_circle")
    object Profile : MainRoute("profile")

    
    data class Chat(val companionId: Long) : MainRoute("chat/$companionId")
    data class ChatDetail(val companionId: Long) : MainRoute("chat_detail/$companionId")

    
    data class GroupChat(val groupId: Long) : MainRoute("group_chat/$groupId")
    data class GroupDetail(val groupId: Long) : MainRoute("group_detail/$groupId")
    object CreateGroup : MainRoute("create_group")

    
    object CreateCompanion : MainRoute("create")
    data class EditCompanion(val companionId: Long) : MainRoute("edit/$companionId")

    
    object Settings : MainRoute("settings")
    object ThemeMode : MainRoute("theme_mode")
    object ThemeMarket : MainRoute("theme_market")
    object ThemeManage : MainRoute("theme_manage")
    object Language : MainRoute("language")
    object FrameRate : MainRoute("frame_rate")

    
    object GeneralSettings : MainRoute("general_settings")

    
    object About : MainRoute("about")
    object AgreementView : MainRoute("agreement_view")
    object OriginOSAdaption : MainRoute("originos_adaption")

    
    object WeChatSettings : MainRoute("wechat_settings")
    object WeChatBind : MainRoute("wechat_bind")

    
    object QQBotSettings : MainRoute("qqbot_settings")

    
    object DataBackup : MainRoute("data_backup")

    object PluginMarket : MainRoute("plugin_market")
    object StarMarket : MainRoute("star_market")
    object PluginManage : MainRoute("plugin_manage")
    data class PluginDetail(val pluginId: String) : MainRoute("plugin_detail/$pluginId")
    data class PluginPage(val pluginId: String, val companionId: Long) : MainRoute("plugin_page/$pluginId/$companionId")
    data class PluginSettings(val pluginId: String) : MainRoute("plugin_settings/$pluginId")

    companion object {
         
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
