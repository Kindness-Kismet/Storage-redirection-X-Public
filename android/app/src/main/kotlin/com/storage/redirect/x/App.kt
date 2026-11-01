package com.storage.redirect.x

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.PreferenceKeys
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.ui.component.bottombar.AppNavigationBar
import com.storage.redirect.x.ui.component.UpdateDialogs
import com.storage.redirect.x.ui.component.bottombar.BottomBarAppearanceState
import com.storage.redirect.x.ui.component.bottombar.FloatingBottomBar
import com.storage.redirect.x.ui.component.bottombar.FloatingBottomBarTab
import com.storage.redirect.x.ui.component.bottombar.NAV_BAR_CONTENT_HEIGHT
import com.storage.redirect.x.ui.icon.AppIcons
import com.storage.redirect.x.ui.navigation.AppPage
import com.storage.redirect.x.ui.navigation.PageStackHost
import com.storage.redirect.x.ui.navigation.rememberPageStack
import com.storage.redirect.x.ui.navigation.rememberRootNavigationState
import com.storage.redirect.x.ui.page.AboutPage
import com.storage.redirect.x.ui.page.AppsPage
import com.storage.redirect.x.ui.page.ColorPalettePage
import com.storage.redirect.x.ui.page.FileMonitorPage
import com.storage.redirect.x.ui.page.HomePage
import com.storage.redirect.x.ui.page.LogsPage
import com.storage.redirect.x.ui.page.OpenSourceLicensesPage
import com.storage.redirect.x.ui.page.PermissionPage
import com.storage.redirect.x.ui.page.RuleConfigPage
import com.storage.redirect.x.ui.page.SettingsPage
import com.storage.redirect.x.ui.page.TemplatesPage
import com.storage.redirect.x.ui.page.UpdatePage
import com.storage.redirect.x.ui.page.UpdateViewModel
import com.storage.redirect.x.ui.theme.AppTheme
import com.storage.redirect.x.ui.theme.AppThemeController
import com.storage.redirect.x.ui.theme.AppThemeSettings
import com.storage.redirect.x.ui.theme.isInDarkTheme
import com.storage.redirect.x.ui.util.LocalTopBarBlur
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val KEY_PERMISSION_GRANTED = "permission_granted"
private val FLOATING_BOTTOM_BAR_OVERLAY_PADDING = 76.dp

