package com.storage.redirect.x

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.service.RootService
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.storage.redirect.x.data.cache.PreferenceKeys
import com.storage.redirect.x.ui.component.bottombar.AppNavigationBar
import com.storage.redirect.x.ui.component.bottombar.BottomBarAppearanceState
import com.storage.redirect.x.ui.component.bottombar.BottomBarBlurMode
import com.storage.redirect.x.ui.component.bottombar.FloatingBottomBar
import com.storage.redirect.x.ui.component.bottombar.FloatingBottomBarTab
import com.storage.redirect.x.ui.page.AppsPage
import com.storage.redirect.x.ui.page.FileMonitorPage
import com.storage.redirect.x.ui.page.HomePage
import com.storage.redirect.x.ui.page.PermissionPage
import com.storage.redirect.x.ui.page.RuleConfigPage
import com.storage.redirect.x.ui.page.SettingsPage
import com.storage.redirect.x.ui.page.TemplatesPage
import com.storage.redirect.x.ui.theme.AppTheme
import com.storage.redirect.x.ui.theme.AppThemeController
import com.storage.redirect.x.ui.theme.AppThemeSettings
import com.storage.redirect.x.ui.theme.LocalEnableBlur
import com.storage.redirect.x.ui.theme.UiMode
import com.storage.redirect.x.ui.theme.isInDarkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val KEY_PERMISSION_GRANTED = "permission_granted"
private val FLOATING_BOTTOM_BAR_OVERLAY_PADDING = 76.dp

// Detail 页面路由
data class DetailRoute(val packageName: String, val appName: String, val userId: Int)

