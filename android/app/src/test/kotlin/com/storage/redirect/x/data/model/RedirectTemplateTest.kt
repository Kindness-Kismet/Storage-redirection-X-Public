package com.storage.redirect.x.data.model

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectTemplateTest {
    @Test
    fun immutableTemplateKeepsTheExistingJsonFormat() {
        val template =
            RedirectTemplate(
                id = "template",
                name = "Photos",
                allowedRealPaths = persistentListOf("DCIM", "Pictures"),
                pathMappings = persistentListOf(StoragePathMapping("Download", "Pictures")),
                createdAt = 1L,
                updatedAt = 2L,
            )
        val json = template.toJson()
        assertEquals("DCIM", json.getAsJsonArray("allowed_real_paths")[0].asString)
        assertEquals("Pictures", json.getAsJsonObject("path_mappings")["Download"].asString)
        assertEquals(template, RedirectTemplate.fromJson(json))
    }

    @Test
    fun normalizingATemplatePreservesTheSourceSnapshot() {
        val original =
            RedirectTemplate(
                id = "template",
                name = "  Photos  ",
                allowedRealPaths = persistentListOf("Pictures", "DCIM", "Pictures"),
            )
        val normalized = original.normalized()
        assertEquals(listOf("DCIM", "Pictures"), normalized.allowedRealPaths)
        assertEquals("Photos", normalized.name)
        assertEquals(3, original.allowedRealPaths.size)
        assertEquals("  Photos  ", original.name)
    }
}
