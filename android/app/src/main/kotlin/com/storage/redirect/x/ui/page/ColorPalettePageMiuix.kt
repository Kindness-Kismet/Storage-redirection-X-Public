package com.storage.redirect.x.ui.page

import android.os.Build
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.storage.redirect.x.ui.component.PAGE_CONTENT_TOP_PADDING
import com.storage.redirect.x.ui.theme.rememberInteractionFeedback
import com.storage.redirect.x.ui.theme.isInDarkTheme
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.PreferenceKeys
import com.storage.redirect.x.ui.icon.AppIcons
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.SrxBackButton
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.component.bottombar.BottomBarAppearanceState
import com.storage.redirect.x.ui.component.miuix.ScaleDialog
import com.storage.redirect.x.ui.theme.AppThemeSettings
import com.storage.redirect.x.ui.theme.ColorMode
import com.storage.redirect.x.ui.theme.keyColorOptions
import com.storage.redirect.x.util.PredictiveBackSupport
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ColorPalettePageMiuix(
    themeSettings: AppThemeSettings,
    onThemeSettingsChange: (AppThemeSettings) -> Unit,
    bottomBarAppearance: BottomBarAppearanceState,
    onBottomBarAppearanceChange: (BottomBarAppearanceState) -> Unit,
    onBack: () -> Unit,
    bottomInnerPadding: Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val colorMode = themeSettings.colorMode
    val activity = LocalActivity.current
    var isPredictiveBackEnabled by remember {
        mutableStateOf(AppPreferences.getBoolean(PreferenceKeys.PREDICTIVE_BACK_ENABLED))
    }

    SrxScaffold(
        topBar = {
            SrxTopAppBar(
                title = stringResource(R.string.theme_title),
                navigationIcon = { SrxBackButton(onClick = onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val showScaleDialog = rememberSaveable { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = PAGE_HORIZONTAL_PADDING),
            overscrollEffect = null,
            contentPadding = innerPadding,
        ) {
            item {
                Spacer(Modifier.height(PAGE_CONTENT_TOP_PADDING))
                ThemeModeSelector(
                    colorMode = colorMode,
                    onSelect = { onThemeSettingsChange(themeSettings.copy(colorMode = it)) },
                )

                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_monet),
                        startAction = {
                            Icon(
                                AppIcons.ColorFilter,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = themeSettings.miuixMonet,
                        onCheckedChange = { onThemeSettingsChange(themeSettings.copy(miuixMonet = it)) },
                    )

                    AnimatedVisibility(visible = themeSettings.miuixMonet) {
                        Column {
                            val colorItems = themeColorNames()
                            val colorValues = listOf(0) + keyColorOptions
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_key_color),
                                items = colorItems,
                                startAction = {
                                    Icon(
                                        AppIcons.ColorPicker,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                selectedIndex = colorValues.indexOf(themeSettings.keyColor).takeIf { it >= 0 } ?: 0,
                                onSelectedIndexChange = { index ->
                                    onThemeSettingsChange(themeSettings.copy(keyColor = colorValues[index]))
                                },
                            )

                            AnimatedVisibility(visible = themeSettings.keyColor != 0) {
                                Column {
                                    val styles = PaletteStyle.entries
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.settings_color_style),
                                        items = styles.map { it.name },
                                        startAction = {
                                            Icon(
                                                AppIcons.PaletteStyle,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = null,
                                                tint = colorScheme.onBackground,
                                            )
                                        },
                                        selectedIndex = styles.indexOf(themeSettings.paletteStyle).coerceAtLeast(0),
                                        onSelectedIndexChange = { index ->
                                            onThemeSettingsChange(themeSettings.copy(paletteStyle = styles[index]))
                                        },
                                    )

                                    val specs = ColorSpec.SpecVersion.entries
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.settings_color_spec),
                                        items = specs.map { it.name },
                                        startAction = {
                                            Icon(
                                                AppIcons.Brush,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = null,
                                                tint = colorScheme.onBackground,
                                            )
                                        },
                                        selectedIndex = specs.indexOf(themeSettings.colorSpec).coerceAtLeast(0),
                                        onSelectedIndexChange = { index ->
                                            onThemeSettingsChange(themeSettings.copy(colorSpec = specs[index]))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_floating_bottom_bar),
                        summary = stringResource(R.string.settings_floating_bottom_bar_summary),
                        startAction = {
                            Icon(
                                AppIcons.LayoutBottom,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = bottomBarAppearance.isFloatingBottomBarEnabled,
                        onCheckedChange = { enabled ->
                            onBottomBarAppearanceChange(bottomBarAppearance.copy(isFloatingBottomBarEnabled = enabled))
                        },
                    )
                    // 模糊依赖 RuntimeShader，API 33 以下不提供开关
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SwitchPreference(
                            modifier = Modifier.testTag("switch_theme_top_bar_blur"),
                            title = stringResource(R.string.settings_top_bar_blur),
                            summary = stringResource(R.string.settings_top_bar_blur_summary),
                            startAction = {
                                Icon(
                                    AppIcons.BlurOn,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = themeSettings.isTopBarBlurEnabled,
                            onCheckedChange = { onThemeSettingsChange(themeSettings.copy(isTopBarBlurEnabled = it)) },
                        )
                        SwitchPreference(
                            modifier = Modifier.testTag("switch_theme_bottom_bar_blur"),
                            title = stringResource(R.string.settings_bottom_bar_blur),
                            summary = stringResource(R.string.settings_bottom_bar_blur_summary),
                            startAction = {
                                Icon(
                                    AppIcons.BlurOn,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = bottomBarAppearance.isBottomBarBlurEnabled,
                            onCheckedChange = { enabled ->
                                onBottomBarAppearanceChange(bottomBarAppearance.copy(isBottomBarBlurEnabled = enabled))
                            },
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        SwitchPreference(
                            modifier = Modifier.testTag("switch_theme_predictive_back"),
                            title = stringResource(R.string.settings_predictive_back),
                            summary = stringResource(R.string.settings_predictive_back_summary),
                            startAction = {
                                Icon(
                                    AppIcons.SwipeHorizontal,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = isPredictiveBackEnabled,
                            onCheckedChange = { enabled ->
                                // 窗口只在创建时读取该标记，切换后重建 Activity 才生效
                                if (activity != null && PredictiveBackSupport.setEnabled(activity.applicationInfo, enabled)) {
                                    isPredictiveBackEnabled = enabled
                                    AppPreferences.put(PreferenceKeys.PREDICTIVE_BACK_ENABLED, enabled)
                                    activity.recreate()
                                }
                            },
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    var sliderValue by remember(themeSettings.pageScale) { mutableFloatStateOf(themeSettings.pageScale) }
                    ArrowPreference(
                        title = stringResource(R.string.settings_page_scale),
                        summary = stringResource(R.string.settings_page_scale_summary),
                        startAction = {
                            Icon(
                                AppIcons.AspectRatio,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        },
                        endActions = {
                            Text(
                                text = "${(sliderValue * 100).toInt()}%",
                                color = colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = { showScaleDialog.value = !showScaleDialog.value },
                        bottomAction = {
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    onThemeSettingsChange(themeSettings.copy(pageScale = sliderValue))
                                },
                                valueRange = 0.8f..1.1f,
                            )
                        },
                    )
                    ScaleDialog(
                        show = showScaleDialog.value,
                        onDismissRequest = { showScaleDialog.value = false },
                        volumeState = { themeSettings.pageScale },
                        onVolumeChange = { onThemeSettingsChange(themeSettings.copy(pageScale = it)) },
                    )
                }
            }
            item {
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                            12.dp + bottomInnerPadding,
                    )
                )
            }
        }
    }
}

private fun ColorMode.toBaseThemeIndex(): Int = when (this) {
    ColorMode.LIGHT, ColorMode.MONET_LIGHT -> 1
    ColorMode.DARK, ColorMode.MONET_DARK -> 2
    else -> 0
}

private fun baseColorModeForIndex(index: Int): ColorMode = when (index) {
    1 -> ColorMode.LIGHT
    2 -> ColorMode.DARK
    else -> ColorMode.SYSTEM
}

@Composable
internal fun themeColorNames(): List<String> = listOf(
    stringResource(R.string.settings_key_color_default),
    stringResource(R.string.color_red),
    stringResource(R.string.color_pink),
    stringResource(R.string.color_purple),
    stringResource(R.string.color_deep_purple),
    stringResource(R.string.color_indigo),
    stringResource(R.string.color_blue),
    stringResource(R.string.color_cyan),
    stringResource(R.string.color_teal),
    stringResource(R.string.color_green),
    stringResource(R.string.color_yellow),
    stringResource(R.string.color_amber),
    stringResource(R.string.color_orange),
    stringResource(R.string.color_brown),
    stringResource(R.string.color_blue_grey),
    stringResource(R.string.color_sakura),
)

@Composable
private fun ThemeModeSelector(colorMode: ColorMode, onSelect: (ColorMode) -> Unit) {
    val labels = listOf(
        stringResource(R.string.theme_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
    )
    val feedback = rememberInteractionFeedback(CircleShape)
    val selectedColor = if (isInDarkTheme(colorMode)) colorScheme.secondaryVariant else colorScheme.surfaceContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colorScheme.surfaceContainerHigh)
            .padding(4.dp)
            .selectableGroup()
            .testTag("theme_mode_selector"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = colorMode.toBaseThemeIndex() == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) selectedColor else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = feedback,
                        onClick = { onSelect(baseColorModeForIndex(index)) },
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp)
                    .testTag("theme_mode_$index"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) colorScheme.onSurface else colorScheme.onSurfaceVariantSummary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
