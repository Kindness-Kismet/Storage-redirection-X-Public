// Adapted from Mishka (YuKongA), GPL-3.0.
package com.storage.redirect.x.ui.component.blur

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

internal object ColorBlendToken {

    val Pured_Regular_Light =
        listOf(
            BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
            BlendColorEntry(Color(0xB3FFFFFF.toInt()), BlurBlendMode.HardLight),
        )

    val Overlay_Thin_Light =
        listOf(
            BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
            BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
        )
}
