package com.storage.redirect.x.ui.component.bottombar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarPressAnimationTest {

    companion object {
        private const val CURRENT_VALUE = 2f
        private const val VALUE_VISIBILITY_THRESHOLD = 0.001f
        private const val WITHIN_VALUE_VISIBILITY_THRESHOLD_TARGET = 2.0005f
        private const val VALUE_VISIBILITY_THRESHOLD_TARGET = 2.001f
        private const val BELOW_TOLERANCE_BAND_TARGET = 2.000998f
        private const val WITHIN_TOLERANCE_BAND_TARGET = 2.0009995f
        private const val DISTINCT_TARGET = 2.01f
        private const val RELEASE_SETTLE_THRESHOLD_VALUE_RANGE_START = 0f
        private const val RELEASE_SETTLE_THRESHOLD_VALUE_RANGE_END = 4f
        private const val RELEASE_SETTLE_THRESHOLD = 0.1f
        private const val WITHIN_RELEASE_SETTLE_THRESHOLD_TARGET = 3.91f
        private const val OUTSIDE_RELEASE_SETTLE_THRESHOLD_TARGET = 3.899f
        private const val RELEASE_SETTLE_BOUNDARY_CURRENT_VALUE = 0f
        private const val RELEASE_SETTLE_BOUNDARY_TARGET_VALUE = 0.1f
    }

    @Test
    fun `animation is skipped when target already matches current value`() {
        assertFalse(shouldAnimateToValue(currentValue = CURRENT_VALUE, targetValue = CURRENT_VALUE, valueVisibilityThreshold = VALUE_VISIBILITY_THRESHOLD))
    }

    @Test
    fun `animation is skipped when target stays within threshold`() {
        assertFalse(
            shouldAnimateToValue(
                currentValue = CURRENT_VALUE,
                targetValue = WITHIN_VALUE_VISIBILITY_THRESHOLD_TARGET,
                valueVisibilityThreshold = VALUE_VISIBILITY_THRESHOLD,
            ),
        )
    }

    @Test
    fun `animation runs when target reaches threshold`() {
        assertTrue(
            shouldAnimateToValue(
                currentValue = CURRENT_VALUE,
                targetValue = VALUE_VISIBILITY_THRESHOLD_TARGET,
                valueVisibilityThreshold = VALUE_VISIBILITY_THRESHOLD,
            ),
        )
    }

    @Test
    fun `animation still skips when difference stays below threshold outside tolerance band`() {
        assertFalse(
            shouldAnimateToValue(
                currentValue = CURRENT_VALUE,
                targetValue = BELOW_TOLERANCE_BAND_TARGET,
                valueVisibilityThreshold = VALUE_VISIBILITY_THRESHOLD,
            ),
        )
    }

    @Test
    fun `animation runs when difference enters tolerance band below threshold`() {
        assertTrue(
            shouldAnimateToValue(
                currentValue = CURRENT_VALUE,
                targetValue = WITHIN_TOLERANCE_BAND_TARGET,
                valueVisibilityThreshold = VALUE_VISIBILITY_THRESHOLD,
            ),
        )
    }

    @Test
    fun `animation runs when target differs from current value`() {
        assertTrue(
            shouldAnimateToValue(
                currentValue = CURRENT_VALUE,
                targetValue = DISTINCT_TARGET,
                valueVisibilityThreshold = VALUE_VISIBILITY_THRESHOLD,
            ),
        )
    }

    @Test
    fun `release settle threshold uses value range fraction`() {
        assertTrue(
            calculateReleaseSettleThreshold(
                valueRange = RELEASE_SETTLE_THRESHOLD_VALUE_RANGE_START..RELEASE_SETTLE_THRESHOLD_VALUE_RANGE_END,
            ) == RELEASE_SETTLE_THRESHOLD,
        )
    }

    @Test
    fun `release settle runs only when difference stays below threshold`() {
        assertTrue(
            shouldReleaseSettle(
                currentValue = WITHIN_RELEASE_SETTLE_THRESHOLD_TARGET,
                targetValue = 4f,
                releaseSettleThreshold = RELEASE_SETTLE_THRESHOLD,
            ),
        )
        assertFalse(
            shouldReleaseSettle(
                currentValue = OUTSIDE_RELEASE_SETTLE_THRESHOLD_TARGET,
                targetValue = 4f,
                releaseSettleThreshold = RELEASE_SETTLE_THRESHOLD,
            ),
        )
    }

    @Test
    fun `release settle keeps waiting when difference reaches threshold`() {
        assertFalse(
            shouldReleaseSettle(
                currentValue = RELEASE_SETTLE_BOUNDARY_CURRENT_VALUE,
                targetValue = RELEASE_SETTLE_BOUNDARY_TARGET_VALUE,
                releaseSettleThreshold = RELEASE_SETTLE_THRESHOLD,
            ),
        )
    }
}
