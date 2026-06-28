package com.storage.redirect.x.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectConfigPathTest {

    @Test
    fun `excluded hidden segment is normalized`() {
        val result = RedirectConfig.validateAllowedPath("!Pictures/!.gs")

        assertTrue(result is PathValidationResult.Valid)
        assertEquals("!Pictures/.gs", (result as PathValidationResult.Valid).normalized)
    }

    @Test
    fun `allowed hidden segment keeps literal bang`() {
        val result = RedirectConfig.validateAllowedPath("Pictures/!.gs")

        assertTrue(result is PathValidationResult.Valid)
        assertEquals("Pictures/!.gs", (result as PathValidationResult.Valid).normalized)
    }

    @Test
    fun `excluded parent-like hidden segment keeps literal bang`() {
        val result = RedirectConfig.validateAllowedPath("!Pictures/!..")

        assertTrue(result is PathValidationResult.Valid)
        assertEquals("!Pictures/!..", (result as PathValidationResult.Valid).normalized)
    }
}
