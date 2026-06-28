package com.storage.redirect.x.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

data class RedirectTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val allowedRealPaths: List<String> = emptyList(),
    val pathMappings: List<StoragePathMapping> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    fun normalized(): RedirectTemplate {
        val cleanedPaths = allowedRealPaths
            .mapNotNull { RedirectConfig.normalizeAllowedPath(it) }
            .toSortedSet()
            .toList()
        val mappingByRequest = linkedMapOf<String, StoragePathMapping>()
        for (mapping in pathMappings) {
            val requestPath = RedirectConfig.normalizeMappingPath(mapping.requestPath) ?: continue
            val finalPath = RedirectConfig.normalizeMappingPath(mapping.finalPath) ?: continue
            if (requestPath == finalPath) continue
            mappingByRequest[requestPath] = StoragePathMapping(requestPath, finalPath)
        }
        return copy(
            name = name.trim(),
            allowedRealPaths = cleanedPaths,
            pathMappings = mappingByRequest.values.sortedBy { it.requestPath },
        )
    }

    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", id)
        obj.addProperty("name", name)
        obj.addProperty("created_at", createdAt)
        obj.addProperty("updated_at", updatedAt)

        val paths = JsonArray()
        allowedRealPaths.forEach { paths.add(it) }
        obj.add("allowed_real_paths", paths)

        val mappings = JsonObject()
        pathMappings.forEach { mappings.addProperty(it.requestPath, it.finalPath) }
        obj.add("path_mappings", mappings)
        return obj
    }

    companion object {
        fun fromJson(json: JsonObject): RedirectTemplate? {
            val id = json.get("id")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val name = json.get("name")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val allowedRealPaths = json.getAsJsonArray("allowed_real_paths")
                ?.mapNotNull { element ->
                    if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                        element.asString.trim().takeIf { value -> value.isNotEmpty() }
                    } else {
                        null
                    }
                }
                ?: emptyList()
            val mappings = mutableListOf<StoragePathMapping>()
            val mappingsObj = json.get("path_mappings")
            if (mappingsObj != null && mappingsObj.isJsonObject) {
                mappingsObj.asJsonObject.entrySet().forEach { (requestPathRaw, finalPathElement) ->
                    if (!finalPathElement.isJsonPrimitive || !finalPathElement.asJsonPrimitive.isString) return@forEach
                    val requestPath = requestPathRaw.trim()
                    val finalPath = finalPathElement.asString.trim()
                    if (requestPath.isNotEmpty() && finalPath.isNotEmpty()) {
                        mappings.add(StoragePathMapping(requestPath, finalPath))
                    }
                }
            }
            return RedirectTemplate(
                id = id,
                name = name,
                allowedRealPaths = allowedRealPaths,
                pathMappings = mappings,
                createdAt = json.get("created_at")?.asLong ?: System.currentTimeMillis(),
                updatedAt = json.get("updated_at")?.asLong ?: System.currentTimeMillis(),
            ).normalized()
        }

        fun listFromJson(content: String): List<RedirectTemplate> {
            if (content.isBlank()) return emptyList()
            return try {
                val root = JsonParser.parseString(content)
                val array = when {
                    root.isJsonArray -> root.asJsonArray
                    root.isJsonObject -> root.asJsonObject.getAsJsonArray("templates")
                    else -> null
                } ?: return emptyList()
                array.mapNotNull { element ->
                    if (element.isJsonObject) fromJson(element.asJsonObject) else null
                }.sortedBy { it.name.lowercase() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
