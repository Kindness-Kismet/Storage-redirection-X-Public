package com.storage.redirect.x.data.model

data class CloudRuleCatalog(
    val revision: String,
    val rulesByPackage: Map<String, CloudRule>,
)

data class CloudRule(
    val packageName: String,
    val recommendation: String,
    val description: Map<String, String>,
    val authors: List<String>,
    val isUpstreamVerified: Boolean,
    val updatedAtSeconds: Long,
    val upstreamFile: String,
    val warnings: Set<String>,
    val allowedRealPaths: List<String>,
    val pathMappings: List<StoragePathMapping>,
) {
    val canEnableBasicRedirect: Boolean get() = recommendation == "recommended"

    fun descriptionFor(languageTag: String): String {
        val preferred = when {
            languageTag.startsWith("zh", true) &&
                listOf("TW", "HK", "Hant").any { languageTag.contains(it, true) } -> "zh-TW"
            languageTag.startsWith("zh", true) -> "zh-CN"
            else -> "en"
        }
        return description[preferred] ?: description["en"] ?: description["zh-CN"]
            ?: description.values.firstOrNull().orEmpty()
    }

    fun toTemplate(selectedPaths: Set<String>, selectedMappings: Set<String>): RedirectTemplate =
        RedirectTemplate(
            id = "cloud:$packageName",
            name = packageName,
            allowedRealPaths = allowedRealPaths.filter { it in selectedPaths },
            pathMappings = pathMappings.filter { it.requestPath in selectedMappings },
        )
}
