// Adapted from Mishka (YuKongA), GPL-3.0.
package com.storage.redirect.x.ui.component.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.storage.redirect.x.ui.theme.LocalAppDarkMode
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BgEffectBackground(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    alpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    if (!isRuntimeShaderSupported()) {
        Box(modifier = modifier, content = content)
        return
    }
    Box(modifier = modifier) {
        val surface = MiuixTheme.colorScheme.surface
        val deviceType = DeviceType.PHONE
        val isDarkTheme = LocalAppDarkMode.current
        val painter = remember { BgEffectPainter() }

        val preset =
            remember(deviceType, isDarkTheme) {
                BgEffectConfig.get(deviceType, isDarkTheme)
            }

        val colorStage = remember { Animatable(0f) }

        val currentAlpha by rememberUpdatedState(alpha)

        LaunchedEffect(dynamicBackground, preset) {
            if (!dynamicBackground) return@LaunchedEffect
            val animatesColors =
                preset.colors1 !== preset.colors2 || preset.colors2 !== preset.colors3
            if (!animatesColors) return@LaunchedEffect

            var targetStage = floor(colorStage.value) + 1f
            while (isActive) {
                // 不可见时挂起色阶推进：draw 里读 colorStage，spring 每帧都会让它重绘。
                // snapshotFlow 在协程里读 alpha，不像 by 解包那样把它带进组合期
                snapshotFlow { currentAlpha() > 0f }.first { it }
                delay((preset.colorInterpPeriod * 500).toLong())
                colorStage.animateTo(
                    targetValue = targetStage,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
                )
                targetStage += 1f
            }
        }

        Spacer(
            modifier =
                Modifier.fillMaxSize()
                    .then(bgModifier)
                    .bgEffectDraw(
                        painter = painter,
                        preset = preset,
                        deviceType = deviceType,
                        isDarkTheme = isDarkTheme,
                        surface = surface,
                        effectBackground = effectBackground,
                        isFullSize = isFullSize,
                        playing = dynamicBackground,
                        colorStage = { colorStage.value },
                        alpha = alpha,
                    )
        )
        content()
    }
}
