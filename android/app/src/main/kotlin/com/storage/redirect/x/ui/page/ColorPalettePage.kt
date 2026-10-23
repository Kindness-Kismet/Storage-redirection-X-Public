package com.storage.redirect.x.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.component.*
import com.storage.redirect.x.ui.component.miuix.ScaleDialog
import com.storage.redirect.x.ui.theme.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@Composable
fun ColorPalettePage(
    themeSettings: AppThemeSettings,
    onThemeSettingsChange: (AppThemeSettings) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val blurSupported = isRuntimeShaderSupported()
    var showScale by remember { mutableStateOf(false) }
    var scaleDraft by
        remember(themeSettings.pageScale) { mutableFloatStateOf(themeSettings.pageScale * 100) }
    val modes =
        listOf(
            stringResource(R.string.theme_system),
            stringResource(R.string.theme_light),
            stringResource(R.string.theme_dark),
        )
    val blurStyles =
        listOf(
            stringResource(R.string.settings_theme_blur_style_gaussian),
            stringResource(R.string.settings_theme_blur_style_progressive),
        )
    ScreenScaffold(title = stringResource(R.string.settings_theme_title), onBack = onBack) {
        item { SmallTitle(stringResource(R.string.settings_theme_group_color)) }
        groupedCardItems(
            "colors",
            listOf(
                CardItem("mode") {
                    ChoicePreference(
                        title = stringResource(R.string.settings_theme_mode),
                        items = modes,
                        selectedIndex =
                            themeSettings.colorMode.toNonMonetMode().value.coerceIn(0, 2),
                        onSelect = {
                            onThemeSettingsChange(
                                themeSettings.copy(colorMode = ColorMode.fromValue(it))
                            )
                        },
                    )
                },
                CardItem("monet") {
                    SwitchPreference(
                        title = stringResource(R.string.settings_theme_monet),
                        summary = stringResource(R.string.settings_theme_monet_summary),
                        checked = themeSettings.miuixMonet,
                        onCheckedChange = {
                            onThemeSettingsChange(themeSettings.copy(miuixMonet = it))
                        },
                    )
                    AnimatedVisibility(
                        visible = themeSettings.miuixMonet,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        Column {
                            ChoicePreference(
                                title = stringResource(R.string.settings_theme_palette_style),
                                items = ThemePaletteStyle.entries.map { it.label() },
                                selectedIndex = themeSettings.paletteStyle.ordinal,
                                onSelect = {
                                    onThemeSettingsChange(
                                        themeSettings.copy(
                                            paletteStyle = ThemePaletteStyle.entries[it]
                                        )
                                    )
                                },
                            )
                            ChoicePreference(
                                title = stringResource(R.string.settings_theme_accent_title),
                                items =
                                    ThemeAccentColor.entries.map { stringResource(it.labelRes) },
                                selectedIndex =
                                    ThemeAccentColor.entries
                                        .indexOfFirst { it.value == themeSettings.keyColor }
                                        .coerceAtLeast(0),
                                onSelect = {
                                    onThemeSettingsChange(
                                        themeSettings.copy(
                                            keyColor = ThemeAccentColor.entries[it].value
                                        )
                                    )
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.settings_theme_pure_black),
                                summary =
                                    stringResource(R.string.settings_theme_pure_black_summary),
                                checked = themeSettings.pureBlack,
                                onCheckedChange = {
                                    onThemeSettingsChange(themeSettings.copy(pureBlack = it))
                                },
                            )
                        }
                    }
                },
            ),
        )
        item { SmallTitle(stringResource(R.string.settings_theme_group_interface)) }
        groupedCardItems(
            "interface",
            listOf(
                CardItem("blur") {
                    SwitchPreference(
                        title = stringResource(R.string.settings_theme_blur),
                        summary = stringResource(R.string.settings_theme_blur_summary),
                        checked = themeSettings.enableBlur && blurSupported,
                        onCheckedChange = {
                            onThemeSettingsChange(themeSettings.copy(enableBlur = it))
                        },
                        enabled = blurSupported,
                    )
                    AnimatedVisibility(
                        visible = themeSettings.enableBlur && blurSupported,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_theme_blur_style),
                            summary =
                                stringResource(
                                    if (themeSettings.topBarBlurStyle == TopBarBlurStyle.Gaussian)
                                        R.string.settings_theme_blur_style_gaussian_summary
                                    else R.string.settings_theme_blur_style_progressive_summary
                                ),
                            items = blurStyles,
                            selectedIndex = themeSettings.topBarBlurStyle.ordinal,
                            onSelectedIndexChange = {
                                onThemeSettingsChange(
                                    themeSettings.copy(
                                        topBarBlurStyle = TopBarBlurStyle.entries[it]
                                    )
                                )
                            },
                        )
                    }
                },
                CardItem("scale") {
                    ArrowPreference(
                        title = stringResource(R.string.settings_theme_density_scale),
                        endActions = { Text("${scaleDraft.toInt()}%") },
                        onClick = { showScale = true },
                        holdDownState = showScale,
                        bottomAction = {
                            Slider(
                                value = scaleDraft,
                                onValueChange = { scaleDraft = it },
                                valueRange = 80f..110f,
                                onValueChangeFinished = {
                                    onThemeSettingsChange(
                                        themeSettings.copy(pageScale = scaleDraft / 100f)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                },
            ),
        )
    }
    ScaleDialog(
        show = showScale,
        onDismissRequest = { showScale = false },
        volumeState = { themeSettings.pageScale },
        onVolumeChange = { onThemeSettingsChange(themeSettings.copy(pageScale = it)) },
    )
}

@Composable
private fun ThemePaletteStyle.label(): String =
    stringResource(
        when (this) {
            ThemePaletteStyle.Neutral -> R.string.settings_theme_palette_neutral
            ThemePaletteStyle.Vibrant -> R.string.settings_theme_palette_vibrant
            ThemePaletteStyle.Expressive -> R.string.settings_theme_palette_expressive
            ThemePaletteStyle.Rainbow -> R.string.settings_theme_palette_rainbow
            ThemePaletteStyle.FruitSalad -> R.string.settings_theme_palette_fruit_salad
            ThemePaletteStyle.Monochrome -> R.string.settings_theme_palette_monochrome
            ThemePaletteStyle.Fidelity -> R.string.settings_theme_palette_fidelity
            ThemePaletteStyle.Content -> R.string.settings_theme_palette_content
            else -> R.string.settings_theme_palette_tonal_spot
        }
    )