@Composable
fun App(
    pendingDebugPage: String? = null,
    onDebugPageConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var themeSettings by remember { mutableStateOf(AppThemeController.readSettings()) }

    fun updateThemeSettings(settings: AppThemeSettings) {
        themeSettings = settings
        AppThemeController.writeSettings(settings)
    }

    val systemDensity = LocalDensity.current
    val density = remember(systemDensity, themeSettings.pageScale) {
        Density(systemDensity.density * themeSettings.pageScale, systemDensity.fontScale)
    }

    CompositionLocalProvider(
        LocalDensity provides density,
        LocalTopBarBlur provides themeSettings.isTopBarBlurEnabled,
    ) {
    AppTheme(settings = themeSettings) {
        // 根据主题动态切换状态栏和导航栏图标颜色
        val view = LocalView.current
        val isDark = isInDarkTheme(themeSettings.colorMode)
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }

        // 权限门禁：优先读取持久化缓存，已授权则直接进入主界面
        val cachedGrant = AppPreferences.getBoolean(KEY_PERMISSION_GRANTED)
        var permissionState by remember { mutableStateOf<Boolean?>(if (cachedGrant) true else null) }

        // 后台校验权限
        LaunchedEffect(Unit) {
            val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            val hasRoot = RootService.isRootAvailable()
            val granted = hasRoot && hasNotification

            AppPreferences.put(KEY_PERMISSION_GRANTED, granted)
            permissionState = granted
        }

        // 首次安装且未缓存：等待权限检查完成
        if (permissionState == null) return@AppTheme

        // 权限不足：显示权限引导页
        if (permissionState == false) {
            PermissionPage(onAllGranted = {
                AppPreferences.put(KEY_PERMISSION_GRANTED, true)
                permissionState = true
            })
            return@AppTheme
        }

        val pageStack = rememberPageStack<AppPage>()
        val updateViewModel: UpdateViewModel = viewModel()
        val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
        // 从配置页返回时递增，驱动应用列表刷新配置
        var configVersion by remember { mutableIntStateOf(0) }
        var bottomBarAppearance by remember {
            mutableStateOf(BottomBarAppearanceState.fromPreferences())
        }
        // 任一子页的顶栏下拉菜单展开时，禁用底栏以避免误触
        var isAnyMenuOpen by remember { mutableStateOf(false) }
        // 应用页批量选择时由页内批量栏接管底部，隐藏底栏并锁定左右滑动
        var isAppsBatchMode by remember { mutableStateOf(false) }

        fun updateBottomBarAppearance(state: BottomBarAppearanceState) {
            bottomBarAppearance = state
            AppPreferences.put(PreferenceKeys.FLOATING_BOTTOM_BAR_ENABLED, state.isFloatingBottomBarEnabled)
            AppPreferences.put(PreferenceKeys.BOTTOM_BAR_BLUR_ENABLED, state.isBottomBarBlurEnabled)
        }

        // 底栏导航项
        val navKeys = listOf("home", "apps", "monitor", "settings")
        val navIcons = remember {
            listOf(
                AppIcons.Home,
                AppIcons.Grid,
                AppIcons.Performance,
                AppIcons.Settings,
            )
        }
        // 选中的 Tab 换成同款填充图标
        val navSelectedIcons = remember {
            listOf(
                AppIcons.HomeFilled,
                AppIcons.GridFilled,
                AppIcons.PerformanceFilled,
                AppIcons.SettingsFilled,
            )
        }
        val navLabels = listOf(
            stringResource(R.string.nav_home),
            stringResource(R.string.nav_apps),
            stringResource(R.string.nav_monitor),
            stringResource(R.string.nav_settings),
        )

        val rootNavigation = rememberRootNavigationState(navKeys.size)
        val pagerState = rootNavigation.pagerState
        val selectedTabIndex = rootNavigation.selectedPage

        val isBottomBarBlurEnabled = bottomBarAppearance.isBottomBarBlurEnabled && isRuntimeShaderSupported()
        LaunchedEffect(pagerState.settledPage) {
            rootNavigation.syncPage()
        }

        // debug 指令 page.open 到达时切换 tab
        LaunchedEffect(pendingDebugPage) {
            val page = pendingDebugPage ?: return@LaunchedEffect
            val index = navKeys.indexOf(page)
            if (index >= 0) {
                pageStack.clear()
                rootNavigation.animateTo(index)
            }
            onDebugPageConsumed()
        }

        val bottomBarSurfaceColor = MiuixTheme.colorScheme.surface
        val bottomBarContentColor = MiuixTheme.colorScheme.onSurface
        // 底栏采样整个分页内容；悬浮底栏未开模糊时也需要一个 backdrop 承载自身效果
        val bottomBarBackdrop = rememberLayerBackdrop {
            drawRect(bottomBarSurfaceColor)
            drawContent()
        }
        val navigationBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val floatingBottomBarModifier = Modifier.padding(bottom = 12.dp + navigationBarsBottom)
        // 底栏叠在分页内容上，主页面底部为其留白；二级页面覆盖底栏，只需避让系统导航栏
        val overlayBottomPadding = navigationBarsBottom + if (bottomBarAppearance.isFloatingBottomBarEnabled) {
            FLOATING_BOTTOM_BAR_OVERLAY_PADDING
        } else {
            NAV_BAR_CONTENT_HEIGHT
        }

        PageStackHost(
            stack = pageStack,
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            page = { route, onBack ->
                when (route) {
                    AppPage.Templates -> {
                        // 模板可能被改动，关闭后刷新应用列表里的模板与配置
                        DisposableEffect(Unit) { onDispose { configVersion++ } }
                        TemplatesPage(onBack = onBack)
                    }
                    is AppPage.RuleConfig -> RuleConfigPage(
                        packageName = route.packageName,
                        appName = route.appName,
                        userId = route.userId,
                        onBack = onBack,
                        onModifiedExit = { configVersion++ },
                    )
                    AppPage.Theme -> ColorPalettePage(
                        themeSettings = themeSettings,
                        onThemeSettingsChange = ::updateThemeSettings,
                        bottomBarAppearance = bottomBarAppearance,
                        onBottomBarAppearanceChange = ::updateBottomBarAppearance,
                        onBack = onBack,
                    )
                    AppPage.Logs -> LogsPage(onBack = onBack, bottomInnerPadding = navigationBarsBottom)
                    AppPage.About -> AboutPage(
                        onBack = onBack,
                        onOpenLicenses = { pageStack.push(AppPage.Licenses) },
                        bottomInnerPadding = navigationBarsBottom,
                    )
                    AppPage.Licenses -> OpenSourceLicensesPage(onBack = onBack, bottomInnerPadding = navigationBarsBottom)
                    AppPage.Update -> UpdatePage(
                        uiState = updateState,
                        onCheck = { updateViewModel.checkForUpdate() },
                        onCheckOnLaunchChange = updateViewModel::setCheckOnLaunch,
                        onChannelChange = updateViewModel::setChannel,
                        onBack = onBack,
                        bottomInnerPadding = navigationBarsBottom,
                    )
                }
            },
        ) {
            Scaffold(
                // 主内容仅消费横向安全区，顶部间距由页面顶栏自身处理
                contentWindowInsets = WindowInsets.systemBars
                    .add(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal),
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .testTag("page_container_${navKeys[pagerState.settledPage]}")
                            .then(if (isBottomBarBlurEnabled) Modifier.layerBackdrop(bottomBarBackdrop) else Modifier),
                        verticalAlignment = Alignment.Top,
                        beyondViewportPageCount = navKeys.size - 1,
                        userScrollEnabled = !isAppsBatchMode,
                    ) { page ->
                        when (page) {
                            0 -> HomePage(
                                themeMode = themeSettings.colorMode.value,
                                bottomInnerPadding = overlayBottomPadding,
                                onMenuStateChange = { isAnyMenuOpen = it },
                                onTemplatesClick = { pageStack.push(AppPage.Templates) },
                                onLogsClick = { pageStack.push(AppPage.Logs, waitForReady = true) },
                            )
                            1 -> AppsPage(
                                onAppClick = { packageName, appName, userId ->
                                    pageStack.push(AppPage.RuleConfig(packageName, appName, userId), waitForReady = true)
                                },
                                configVersion = configVersion,
                                bottomInnerPadding = overlayBottomPadding,
                                onMenuStateChange = { isAnyMenuOpen = it },
                                onBatchModeChange = { isAppsBatchMode = it },
                                onTemplatesClick = { pageStack.push(AppPage.Templates) },
                            )
                            2 -> FileMonitorPage(
                                bottomInnerPadding = overlayBottomPadding,
                                onMenuStateChange = { isAnyMenuOpen = it },
                            )
                            3 -> SettingsPage(
                                onOpenTheme = { pageStack.push(AppPage.Theme) },
                                onOpenLogs = { pageStack.push(AppPage.Logs, waitForReady = true) },
                                onOpenAbout = { pageStack.push(AppPage.About) },
                                onOpenUpdate = { pageStack.push(AppPage.Update) },
                                bottomInnerPadding = overlayBottomPadding,
                            )
                        }
                    }

                    // 底栏属于主页面层，位于 Scaffold 内部，受 MiuixPopupHost 遮罩覆盖
                    if (bottomBarAppearance.isFloatingBottomBarEnabled) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                AnimatedVisibility(
                                    visible = !isAppsBatchMode,
                                    enter = slideInVertically { it } + fadeIn(),
                                    exit = slideOutVertically { it } + fadeOut(),
                                ) {
                                    FloatingBottomBar(
                                        modifier = floatingBottomBarModifier.testTag("bottom_bar"),
                                        selectedIndex = selectedTabIndex,
                                        tabTags = navKeys,
                                        onSelected = { index -> rootNavigation.animateTo(index) },
                                        backdrop = bottomBarBackdrop,
                                        tabsCount = navKeys.size,
                                        isBlurEnabled = isBottomBarBlurEnabled,
                                        isDarkTheme = isDark,
                                        enabled = !isAnyMenuOpen,
                                        accentColor = MiuixTheme.colorScheme.primary,
                                        containerColor = MiuixTheme.colorScheme.surfaceContainer,
                                    ) {
                                        navIcons.forEachIndexed { index, icon ->
                                            FloatingBottomBarTab(
                                                onClick = { rootNavigation.animateTo(index) },
                                                enabled = !isAnyMenuOpen,
                                                modifier = Modifier
                                                    .defaultMinSize(minWidth = 76.dp)
                                                    .testTag("bottom_tab_${navKeys[index]}"),
                                            ) {
                                                Icon(
                                                    imageVector = if (index == selectedTabIndex) navSelectedIcons[index] else icon,
                                                    contentDescription = navLabels[index],
                                                    tint = bottomBarContentColor,
                                                )
                                                Text(
                                                    text = navLabels[index],
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    color = bottomBarContentColor,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Visible,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (!isAppsBatchMode) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            AppNavigationBar(
                                labels = navLabels,
                                icons = navIcons,
                                selectedIcons = navSelectedIcons,
                                selectedIndex = selectedTabIndex,
                                enabled = !isAnyMenuOpen,
                                backdrop = if (isBottomBarBlurEnabled) bottomBarBackdrop else null,
                                onSelect = { index -> rootNavigation.animateTo(index) },
                            )
                        }
                    }
                }
            }
        }
        UpdateDialogs(updateState.result, updateState.isDialogVisible, updateViewModel::dismissDialog)
        }
    }
    }
