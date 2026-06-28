package com.storage.redirect.x.ui.component.bottombar

import com.storage.redirect.x.data.cache.PreferenceKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarAppearanceStateTest {

    @Test
    fun `fromPreferences returns disabled defaults when no preference is stored`() {
        val state = BottomBarAppearanceState.fromPreferences { _, default -> default }

        assertFalse(state.isFloatingBottomBarEnabled)
        assertFalse(state.isFloatingBottomBarBlurEnabled)
        assertFalse(state.isBlurSwitchEnabled)
        assertFalse(state.isBlurEffectActive)
    }

    @Test
    fun `blur switch becomes editable only after floating bottom bar is enabled`() {
        val disabledState = BottomBarAppearanceState(
            isFloatingBottomBarEnabled = false,
            isFloatingBottomBarBlurEnabled = true,
        )
        val enabledState = disabledState.copy(isFloatingBottomBarEnabled = true)

        assertFalse(disabledState.isBlurSwitchEnabled)
        assertTrue(enabledState.isBlurSwitchEnabled)
    }

    @Test
    fun `blur effect is active only when both switches are enabled`() {
        val floatingDisabled = BottomBarAppearanceState(
            isFloatingBottomBarEnabled = false,
            isFloatingBottomBarBlurEnabled = true,
        )
        val blurDisabled = BottomBarAppearanceState(
            isFloatingBottomBarEnabled = true,
            isFloatingBottomBarBlurEnabled = false,
        )
        val allEnabled = BottomBarAppearanceState(
            isFloatingBottomBarEnabled = true,
            isFloatingBottomBarBlurEnabled = true,
        )

        assertFalse(floatingDisabled.isBlurEffectActive)
        assertFalse(blurDisabled.isBlurEffectActive)
        assertTrue(allEnabled.isBlurEffectActive)
    }

    @Test
    fun `floating mode uses overlay content padding instead of bottom bar slot padding`() {
        val normalState = BottomBarAppearanceState()
        val floatingState = BottomBarAppearanceState(isFloatingBottomBarEnabled = true)

        assertTrue(normalState.usesBottomBarSlotPadding)
        assertFalse(floatingState.usesBottomBarSlotPadding)
    }

    @Test
    fun `blur switch selects plain blur mode when enabled`() {
        val disabledState = BottomBarAppearanceState()
        val enabledState = BottomBarAppearanceState(
            isFloatingBottomBarEnabled = true,
            isFloatingBottomBarBlurEnabled = true,
        )

        assertTrue(disabledState.blurMode == BottomBarBlurMode.None)
        assertTrue(enabledState.blurMode == BottomBarBlurMode.Blur)
    }

    @Test
    fun `content backdrop is used only for floating blur mode`() {
        val normalState = BottomBarAppearanceState()
        val floatingState = BottomBarAppearanceState(isFloatingBottomBarEnabled = true)
        val blurState = BottomBarAppearanceState(
            isFloatingBottomBarEnabled = true,
            isFloatingBottomBarBlurEnabled = true,
        )

        assertFalse(normalState.usesContentBackdrop)
        assertFalse(floatingState.usesContentBackdrop)
        assertTrue(blurState.usesContentBackdrop)
    }

    @Test
    fun `stored blur preference stays inactive until floating mode is enabled`() {
        val state = BottomBarAppearanceState.fromPreferences { key, default ->
            when (key) {
                PreferenceKeys.FLOATING_BOTTOM_BAR_ENABLED -> false
                PreferenceKeys.FLOATING_BOTTOM_BAR_BLUR_ENABLED -> true
                else -> default
            }
        }

        assertFalse(state.isFloatingBottomBarEnabled)
        assertTrue(state.isFloatingBottomBarBlurEnabled)
        assertFalse(state.isBlurEffectActive)
        assertFalse(state.usesContentBackdrop)
    }

    @Test
    fun `fromPreferences reads current preference keys`() {
        val reads = linkedMapOf<String, Boolean>()

        BottomBarAppearanceState.fromPreferences { key, default ->
            reads[key] = default
            key == PreferenceKeys.FLOATING_BOTTOM_BAR_BLUR_ENABLED
        }

        assertTrue(reads.containsKey(PreferenceKeys.FLOATING_BOTTOM_BAR_ENABLED))
        assertTrue(reads.containsKey(PreferenceKeys.FLOATING_BOTTOM_BAR_BLUR_ENABLED))
    }
}
