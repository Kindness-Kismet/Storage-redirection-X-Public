package com.storage.redirect.x.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.PreferenceKeys
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5);

    companion object {
        fun fromValue(value: Int): ColorMode = entries.find { it.value == value } ?: SYSTEM
    }

    val isSystem: Boolean
        get() = this == SYSTEM || this == MONET_SYSTEM

    val isDark: Boolean
        get() = this == DARK || this == MONET_DARK

    val isMonet: Boolean
        get() = this == MONET_SYSTEM || this == MONET_LIGHT || this == MONET_DARK

    fun toNonMonetMode(): ColorMode =
        when (this) {
            MONET_SYSTEM -> SYSTEM
            MONET_LIGHT -> LIGHT
            MONET_DARK -> DARK
            else -> this
        }

    fun toMonetMode(): ColorMode =
        when (this) {
            SYSTEM -> MONET_SYSTEM
            LIGHT -> MONET_LIGHT
            DARK -> MONET_DARK
            else -> this
        }
}

@Immutable
data class AppThemeSettings(
    val colorMode: ColorMode = ColorMode.SYSTEM,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TonalSpot,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.Spec2025,
    val pureBlack: Boolean = false,
    val pageScale: Float = 1.0f,
    val enableBlur: Boolean = true,
    val topBarBlurStyle: TopBarBlurStyle = TopBarBlurStyle.Gaussian,
)

enum class ThemeAccentColor(val value: Int, val labelRes: Int) {
    Default(0, com.storage.redirect.x.R.string.settings_theme_accent_default),
    Blue(0xFF3482FF.toInt(), com.storage.redirect.x.R.string.settings_theme_accent_blue),
    Purple(0xFF6750A4.toInt(), com.storage.redirect.x.R.string.settings_theme_accent_purple),
    Pink(0xFFB0006D.toInt(), com.storage.redirect.x.R.string.settings_theme_accent_pink),
    Red(0xFFBA1A1A.toInt(), com.storage.redirect.x.R.string.settings_theme_accent_red),
    Orange(0xFFB65D00.toInt(), com.storage.redirect.x.R.string.settings_theme_accent_orange),
    Yellow(0xFF7D5700.toInt(), com.storage.redirect.x.R.string.settings_theme_accent_yellow),
    Green(0xFF006D3B.toInt(), com.storage.redirect.x.R.string.settings_theme_accent_green),
    Teal(0xFF006A6A.toInt(), com.storage.redirect.x.R.string.settings_theme_accent_teal),
}

object AppThemeController {
    fun readSettings(): AppThemeSettings {
        val storedColorMode =
            ColorMode.fromValue(
                AppPreferences.getInt(PreferenceKeys.THEME_MODE, ColorMode.SYSTEM.value)
            )
        val miuixMonet =
            AppPreferences.getBoolean(PreferenceKeys.MIUIX_MONET, storedColorMode.isMonet)
        return AppThemeSettings(
            colorMode = if (miuixMonet) storedColorMode.toNonMonetMode() else storedColorMode,
            miuixMonet = miuixMonet,
            keyColor = AppPreferences.getInt(PreferenceKeys.KEY_COLOR, 0),
            paletteStyle =
                enumValueOrDefault(
                    AppPreferences.getString(
                        PreferenceKeys.COLOR_STYLE,
                        ThemePaletteStyle.TonalSpot.name,
                    ),
                    ThemePaletteStyle.TonalSpot,
                ),
            pureBlack = AppPreferences.getBoolean("theme_pure_black", false),
            colorSpec =
                enumValueOrDefault(
                    AppPreferences.getString(
                            PreferenceKeys.COLOR_SPEC,
                            ThemeColorSpec.Spec2025.name,
                        )
                        .let { if (it == "SPEC_2025") "Spec2025" else it },
                    ThemeColorSpec.Spec2025,
                ),
            pageScale =
                AppPreferences.getFloat(PreferenceKeys.PAGE_SCALE, 1.0f).let {
                    if (it.isFinite()) it.coerceIn(0.8f, 1.1f) else 1.0f
                },
            enableBlur = AppPreferences.getBoolean(PreferenceKeys.ENABLE_BLUR, true),
            topBarBlurStyle =
                TopBarBlurStyle.entries.firstOrNull {
                    it.name == AppPreferences.getString("top_bar_blur_style", "Gaussian")
                } ?: TopBarBlurStyle.Gaussian,
        )
    }

    fun writeSettings(settings: AppThemeSettings) {
        AppPreferences.put(PreferenceKeys.THEME_MODE, settings.colorMode.value)
        AppPreferences.put(PreferenceKeys.MIUIX_MONET, settings.miuixMonet)
        AppPreferences.put("theme_pure_black", settings.pureBlack)
        AppPreferences.put(PreferenceKeys.KEY_COLOR, settings.keyColor)
        AppPreferences.put(PreferenceKeys.COLOR_STYLE, settings.paletteStyle.name)
        AppPreferences.put(PreferenceKeys.COLOR_SPEC, settings.colorSpec.name)
        AppPreferences.put(PreferenceKeys.PAGE_SCALE, settings.pageScale)
        AppPreferences.put(PreferenceKeys.ENABLE_BLUR, settings.enableBlur)
        AppPreferences.put("top_bar_blur_style", settings.topBarBlurStyle.name)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
        return try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}

val LocalColorMode = staticCompositionLocalOf { ColorMode.SYSTEM }
val LocalEnableBlur = staticCompositionLocalOf { true }
val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }
val LocalAppDarkMode = staticCompositionLocalOf { false }

enum class TopBarBlurStyle {
    Gaussian,
    Progressive,
}

val LocalAppMonetEnabled = staticCompositionLocalOf { false }