@Composable
fun App() {
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
        LocalEnableBlur provides themeSettings.enableBlur,
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

        var detailRoute by remember { mutableStateOf<DetailRoute?>(null) }
        var isTemplatesPageVisible by remember { mutableStateOf(false) }
        // 从配置页返回时递增，驱动应用列表刷新配置
        var configVersion by remember { mutableIntStateOf(0) }
        var bottomBarAppearance by remember {
            mutableStateOf(BottomBarAppearanceState.fromPreferences())
        }
        // 任一子页的顶栏下拉菜单展开时，禁用底栏以避免误触
        var isAnyMenuOpen by remember { mutableStateOf(false) }

        fun closeDetail(modified: Boolean) {
            detailRoute = null
            if (modified) configVersion++
        }

        // 底栏导航项
        val navKeys = listOf("home", "apps", "monitor", "settings")
        val navIcons = remember {
            listOf(
                Icons.Rounded.Home,
                Icons.Rounded.Apps,
                Icons.Rounded.Folder,
                Icons.Rounded.Settings,
            )
        }
        val navLabels = listOf(
            stringResource(R.string.nav_home),
            stringResource(R.string.nav_apps),
            stringResource(R.string.nav_monitor),
            stringResource(R.string.nav_settings),
        )

        val pagerState = rememberPagerState(pageCount = { navKeys.size })
        var selectedTabIndex by remember { mutableIntStateOf(pagerState.currentPage) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(pagerState.settledPage) {
            selectedTabIndex = pagerState.settledPage
        }

        // 底栏配色按 uiMode 取色：Material 走 M3 colorScheme，Miuix 走 Miuix colorScheme
        val isMaterialMode = themeSettings.uiMode == UiMode.Material
        val bottomBarSurfaceColor = if (isMaterialMode) {
            MaterialTheme.colorScheme.surface
        } else {
            MiuixTheme.colorScheme.surface
        }
        val bottomBarContainerColor = if (isMaterialMode) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        }
        val bottomBarAccentColor = if (isMaterialMode) {
            MaterialTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.primary
        }
        val bottomBarContentColor = if (isMaterialMode) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MiuixTheme.colorScheme.onSurface
        }
        val bottomBarBlurMode = bottomBarAppearance.blurMode
        val bottomBarBackdrop = rememberLayerBackdrop {
            drawRect(bottomBarSurfaceColor)
            drawContent()
        }
        val floatingBottomBarModifier = Modifier
            .padding(bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        val overlayBottomPadding = if (bottomBarAppearance.isFloatingBottomBarEnabled) {
            FLOATING_BOTTOM_BAR_OVERLAY_PADDING +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        } else {
            0.dp
        }

        Box(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        ) {
            Scaffold(
                bottomBar = {
                    if (bottomBarAppearance.usesBottomBarSlotPadding) {
                        AppNavigationBar(
                            labels = navLabels,
                            icons = navIcons,
                            selectedIndex = selectedTabIndex,
                            enabled = !isAnyMenuOpen,
                            onSelect = { index ->
                                selectedTabIndex = index
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                        )
                    }
                },
                // 主内容仅消费横向安全区，顶部间距由页面顶栏自身处理
                contentWindowInsets = WindowInsets.systemBars
                    .add(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal),
            ) { padding ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .padding(padding)
                        .testTag("page_container_${navKeys[pagerState.settledPage]}")
                        .then(
                            if (bottomBarBlurMode == BottomBarBlurMode.Blur) {
                                Modifier.layerBackdrop(bottomBarBackdrop)
                            } else {
                                Modifier
                            }
                        ),
                    verticalAlignment = Alignment.Top,
                    beyondViewportPageCount = navKeys.size - 1,
                ) { page ->
                    when (page) {
                        0 -> HomePage(
                            themeMode = themeSettings.colorMode.value,
                            bottomInnerPadding = overlayBottomPadding,
                            onMenuStateChange = { isAnyMenuOpen = it },
                        )
                        1 -> AppsPage(
                            onAppClick = { packageName, appName, userId ->
                                detailRoute = DetailRoute(packageName, appName, userId)
                            },
                            configVersion = configVersion,
                            bottomInnerPadding = overlayBottomPadding,
                            onMenuStateChange = { isAnyMenuOpen = it },
                            onTemplatesClick = { isTemplatesPageVisible = true },
                        )
                        2 -> FileMonitorPage(bottomInnerPadding = overlayBottomPadding)
                        3 -> SettingsPage(
                            themeSettings = themeSettings,
                            onThemeSettingsChange = ::updateThemeSettings,
                            bottomBarAppearance = bottomBarAppearance,
                            onBottomBarAppearanceChange = { state ->
                                bottomBarAppearance = state
                                AppPreferences.put(
                                    PreferenceKeys.FLOATING_BOTTOM_BAR_ENABLED,
                                    state.isFloatingBottomBarEnabled,
                                )
                                AppPreferences.put(
                                    PreferenceKeys.FLOATING_BOTTOM_BAR_BLUR_ENABLED,
                                    state.isFloatingBottomBarBlurEnabled,
                                )
                            },
                            bottomInnerPadding = overlayBottomPadding,
                        )
                    }
                }
            }

            if (bottomBarAppearance.isFloatingBottomBarEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        FloatingBottomBar(
                            modifier = floatingBottomBarModifier.testTag("bottom_bar"),
                            selectedIndex = selectedTabIndex,
                            tabTags = navKeys,
                            onSelected = { index ->
                                selectedTabIndex = index
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            backdrop = bottomBarBackdrop,
                            tabsCount = navKeys.size,
                            isBlurEnabled = bottomBarAppearance.isBlurEffectActive,
                            isDarkTheme = isDark,
                            enabled = !isAnyMenuOpen,
                            accentColor = bottomBarAccentColor,
                            containerColor = bottomBarContainerColor,
                        ) {
                            navIcons.forEachIndexed { index, icon ->
                                FloatingBottomBarTab(
                                    onClick = {
                                        selectedTabIndex = index
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    },
                                    enabled = !isAnyMenuOpen,
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 76.dp)
                                        .testTag("bottom_tab_${navKeys[index]}"),
                                ) {
                                    Icon(
                                        imageVector = icon,
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

            if (isTemplatesPageVisible) {
                TemplatesPage(onBack = {
                    isTemplatesPageVisible = false
                    configVersion++
                })
            }

            detailRoute?.let { route ->
                RuleConfigPage(
                    packageName = route.packageName,
                    appName = route.appName,
                    userId = route.userId,
                    onBack = { modified -> closeDetail(modified) }
                )
            }
        }
    }
    }
}
