package com.storage.redirect.x.ui.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsUiStateTest {

    @Test
    fun `default state keeps only settings page workflow flags`() {
        val state = SettingsUiState()

        assertFalse(state.isLogsPageVisible)
        assertFalse(state.isLicensesPageVisible)
        assertFalse(state.isUpdatePageVisible)
        assertNull(state.hasRoot)
        assertFalse(state.isWorking)
        assertFalse(state.isFuseFixerEnabled)
    }
}
