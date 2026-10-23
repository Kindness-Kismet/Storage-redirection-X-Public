// Adapted from Mishka (YuKongA), GPL-3.0.
package com.storage.redirect.x.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import com.storage.redirect.x.ui.theme.LocalEnableBlur
import com.storage.redirect.x.ui.theme.TopBarBlurStyle
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalTopBarBlurStyle = staticCompositionLocalOf { TopBarBlurStyle.Gaussian }

@Composable
fun Modifier.defaultBlurEffect(backdrop: LayerBackdrop): Modifier =
    this.textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 25f,
        colors =
            BlurColors(
                blendColors =
                    listOf(BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)))
            ),
    )

@Composable
fun rememberBlurEnabled(): State<Boolean> =
    rememberUpdatedState(LocalEnableBlur.current && isRuntimeShaderSupported())

@Composable
fun rememberBlurBackdrop(enabled: Boolean = LocalEnableBlur.current): LayerBackdrop? {
    if (!enabled || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = rememberBlurEnabled().value,
    blurStyle: TopBarBlurStyle = LocalTopBarBlurStyle.current,
    useProgressive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val progressive = useProgressive && blurStyle == TopBarBlurStyle.Progressive
    Box(
        modifier =
            if (blurActive && backdrop != null && !progressive) {
                Modifier.defaultBlurEffect(backdrop)
            } else {
                Modifier
            }
    ) {
        if (blurActive && backdrop != null && progressive) {
            Box(
                modifier =
                    Modifier.matchParentSize()
                        .progressiveTextureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                            blurRadius = 10f,
                            colors =
                                BlurColors(
                                    blendColors =
                                        listOf(
                                            BlendColorEntry(
                                                color = MiuixTheme.colorScheme.surface.copy(0.3f)
                                            )
                                        )
                                ),
                        )
            )
        }
        content()
    }
}
