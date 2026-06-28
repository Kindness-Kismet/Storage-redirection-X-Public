package com.storage.redirect.x.ui.component.bottombar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBottomBarSelectionTest {

    @Test
    fun `selection propagation is skipped when target index is unchanged`() {
        assertFalse(shouldDispatchSelection(selectedIndex = 2, targetIndex = 2))
    }

    @Test
    fun `selection propagation runs when target index changes`() {
        assertTrue(shouldDispatchSelection(selectedIndex = 1, targetIndex = 2))
    }
}
