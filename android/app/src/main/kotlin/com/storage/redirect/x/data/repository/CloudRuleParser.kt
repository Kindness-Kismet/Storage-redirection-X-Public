package com.storage.redirect.x.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.storage.redirect.x.data.model.CloudRule
import com.storage.redirect.x.data.model.CloudRuleCatalog
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.StoragePathMapping
import java.io.StringReader

// 云端目录整体校验；不丢弃异常条目后把部分更新当作完整结果。
object CloudRuleParser {
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val recommendations = setOf(
        "recommended", "unnecessary", "not_recommended", "cause_problems", "depends_on_situations", "custom",
    )
    private val warningCodes = setOf(
        "direct_mapping", "filtered_export", "invalid_export_path", "android_export",
        "unrecognized_structure", "conflicting_exports", "cross_app_access",
        "non_standard_files", "corrected_metadata",
    )

    fun parse(content: String): CloudRuleCatalog {
        val root = JsonReader(StringReader(content)).use { reader ->
            reader.strictness = Strictness.STRICT
            val value = JsonParser.parseReader(reader).asJsonObject
            require(reader.peek() == JsonToken.END_DOCUMENT)
            value
        }
        require(root.long("schema_version") == 1L)
        val revision = root.string("revision")
        require(Regex("[a-f0-9]{64}").matches(revision))
        val entries = root.getAsJsonArray("rules")
        require(entries.size() in 1..10_000)
        val rules = linkedMapOf<String, CloudRule>()
        for (entry in entries) {
            val rule = parseRule(entry.asJsonObject)
            require(rules.put(rule.packageName, rule) == null)
        }
        return CloudRuleCatalog(revision, rules)
    }

    private fun parseRule(json: JsonObject): CloudRule {
        val packageName = json.string("package")
        require(packagePattern.matches(packageName))
        val recommendation = json.string("recommendation")
        require(recommendation in recommendations)
        val warnings = json.strings("warnings").toSet()
        require(warningCodes.containsAll(warnings))
        val paths = json.strings("allowed_real_paths")
        require(paths.size <= 256 && paths.distinct().size == paths.size)
        require(paths.all { isSafePath(it, true) && RedirectConfig.normalizeAllowedPath(it) == it })
        val mappings = json.getAsJsonObject("path_mappings").entrySet().map { (source, target) ->
            val destination = target.stringValue()
            require(isSafePath(source) && isSafePath(destination) && source != destination)
            require(RedirectConfig.normalizeMappingPath(source) == source)
            require(RedirectConfig.normalizeMappingPath(destination) == destination)
            StoragePathMapping(source, destination)
        }
        require(mappings.size <= 256)
        val sourceFile = json.string("upstream_file")
        require(sourceFile.isEmpty() || Regex("[A-Za-z0-9_.-]+\\.json").matches(sourceFile))
        val updatedAt = json.long("updated_at")
        require(updatedAt in 0..253_402_300_799L)
        val description = json.getAsJsonObject("description").entrySet().associate { (locale, text) ->
            require(locale in setOf("en", "zh-CN", "zh-TW"))
            locale to text.stringValue()
        }
        require(description.isNotEmpty())
        val verified = json.get("upstream_verified")
        require(verified.isJsonPrimitive && verified.asJsonPrimitive.isBoolean)
        return CloudRule(
            packageName, recommendation, description,
            json.strings("authors"), verified.asBoolean, updatedAt, sourceFile,
            warnings, paths, mappings,
        )
    }

    private fun isSafePath(path: String, allowPrefix: Boolean = false): Boolean {
        val body = if (allowPrefix && (path.startsWith('!') || path.startsWith('^'))) path.drop(1) else path
        return body.isNotBlank() && path == path.trim() &&
            body.none { it == '\\' || it == '*' || it == '?' || it.code < 32 } &&
            body.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun JsonObject.string(key: String): String = get(key).stringValue()
    private fun JsonObject.long(key: String): Long {
        val value = get(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asString.toLong()
    }
    private fun JsonObject.strings(key: String): List<String> = getAsJsonArray(key).map { it.stringValue() }
    private fun JsonElement.stringValue(): String {
        require(isJsonPrimitive && asJsonPrimitive.isString)
        return asString.also { require(it.length <= 32_768) }
    }
}
