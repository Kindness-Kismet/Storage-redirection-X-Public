package com.storage.redirect.x

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.ui.navigation.*
import com.storage.redirect.x.ui.page.*
import com.storage.redirect.x.ui.theme.*
import com.storage.redirect.x.ui.util.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.nav.core.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage

@Composable
fun App() {
    val context = LocalContext.current
    var theme by remember { mutableStateOf(AppThemeController.readSettings()) }
    val platformDensity = LocalDensity.current
    val density =
        remember(platformDensity, theme.pageScale) {
            Density(platformDensity.density * theme.pageScale, platformDensity.fontScale)
        }
    val onThemeChange: (AppThemeSettings) -> Unit = {
        theme = it
        AppThemeController.writeSettings(it)
    }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalPlatformDensity provides platformDensity,
        LocalEnableBlur provides theme.enableBlur,
        LocalTopBarBlurStyle provides theme.topBarBlurStyle,
    ) {
        AppTheme(theme) {
            val view = LocalView.current
            val dark = LocalAppDarkMode.current
            SideEffect {
                WindowCompat.getInsetsController((view.context as Activity).window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            var granted by remember {
                mutableStateOf<Boolean?>(
                    if (AppPreferences.getBoolean("permission_granted")) true else null
                )
            }
            LaunchedEffect(Unit) {
                val notification =
                    Build.VERSION.SDK_INT < 33 ||
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                granted = RootService.isRootAvailable() && notification
                AppPreferences.put("permission_granted", granted == true)
            }
            if (granted == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@AppTheme
            }
            if (granted == false) {
                PermissionPage(
                    onAllGranted = {
                        granted = true
                        AppPreferences.put("permission_granted", true)
                    }
                )
                return@AppTheme
            }
            val navigator = rememberAppNavigator()
            CompositionLocalProvider(LocalNavigator provides navigator) {
                NavDisplay(
                    backStack = navigator.backStack,
                    onBack = navigator::pop,
                    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                ) {
                    entry<ScreenRoute> { route ->
                        when (route.page) {
                            "main" -> MainPage()
                            "theme" -> ColorPalettePage(theme, onThemeChange, navigator::pop)
                            "logs" -> LogsPage(navigator::pop)
                            "licenses" -> OpenSourceLicensesPage(navigator::pop)
                            "about" -> AboutPage(onBack = navigator::pop)
                            "update" -> UpdatePage(navigator::pop)
                            "templates" -> TemplatesPage(navigator::pop)
                            "rules" ->
                                RuleConfigPage(route.packageName, route.appName, route.userId) {
                                    navigator.pop()
                                }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MainPage() {
    val navigator = LocalNavigator.current
    val pager = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    var selected by remember { mutableIntStateOf(pager.currentPage) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val select: (Int) -> Unit = { index ->
        if (selected != index) {
            navigationJob?.cancel()
            selected = index
            navigationJob = scope.launch {
                val thisJob = coroutineContext.job
                try {
                    pager.springAnimateToPage(index)
                } finally {
                    if (navigationJob == thisJob) selected = pager.currentPage
                }
            }
        }
    }
    LaunchedEffect(pager.settledPage) {
        if (navigationJob?.isActive != true) selected = pager.settledPage
    }
    BackHandler(enabled = selected != 0) { select(0) }
    val labels =
        listOf(
            stringResource(R.string.nav_home),
            stringResource(R.string.nav_apps),
            stringResource(R.string.nav_monitor),
            stringResource(R.string.nav_settings),
        )
    val icons =
        listOf(MiuixIcons.Sidebar, MiuixIcons.Tune, MiuixIcons.UploadCloud, MiuixIcons.Settings)
    val keys = listOf("home", "apps", "monitor", "settings")
    val wide = rememberIsWideScreen()
    val backdrop = rememberBlurBackdrop()
    val barColor = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
    val pagerContent: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit =
        { modifier, bottomPadding ->
            HorizontalPager(
                state = pager,
                modifier =
                    modifier
                        .pagerGestureOverride(
                            pager,
                            mode = PagerInterceptionMode.CrossAxisInterceptor,
                        )
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                userScrollEnabled = false,
                pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                verticalAlignment = Alignment.Top,
                flingBehavior =
                    PagerDefaults.flingBehavior(
                        pager,
                        snapAnimationSpec = PagerNavigationSpringSpec,
                    ),
                overscrollEffect = null,
            ) { page ->
                when (page) {
                    0 -> HomePage(bottomInnerPadding = bottomPadding)
                    1 ->
                        AppsPage(
                            onAppClick = { pkg, name, user ->
                                navigator.push(ScreenRoute("rules", pkg, name, user))
                            },
                            configVersion = navigator.configVersion,
                            bottomInnerPadding = bottomPadding,
                            onTemplatesClick = { navigator.push("templates") },
                        )
                    2 -> FileMonitorPage(bottomInnerPadding = bottomPadding)
                    3 -> SettingsPage(bottomInnerPadding = bottomPadding)
                }
            }
        }
    if (wide) {
        val railState = rememberNavigationRailState()
        Scaffold { _ ->
            Row(Modifier.fillMaxSize()) {
                NavigationRail(state = railState) {
                    icons.forEachIndexed { i, icon ->
                        NavigationRailItem(
                            selected = selected == i,
                            onClick = { select(i) },
                            icon = if (i == 0) MiuixIcons.Home else icon,
                            label = labels[i],
                        )
                    }
                }
                pagerContent(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .consumeWindowInsets(
                            WindowInsets.displayCutout
                                .union(WindowInsets.navigationBars)
                                .only(WindowInsetsSides.Start)
                        )
                        .windowInsetsPadding(
                            WindowInsets.systemBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.End)
                        ),
                    0.dp,
                )
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                BlurredBar(backdrop, useProgressive = false) {
                    NavigationBar(
                        color = barColor,
                        showDivider = false,
                        modifier = Modifier.testTag("bottom_bar"),
                    ) {
                        icons.forEachIndexed { i, icon ->
                            NavigationBarItem(
                                selected = selected == i,
                                onClick = { select(i) },
                                icon = icon,
                                label = labels[i],
                                modifier = Modifier.testTag("nav_${keys[i]}"),
                            )
                        }
                    }
                }
            }
        ) { padding ->
            pagerContent(Modifier.fillMaxSize(), padding.calculateBottomPadding())
        }
    }
}
