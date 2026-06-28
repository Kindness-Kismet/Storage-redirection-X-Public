package com.storage.redirect.x.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@Composable
fun AppTheme(
    settings: AppThemeSettings,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalUiMode provides settings.uiMode,
        LocalColorMode provides settings.colorMode,
    ) {
        // content 在 Miuix/Material 主题间移动时保留内部状态(分页/导航)
        val currentContent by rememberUpdatedState(content)
        val movableContent = remember { movableContentOf { currentContent() } }
        when (settings.uiMode) {
            UiMode.Miuix -> MiuixAppTheme(settings) { movableContent() }
            UiMode.Material -> MaterialAppTheme(settings) {
                MiuixAppTheme(settings) { movableContent() }
            }
        }
    }
}

@Composable
fun AppTheme(
    colorMode: Int = ColorMode.SYSTEM.value,
    keyColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val settings = AppThemeController.readSettings().let { current ->
        current.copy(
            colorMode = ColorMode.fromValue(colorMode),
            keyColor = keyColor?.toArgb() ?: current.keyColor,
        )
    }
    AppTheme(settings = settings, content = content)
}

@Composable
@ReadOnlyComposable
fun isInDarkTheme(themeMode: Int): Boolean {
    return isInDarkTheme(ColorMode.fromValue(themeMode))
}

@Composable
@ReadOnlyComposable
fun isInDarkTheme(colorMode: ColorMode): Boolean {
    return when {
        colorMode == ColorMode.LIGHT || colorMode == ColorMode.MONET_LIGHT -> false
        colorMode.isDark -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
private fun MiuixAppTheme(
    settings: AppThemeSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = isInDarkTheme(settings.colorMode)
    val paletteStyle = try {
        ThemePaletteStyle.valueOf(settings.paletteStyle.name)
    } catch (_: IllegalArgumentException) {
        ThemePaletteStyle.TonalSpot
    }
    val colorSpec = if (settings.colorSpec == ColorSpec.SpecVersion.SPEC_2025) {
        ThemeColorSpec.Spec2025
    } else {
        ThemeColorSpec.Spec2021
    }
    val miuixColorMode = if (settings.miuixMonet) {
        settings.colorMode.toMonetMode()
    } else {
        settings.colorMode.toNonMonetMode()
    }
    val controller = ThemeController(
        colorSchemeMode = miuixColorMode.toMiuixColorSchemeMode(),
        keyColor = settings.keyColor.takeIf { it != 0 }?.let(::Color),
        isDark = darkTheme,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec,
    )

    MiuixTheme(
        controller = controller,
        smoothRounding = settings.smoothRounding,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialAppTheme(
    settings: AppThemeSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = isInDarkTheme(settings.colorMode)
    val colorScheme = if (settings.keyColor == 0) {
        val baseScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        rememberDynamicColorScheme(
            seedColor = Color.Unspecified,
            isDark = darkTheme,
            style = settings.paletteStyle,
            specVersion = settings.colorSpec,
            primary = baseScheme.primary,
            secondary = baseScheme.secondary,
            tertiary = baseScheme.tertiary,
            neutral = baseScheme.surface,
            neutralVariant = baseScheme.surfaceVariant,
            error = baseScheme.error,
        )
    } else {
        rememberDynamicColorScheme(
            seedColor = Color(settings.keyColor),
            isDark = darkTheme,
            style = settings.paletteStyle,
            specVersion = settings.colorSpec,
        )
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

private fun ColorMode.toMiuixColorSchemeMode(): ColorSchemeMode {
    return when (this) {
        ColorMode.SYSTEM -> ColorSchemeMode.System
        ColorMode.LIGHT -> ColorSchemeMode.Light
        ColorMode.DARK -> ColorSchemeMode.Dark
        ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
        ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
        ColorMode.MONET_DARK -> ColorSchemeMode.MonetDark
    }
}
