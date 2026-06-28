package com.storage.redirect.x.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.PreferenceKeys

enum class UiMode(val value: String) {
    Miuix("miuix"),
    Material("material");

    companion object {
        val DEFAULT_VALUE = Miuix.value

        fun fromValue(value: String): UiMode = when (value) {
            Material.value -> Material
            else -> Miuix
        }
    }
}

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

    val isSystem: Boolean get() = this == SYSTEM || this == MONET_SYSTEM
    val isDark: Boolean get() = this == DARK || this == MONET_DARK
    val isMonet: Boolean get() = this == MONET_SYSTEM || this == MONET_LIGHT || this == MONET_DARK

    fun toNonMonetMode(): ColorMode = when (this) {
        MONET_SYSTEM -> SYSTEM
        MONET_LIGHT -> LIGHT
        MONET_DARK -> DARK
        else -> this
    }

    fun toMonetMode(): ColorMode = when (this) {
        SYSTEM -> MONET_SYSTEM
        LIGHT -> MONET_LIGHT
        DARK -> MONET_DARK
        else -> this
    }
}

data class AppThemeSettings(
    val uiMode: UiMode = UiMode.Miuix,
    val colorMode: ColorMode = ColorMode.SYSTEM,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.Default,
    val smoothRounding: Boolean = true,
    val pageScale: Float = 1.0f,
    val enableBlur: Boolean = false,
)

val keyColorOptions = listOf(
    Color(0xFFF44336).toArgb(),
    Color(0xFFE91E63).toArgb(),
    Color(0xFF9C27B0).toArgb(),
    Color(0xFF673AB7).toArgb(),
    Color(0xFF3F51B5).toArgb(),
    Color(0xFF2196F3).toArgb(),
    Color(0xFF00BCD4).toArgb(),
    Color(0xFF009688).toArgb(),
    Color(0xFF4FAF50).toArgb(),
    Color(0xFFFFEB3B).toArgb(),
    Color(0xFFFFC107).toArgb(),
    Color(0xFFFF9800).toArgb(),
    Color(0xFF795548).toArgb(),
    Color(0xFF607D8F).toArgb(),
    Color(0xFFFF9CA8).toArgb(),
)

object AppThemeController {
    fun readSettings(): AppThemeSettings {
        val storedColorMode = ColorMode.fromValue(
            AppPreferences.getInt(PreferenceKeys.THEME_MODE, ColorMode.SYSTEM.value),
        )
        val miuixMonet = AppPreferences.getBoolean(PreferenceKeys.MIUIX_MONET, storedColorMode.isMonet)
        return AppThemeSettings(
            uiMode = UiMode.fromValue(
                AppPreferences.getString(PreferenceKeys.UI_MODE, UiMode.DEFAULT_VALUE),
            ),
            colorMode = if (miuixMonet) storedColorMode.toNonMonetMode() else storedColorMode,
            miuixMonet = miuixMonet,
            keyColor = AppPreferences.getInt(PreferenceKeys.KEY_COLOR, 0),
            paletteStyle = enumValueOrDefault(
                AppPreferences.getString(PreferenceKeys.COLOR_STYLE, PaletteStyle.TonalSpot.name),
                PaletteStyle.TonalSpot,
            ),
            colorSpec = enumValueOrDefault(
                AppPreferences.getString(PreferenceKeys.COLOR_SPEC, ColorSpec.SpecVersion.Default.name),
                ColorSpec.SpecVersion.Default,
            ),
            smoothRounding = AppPreferences.getBoolean(PreferenceKeys.SMOOTH_ROUNDING, true),
            pageScale = AppPreferences.getFloat(PreferenceKeys.PAGE_SCALE, 1.0f),
            enableBlur = AppPreferences.getBoolean(PreferenceKeys.ENABLE_BLUR, false),
        )
    }

    fun writeSettings(settings: AppThemeSettings) {
        AppPreferences.put(PreferenceKeys.UI_MODE, settings.uiMode.value)
        AppPreferences.put(PreferenceKeys.THEME_MODE, settings.colorMode.value)
        AppPreferences.put(PreferenceKeys.MIUIX_MONET, settings.miuixMonet)
        AppPreferences.put(PreferenceKeys.KEY_COLOR, settings.keyColor)
        AppPreferences.put(PreferenceKeys.COLOR_STYLE, settings.paletteStyle.name)
        AppPreferences.put(PreferenceKeys.COLOR_SPEC, settings.colorSpec.name)
        AppPreferences.put(PreferenceKeys.SMOOTH_ROUNDING, settings.smoothRounding)
        AppPreferences.put(PreferenceKeys.PAGE_SCALE, settings.pageScale)
        AppPreferences.put(PreferenceKeys.ENABLE_BLUR, settings.enableBlur)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
        return try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}

val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }
val LocalColorMode = staticCompositionLocalOf { ColorMode.SYSTEM }
val LocalEnableBlur = staticCompositionLocalOf { false }
