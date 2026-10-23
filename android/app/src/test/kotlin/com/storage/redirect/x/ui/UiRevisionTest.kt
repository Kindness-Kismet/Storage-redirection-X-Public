package com.storage.redirect.x.ui

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.ui.navigation.*
import com.storage.redirect.x.ui.page.*
import com.storage.redirect.x.ui.page.FakeAppSource
import com.storage.redirect.x.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiRevisionTest {
    @Before
    fun setup() {
        AppPreferences.init(RuntimeEnvironment.getApplication())
        AppPreferences.put("apps_sort_mode", "NAME")
        AppPreferences.put("apps_show_system", false)
    }

    @Test
    fun monitorFilterUsesTheAnchoredMenuAndAppliesImmediately() {
        val vm = FileMonitorViewModel()
        RenderScene {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) {
                    FileMonitorPage(monitorViewModel = vm)
                }
            }
            .use { scene ->
                scene.frames()
                scene.tapTag("monitor_filter_menu")
                val create =
                    scene.activity.getString(
                        com.storage.redirect.x.R.string.file_monitor_action_create
                    )
                val rename =
                    scene.activity.getString(
                        com.storage.redirect.x.R.string.file_monitor_action_rename
                    )
                assertTrue(scene.texts(create).isNotEmpty())
                assertTrue(scene.texts(rename).isNotEmpty())
                assertTrue(scene.texts("取消").isEmpty())
                scene.capture("monitor-filter-menu")
                scene.tapText(create)
                assertEquals(2, vm.uiState.value.opTypeIndex)
                assertFalse(vm.uiState.value.isOpTypeMenuVisible)
            }
    }

    @Test
    fun restartActionAndItsDialogStayBounded() {
        val navigator = AppNavigator()
        RenderScene {
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalEnableBlur provides false,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { HomePage() }
            }
        }
            .use { scene ->
                scene.frames()
                val button = scene.tag("home_restart_media").boundsInRoot
                assertTrue(button.height < 240f)
                assertTrue(button.width > 800f)
                val status = scene.tag("home_module_status").boundsInRoot
                val apps = scene.tag("home_redirected_apps").boundsInRoot
                val count = scene.tag("home_redirect_count").boundsInRoot
                assertEquals(status.width, apps.width, 1f)
                assertEquals(status.top, apps.top, 1f)
                assertEquals(status.bottom, count.bottom, 1f)
                assertTrue(count.top > apps.bottom)
                assertTrue(button.top >= status.bottom)
                scene.capture("home-revised")
                scene.tapTag("home_restart_media")
                assertTrue(
                    "Window texts: ${scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }}",
                    scene.texts("确定").isNotEmpty(),
                )
                val confirm = scene.clickable(scene.texts("确定").first()).boundsInRoot
                val cancel = scene.clickable(scene.texts("取消").first()).boundsInRoot
                assertTrue(
                    "The confirmation action filled the screen: $confirm",
                    confirm.height < 240f,
                )
                assertEquals(confirm.height, cancel.height, 1f)
                scene.tapText("取消")
            }
    }

    @Test
    fun templatesTitleAndBackButtonShareOneToolbarRow() {
        RenderScene { AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { TemplatesPage() } }
            .use { scene ->
                scene.frames()
                val title = scene.texts("模板").first().boundsInRoot
                val back =
                    scene
                        .nodes()
                        .first {
                            it.config
                                .getOrNull(SemanticsProperties.ContentDescription)
                                ?.contains("关闭") == true
                        }
                        .boundsInRoot
                assertEquals(title.center.y, back.center.y, 3f)
                scene.capture("templates-revised")
            }
    }

    @Test
    fun themeModeUsesTheAnchoredBlurStyleMenu() {
        RenderScene {
            AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) {
                ColorPalettePage(AppThemeSettings(colorMode = ColorMode.LIGHT), {}, {})
            }
        }
            .use { scene ->
                scene.frames()
                scene.tapText("主题模式")
                scene.capture("theme-mode-diagnostic")
                assertTrue(
                    "Texts: ${scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }}",
                    scene.texts("跟随系统").isNotEmpty(),
                )
                assertTrue(scene.texts("深色").isNotEmpty())
                assertTrue(
                    "An anchored choice menu must not add a dialog cancel button",
                    scene.texts("取消").isEmpty(),
                )
                scene.capture("theme-mode-menu")
            }
    }

    @Test
    fun configuredAppsUseMishkaCheckboxesAndSelectionConfirmsAtBottomRight() {
        val source = FakeAppSource()
        val vm = AppsViewModel(source)
        vm.cacheConfig(0, 0, RedirectConfig().addApp("example.alpha"))
        RenderScene {
            AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) {
                AppsPage(appsViewModel = vm)
            }
        }
            .use { scene ->
                scene.frames(80)
                scene.capture("apps-diagnostic")
                assertTrue(
                    "Catalog: ${vm.uiState.value}; filtered: ${vm.filteredApps.value}; source reads: ${source.reads}",
                    vm.filteredApps.value.isNotEmpty(),
                )
                val card = scene.tag("app_item_example.alpha")
                fun nodes(node: SemanticsNode): List<SemanticsNode> =
                    listOf(node) + node.children.flatMap(::nodes)
                assertTrue(
                    nodes(card).any {
                        it.config.getOrNull(SemanticsProperties.ToggleableState) ==
                            ToggleableState.On
                    }
                )
                scene.capture("apps-configured-revised")
                card.config.getOrNull(SemanticsActions.OnLongClick)!!.action!!.invoke()
                scene.frames()
                val confirm = scene.tag("apps_selection_confirm").boundsInRoot
                assertTrue(confirm.left > scene.decor.width / 2f)
                assertTrue(confirm.bottom > scene.decor.height * 0.8f)
                assertTrue(confirm.height < 240f)
            }
    }

    @Test
    fun aboutUsesMishkaHeroAndItsSolidFallback() {
        val navigator = AppNavigator()
        RenderScene {
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalEnableBlur provides false,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { AboutPage() }
            }
        }
            .use { scene ->
                scene.frames()
                assertTrue(scene.texts("存储重定向 X").isNotEmpty())
                scene.capture("about-solid")
            }
    }

    @Test
    fun aboutUsesMishkaAnimatedGlass() {
        val navigator = AppNavigator()
        RenderScene {
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalEnableBlur provides true,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { AboutPage() }
            }
        }
            .use { scene ->
                scene.frames()
                assertTrue(scene.texts("存储重定向 X").isNotEmpty())
                scene.capture("about-glass")
                val scroller =
                    scene.nodes().first {
                        it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
                    }
                scroller.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f, 1500f)
                scene.frames(80)
                scene.capture("about-scrolled")
            }
    }

    @Test
    fun settingsHasOneAboutEntryAndUsesAnchoredLanguageMenu() {
        val navigator = AppNavigator()
        RenderScene {
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalEnableBlur provides false,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { SettingsPage() }
            }
        }
            .use { scene ->
                scene.frames()
                scene.tapText("语言")
                assertTrue(scene.texts("取消").isEmpty())
                scene.capture("language-menu")
                // Dismiss by tapping the currently selected option; no activity recreation occurs
                // here.
                val menuOption = scene.texts("跟随系统").last()
                scene
                    .clickable(menuOption)
                    .config
                    .getOrNull(SemanticsActions.OnClick)!!
                    .action!!
                    .invoke()
                scene.frames()
                val scroller =
                    scene.nodes().first {
                        it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
                    }
                scroller.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f, 2000f)
                scene.frames(80)
                assertEquals(
                    1,
                    scene.nodes().count {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "settings_about_entry"
                    },
                )
                scene.tapTag("settings_about_entry")
                assertEquals("about", (navigator.backStack.last() as ScreenRoute).page)
            }
    }
}
