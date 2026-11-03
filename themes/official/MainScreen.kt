package com.xingshi.ai

import com.xingshi.ai.themes.official.R

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xingshi.ai.common.AppForegroundTracker
import com.xingshi.ai.common.BatteryOptimizationHelper
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.feature.chat.ui.screen.ChatDetailScreen
import com.xingshi.ai.feature.chat.ui.screen.ChatScreen
import com.xingshi.ai.feature.companion.ui.screen.ContactsScreen
import com.xingshi.ai.feature.companion.ui.screen.CreateCompanionScreen
import com.xingshi.ai.feature.groupchat.ui.CreateGroupScreen
import com.xingshi.ai.feature.groupchat.ui.GroupChatScreen
import com.xingshi.ai.feature.groupchat.ui.GroupDetailScreen
import com.xingshi.ai.feature.moments.ui.StarCircleScreen
import com.xingshi.ai.database.viewmodel.ChatGroupViewModel
import com.xingshi.ai.feature.profile.*
import com.xingshi.ai.feature.settings.ui.screen.*
import com.xingshi.ai.feature.backup.BackupScreen
import com.xingshi.ai.plugins.PluginFramework
import com.xingshi.ai.plugins.ui.PluginDetailScreen
import com.xingshi.ai.plugins.ui.PluginManageScreen
import com.xingshi.ai.plugins.ui.PluginMarketScreen
import com.xingshi.ai.plugins.ui.PluginPageHost
import com.xingshi.ai.plugins.ui.PluginSettingsHost
import com.xingshi.ai.uicommon.theme.XingShiTheme
import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePages
import com.xingshi.ai.theme.framework.ThemeRuntime
import com.xingshi.ai.theme.framework.rememberThemeState

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.WbTwilight
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.xingshi.ai.theme.framework.themeText

 
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mainActivity: Activity) {
    val navController = rememberNavController()
    
    val recoveryMode by ThemeRuntime.recoveryMode.collectAsStateWithLifecycle()
    var recoveryEnteredManage by remember { mutableStateOf(false) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    LaunchedEffect(currentRoute, recoveryMode) {
        if (!recoveryMode) return@LaunchedEffect
        if (currentRoute == MainRoute.ThemeManage.route) {
            recoveryEnteredManage = true
        } else if (recoveryEnteredManage) {
            ThemeRuntime.exitRecovery()
            recoveryEnteredManage = false
        } else if (currentRoute != null) {
            navController.navigate(MainRoute.ThemeManage.route) { launchSingleTop = true }
        }
    }

    val context = LocalContext.current
    val valActivity = context as ComponentActivity

    fun openCompanionChat(companionId: Long) {
        LastOpenedCompanionStore.save(context, companionId)
        navController.navigate(MainRoute.Chat(companionId).route)
    }

    
    LaunchedEffect(Unit) {
        val intent = valActivity.intent
        if (intent.getBooleanExtra("open_chat", false)) {
            val companionId = intent.getLongExtra("companion_id", -1L)
            if (companionId != -1L) {
                openCompanionChat(companionId)
                intent.removeExtra("open_chat")
                intent.removeExtra("companion_id")
                return@LaunchedEffect
            }
        }

        val restoredCompanionId = LastOpenedCompanionStore.resolveInitialCompanionId(
            context,
            ServiceRegistry.getOrThrow(com.xingshi.ai.database.repository.CompanionRepository::class.java)
        )
        if (restoredCompanionId != null) {
            openCompanionChat(restoredCompanionId)
        }
    }

    
    LaunchedEffect(Unit) {
        com.xingshi.ai.common.AppIntentRouter.incoming.collect { intent ->
            if (intent.getBooleanExtra("open_chat", false)) {
                val companionId = intent.getLongExtra("companion_id", -1L)
                if (companionId != -1L) {
                    openCompanionChat(companionId)
                }
            }
            intent.removeExtra("open_chat")
            intent.removeExtra("companion_id")
        }
    }

    
    MainScreenDialogs(
        onAutoStartSettings = { BatteryOptimizationHelper.openAutoStartSettings(context) },
        onBatterySettings = { BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context) }
    )

    val themeState = rememberThemeState()
    val defaultNavItems = listOf(
        BottomNavItem(themeText("nav_love"), Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, "home"),
        BottomNavItem(themeText("nav_star_circle"), Icons.Filled.WbTwilight, Icons.Outlined.WbTwilight, "star_circle"),
        BottomNavItem(themeText("nav_profile"), Icons.Filled.Person, Icons.Outlined.PersonOutline, "profile")
    )
    val navigationSpec = remember(themeState.revision) { ThemeRuntime.navigation() }
    val bottomNavItems = if (navigationSpec.items.isEmpty()) defaultNavItems else {
        val order = navigationSpec.items.map { if (it.route == "contacts") "star_circle" else it.route }
        val reordered = order.mapNotNull { route -> defaultNavItems.firstOrNull { it.route == route } }
        if (reordered.size == defaultNavItems.size && reordered.map { it.route }.toSet() == defaultNavItems.map { it.route }.toSet()) reordered else defaultNavItems
    }
    val navPosition = navigationSpec.position
    val mainTabRoutes = bottomNavItems.map { it.route }
    val showBottomBar = currentRoute in mainTabRoutes

    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    
    var lastTabPage by rememberSaveable { mutableIntStateOf(0) }

    val pageRoutes = listOf("home", "star_circle", "profile")
    val currentTabRoute = pageRoutes.getOrElse(pagerState.currentPage) { "home" }
    val selectTab: (String) -> Unit = { route ->
        val page = pageRoutes.indexOf(route).takeIf { it >= 0 } ?: 0
        lastTabPage = page
        if (currentRoute != MainRoute.Home.route) {
            navController.navigate(MainRoute.Home.route) {
                popUpTo(MainRoute.Home.route) { inclusive = false }
                launchSingleTop = true
            }
        }
        coroutineScope.launch { pagerState.animateScrollToPage(page) }
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute !in mainTabRoutes) {
            
            lastTabPage = pagerState.currentPage
            return@LaunchedEffect
        }
        
        val targetPage = when (currentRoute) {
            "star_circle" -> 1
            "profile" -> 2
            else -> lastTabPage
        }
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    
    val angleNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity.Zero
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showBottomBar && navPosition == "top") {
                FloatingGlassBottomNav(
                    items = bottomNavItems,
                    currentIndex = bottomNavItems.indexOfFirst { it.route == currentTabRoute }.coerceAtLeast(0),
                    position = "top",
                    onItemClick = { index -> selectTab(bottomNavItems[index].route) }
                )
            }
        },
        bottomBar = {
            if (showBottomBar && navPosition != "top") {
                FloatingGlassBottomNav(
                    items = bottomNavItems,
                    currentIndex = bottomNavItems.indexOfFirst { it.route == currentTabRoute }.coerceAtLeast(0),
                    position = "bottom",
                    onItemClick = { index -> selectTab(bottomNavItems[index].route) }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
                enterTransition = {
                    fadeIn(animationSpec = tween(250)) + slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it / 4 })
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(250), targetOffsetX = { -it / 6 })
                },
                popEnterTransition = {
                    fadeIn(animationSpec = tween(250)) + slideInHorizontally(animationSpec = tween(300), initialOffsetX = { -it / 4 })
                },
                popExitTransition = {
                    fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(250), targetOffsetX = { it / 4 })
                }
            ) {
                
                composable(MainRoute.Home.route) {
                    val pagerOffset by remember { derivedStateOf { pagerState.currentPageOffsetFraction } }
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = 0.4f),
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(pagerState) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var totalDragX = 0f
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { change ->
                                            totalDragX += change.position.x - change.previousPosition.x
                                        }
                                        if (abs(totalDragX) > size.width * 0.6f) {
                                            val target = (pagerState.currentPage + if (totalDragX < 0) 1 else -1).coerceIn(0, pagerState.pageCount - 1)
                                            coroutineScope.launch { pagerState.animateScrollToPage(target) }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .nestedScroll(angleNestedScrollConnection)
                    ) { page ->
                        val cp = pagerState.currentPage
                        val visible = remember(page, cp, pagerOffset) {
                            when {
                                page == cp -> pagerOffset in -0.6f..0.6f
                                page == cp + 1 -> pagerOffset > 0.3f
                                page == cp - 1 -> pagerOffset < -0.3f
                                else -> false
                            }
                        }
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(animationSpec = tween(350)) + scaleIn(initialScale = 0.94f, animationSpec = tween(350)),
                            exit = fadeOut(animationSpec = tween(200))
                        ) {
                            when (page) {
                                0 -> {
                                    val codeUi = ThemeRuntime.codeUi()
                                    if (codeUi == null) {
                                        HomeScreen(
                                            onCompanionClick = { openCompanionChat(it) },
                                            onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                                            onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                                            onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) },
                                        )
                                    } else {
                                        val homeViewModel: HomeViewModel = viewModel()
                                        val groupViewModel: ChatGroupViewModel = viewModel()
                                        val chatList by homeViewModel.chatList.collectAsStateWithLifecycle(initialValue = emptyList())
                                        val groups by groupViewModel.groups.collectAsStateWithLifecycle(initialValue = emptyList())
                                        val host = remember(chatList, groups) {
                                            ThemePageHost(
                                                page = ThemePages.HOME,
                                                items = chatList.map { item ->
                                                    ThemePageItem(
                                                        id = item.companion.id.toString(),
                                                        kind = "companion",
                                                        title = item.companion.name,
                                                        subtitle = item.lastMessage?.content ?: "",
                                                        avatar = item.companion.avatarUrl.orEmpty()
                                                    )
                                                } + groups.map { group ->
                                                    ThemePageItem(
                                                        id = group.id.toString(),
                                                        kind = "group",
                                                        title = group.name,
                                                        subtitle = "",
                                                        avatar = group.avatarUrl.orEmpty()
                                                    )
                                                },
                                                state = mapOf(
                                                    "session_count" to chatList.size.toString(),
                                                    "group_count" to groups.size.toString()
                                                ),
                                                dispatch = { action ->
                                                    when (action) {
                                                        is ThemePageAction.Invoke -> when (action.id) {
                                                            "open_chat" -> action.payload["id"]?.toLongOrNull()
                                                                ?.let { openCompanionChat(it) }
                                                            "open_group" -> action.payload["id"]?.toLongOrNull()
                                                                ?.let { navController.navigate(MainRoute.GroupChat(it).route) }
                                                            "create_assistant" -> navController.navigate(MainRoute.CreateCompanion.route)
                                                            "create_group" -> navController.navigate(MainRoute.CreateGroup.route)
                                                            "open_profile" -> selectTab("profile")
                                                            "open_star_circle" -> selectTab("star_circle")
                                                            "open_settings" -> navController.navigate(MainRoute.Settings.route)
                                                        }
                                                        is ThemePageAction.Open -> when (action.route) {
                                                            ThemePages.PROFILE -> selectTab("profile")
                                                            ThemePages.STAR_CIRCLE -> selectTab("star_circle")
                                                            ThemePages.SETTINGS -> navController.navigate(MainRoute.Settings.route)
                                                            else -> navController.navigate(action.route)
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        codeUi.Page(host) {
                                            HomeScreen(
                                                onCompanionClick = { openCompanionChat(it) },
                                                onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                                                onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                                                onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) },
                                            )
                                        }
                                    }
                                }
                                1 -> StarCircleScreen()
                                2 -> ProfileScreen(
                                    onStarMarketClick = { navController.navigate(MainRoute.StarMarket.route) },
                                    onSettingsClick = { navController.navigate(MainRoute.Settings.route) },
                                    onPluginManageClick = { navController.navigate(MainRoute.PluginManage.route) },
                                    onThemeManageClick = { navController.navigate(MainRoute.ThemeManage.route) },
                                    
                                    onGeneralSettingsClick = { navController.navigate(MainRoute.GeneralSettings.route) },
                                    
                                    onAboutClick = { navController.navigate(MainRoute.About.route) }
                                )
                            }
                        }
                    }
                }

                composable(MainRoute.StarCircle.route) {
                    LaunchedEffect(Unit) { selectTab("star_circle") }
                }
                composable(MainRoute.Contacts.route) {
                    val groupViewModel: ChatGroupViewModel = viewModel()
                    val groups by groupViewModel.groups.collectAsStateWithLifecycle(initialValue = emptyList())
                    ContactsScreen(
                        onCompanionClick = { openCompanionChat(it) },
                        onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                        onEditClick = { navController.navigate(MainRoute.EditCompanion(it).route) },
                        onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                        onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) },
                        groups = groups
                    )
                }

                
                composable(MainRoute.CreateCompanion.route) { CreateCompanionScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.EditCompanion(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    CreateCompanionScreen(companionId = companionId, onNavigateBack = { navController.popBackStack() })
                }

                
                composable(MainRoute.Chat(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    ChatScreen(
                        companionId = companionId,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToDetail = { navController.navigate("chat_detail/$it") },
                        onOpenPluginPage = { pluginId -> navController.navigate(MainRoute.PluginPage(pluginId, companionId).route) },
                        onOpenMainModelSettings = { navController.navigate(MainRoute.Settings.route) }
                    )
                }
                composable(MainRoute.ChatDetail(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val detailCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    ChatDetailScreen(
                        companionId = detailCompanionId,
                        onNavigateBack = { navController.popBackStack() },
                        onCompanionDeleted = {
                            navController.popBackStack(MainRoute.Chat(detailCompanionId).route, inclusive = true)
                        }
                    )
                }

                
                composable(MainRoute.GroupChat(0).route.replace("0", "{groupId}"), arguments = listOf(navArgument("groupId") { type = NavType.LongType })) { backStackEntry ->
                    val groupId = backStackEntry.arguments?.getLong("groupId") ?: 0L
                    GroupChatScreen(groupId = groupId, onNavigateBack = { navController.popBackStack() }, onNavigateToDetail = { navController.navigate("group_detail/$it") })
                }
                composable(MainRoute.GroupDetail(0).route.replace("0", "{groupId}"), arguments = listOf(navArgument("groupId") { type = NavType.LongType })) { backStackEntry ->
                    val groupId = backStackEntry.arguments?.getLong("groupId") ?: 0L
                    GroupDetailScreen(groupId = groupId, onNavigateBack = { navController.popBackStack() }, onGroupDeleted = { navController.popBackStack("group_chat/$groupId", inclusive = true) })
                }
                composable(MainRoute.CreateGroup.route) { CreateGroupScreen(onNavigateBack = { navController.popBackStack() }) }

                
                composable(MainRoute.Settings.route) { SettingsScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.ThemeMarket.route) { ThemeMarketScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.ThemeManage.route) {
                    ThemeManageScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onThemeModeClick = { navController.navigate(MainRoute.ThemeMode.route) },
                        onLanguageClick = { navController.navigate(MainRoute.Language.route) },
                        onFrameRateClick = { navController.navigate(MainRoute.FrameRate.route) }
                    )
                }
                composable(MainRoute.ThemeMode.route) {
                    ThemeModeScreen(
                        onNavigateBack = { navController.popBackStack() },
                        activity = mainActivity
                    )
                }
                composable(MainRoute.Language.route) { LanguageScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity) }
                composable(MainRoute.About.route) { AboutScreen(onNavigateBack = { navController.popBackStack() }, onAgreementClick = { navController.navigate(MainRoute.AgreementView.route) }) }
                composable(MainRoute.AgreementView.route) { AgreementViewScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.FrameRate.route) { FrameRateScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity) }
                composable(MainRoute.OriginOSAdaption.route) {
                    OriginOSAdaptionScreen(onNavigateBack = { navController.popBackStack() })
                }
                composable(MainRoute.GeneralSettings.route) {
                    GeneralSettingsScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onDataBackupClick = { navController.navigate(MainRoute.DataBackup.route) },
                        onOriginOSAdaptionClick = { navController.navigate(MainRoute.OriginOSAdaption.route) },
                        onWeChatClick = { navController.navigate(MainRoute.WeChatSettings.route) },
                        onQQBotClick = { navController.navigate(MainRoute.QQBotSettings.route) }
                    )
                }
                composable(MainRoute.WeChatSettings.route) {
                    ChannelPluginSetupScreen(
                        pluginId = "wechat",
                        settings = true,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateBind = { navController.navigate(MainRoute.WeChatBind.route) }
                    )
                }
                composable(MainRoute.WeChatBind.route) {
                    ChannelPluginSetupScreen(
                        pluginId = "wechat",
                        settings = false,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(MainRoute.QQBotSettings.route) {
                    ChannelPluginSetupScreen(
                        pluginId = "qqbot",
                        settings = true,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(MainRoute.DataBackup.route) { BackupScreen(onNavigateBack = { navController.popBackStack() }) }

                composable(MainRoute.StarMarket.route) {
                    StarMarketScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onOpenPlugin = { pluginId -> navController.navigate(MainRoute.PluginDetail(pluginId).route) }
                    )
                }
                composable(MainRoute.PluginMarket.route) {
                    PluginMarketScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onOpenPlugin = { pluginId -> navController.navigate(MainRoute.PluginDetail(pluginId).route) }
                    )
                }
                composable(MainRoute.PluginManage.route) {
                    PluginManageScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onOpenPlugin = { pluginId -> navController.navigate(MainRoute.PluginPage(pluginId, 0L).route) },
                        onOpenSettings = { pluginId -> navController.navigate(MainRoute.PluginSettings(pluginId).route) }
                    )
                }
                composable(MainRoute.PluginDetail("{pluginId}").route, arguments = listOf(navArgument("pluginId") { type = NavType.StringType })) { backStackEntry ->
                    val pluginId = backStackEntry.arguments?.getString("pluginId").orEmpty()
                    PluginDetailScreen(pluginId = pluginId, onNavigateBack = { navController.popBackStack() })
                }
                composable("plugin_page/{pluginId}/{companionId}", arguments = listOf(navArgument("pluginId") { type = NavType.StringType }, navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val pluginId = backStackEntry.arguments?.getString("pluginId").orEmpty()
                    val pluginCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    PluginPageHost(
                        pluginId = pluginId,
                        companionId = pluginCompanionId,
                        input = PluginFramework.manager()?.pendingInput(pluginCompanionId).orEmpty(),
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(MainRoute.PluginSettings("{pluginId}").route, arguments = listOf(navArgument("pluginId") { type = NavType.StringType })) { backStackEntry ->
                    val pluginId = backStackEntry.arguments?.getString("pluginId").orEmpty()
                    PluginSettingsHost(
                        pluginId = pluginId,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateRoute = { route ->
                            when (route) {
                                "bind" -> navController.navigate(MainRoute.PluginPage(pluginId, 0L).route)
                            }
                        }
                    )
                }
            }
        }
    }
}
