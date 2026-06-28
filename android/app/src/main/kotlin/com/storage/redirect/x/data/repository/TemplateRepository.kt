package com.storage.redirect.x.data.repository

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.AppRedirectConfig
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.RedirectTemplate

private const val KEY_REDIRECT_TEMPLATES = "redirect_templates"

data class TemplateApplyResult(
    val config: RedirectConfig,
    val affectedPackages: Set<String>,
)

enum class TemplateApplyMode {
    Merge,
    Replace,
}

class TemplateRepository {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun loadTemplates(): List<RedirectTemplate> {
        return RedirectTemplate.listFromJson(AppPreferences.getString(KEY_REDIRECT_TEMPLATES))
    }

    fun saveTemplate(template: RedirectTemplate): List<RedirectTemplate> {
        val normalized = template.normalized().copy(updatedAt = System.currentTimeMillis())
        val templates = loadTemplates()
            .filter { it.id != normalized.id }
            .plus(normalized)
            .sortedBy { it.name.lowercase() }
        saveTemplates(templates)
        return templates
    }

    fun deleteTemplate(templateId: String): List<RedirectTemplate> {
        val templates = loadTemplates().filter { it.id != templateId }
        saveTemplates(templates)
        return templates
    }

    fun createTemplateFromApp(name: String, appConfig: AppRedirectConfig): RedirectTemplate {
        return RedirectTemplate(
            name = name,
            allowedRealPaths = appConfig.allowedRealPaths,
            pathMappings = appConfig.pathMappings,
        ).normalized()
    }

    fun applyTemplate(
        config: RedirectConfig,
        packageName: String,
        sharedPackages: List<String>,
        template: RedirectTemplate,
        mode: TemplateApplyMode,
    ): TemplateApplyResult {
        val affectedPackages = linkedSetOf(packageName)
        affectedPackages.addAll(sharedPackages)

        var result = config
        for (targetPackage in affectedPackages) {
            val existing = result.getAppConfig(targetPackage)
            val current = existing ?: AppRedirectConfig(packageName = targetPackage, isEnabled = true)
            val next = when (mode) {
                TemplateApplyMode.Merge -> current.copy(
                    isEnabled = true,
                    allowedRealPaths = current.allowedRealPaths + template.allowedRealPaths,
                    pathMappings = current.pathMappings + template.pathMappings,
                )
                TemplateApplyMode.Replace -> current.copy(
                    isEnabled = true,
                    allowedRealPaths = template.allowedRealPaths,
                    pathMappings = template.pathMappings,
                )
            }
            result = result.updateAppConfig(next)
        }

        return TemplateApplyResult(result, affectedPackages)
    }

    fun templatesToBackupJson(): JsonArray {
        val array = JsonArray()
        loadTemplates().forEach { array.add(it.toJson()) }
        return array
    }

    fun restoreTemplatesFromBackup(backupJson: JsonObject) {
        val templatesElement = backupJson.get("templates") ?: return
        if (!templatesElement.isJsonArray) return
        val templates = templatesElement.asJsonArray.mapNotNull { element ->
            try {
                if (element.isJsonObject) RedirectTemplate.fromJson(element.asJsonObject) else null
            } catch (_: Exception) {
                null
            }
        }
        saveTemplates(templates)
    }

    private fun saveTemplates(templates: List<RedirectTemplate>) {
        val root = JsonObject()
        val array = JsonArray()
        templates.map { it.normalized() }.sortedBy { it.name.lowercase() }.forEach { array.add(it.toJson()) }
        root.addProperty("version", 1)
        root.add("templates", array)
        AppPreferences.put(KEY_REDIRECT_TEMPLATES, gson.toJson(root))
    }
}
