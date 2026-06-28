package com.storage.redirect.x.ui.component.bottombar

import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.PreferenceKeys

enum class BottomBarBlurMode {
    None,
    Blur,
}

data class BottomBarAppearanceState(
    val isFloatingBottomBarEnabled: Boolean = false,
    val isFloatingBottomBarBlurEnabled: Boolean = false,
) {
    val isBlurSwitchEnabled: Boolean
        get() = isFloatingBottomBarEnabled

    val isBlurEffectActive: Boolean
        get() = blurMode == BottomBarBlurMode.Blur

    val usesBottomBarSlotPadding: Boolean
        get() = !isFloatingBottomBarEnabled

    val usesContentBackdrop: Boolean
        get() = blurMode == BottomBarBlurMode.Blur

    val blurMode: BottomBarBlurMode
        get() = if (isFloatingBottomBarEnabled && isFloatingBottomBarBlurEnabled) {
            BottomBarBlurMode.Blur
        } else {
            BottomBarBlurMode.None
        }

    companion object {
        fun fromPreferences(
            readBoolean: (String, Boolean) -> Boolean = AppPreferences::getBoolean,
        ): BottomBarAppearanceState {
            return BottomBarAppearanceState(
                isFloatingBottomBarEnabled = readBoolean(
                    PreferenceKeys.FLOATING_BOTTOM_BAR_ENABLED,
                    false,
                ),
                isFloatingBottomBarBlurEnabled = readBoolean(
                    PreferenceKeys.FLOATING_BOTTOM_BAR_BLUR_ENABLED,
                    false,
                ),
            )
        }
    }
}
