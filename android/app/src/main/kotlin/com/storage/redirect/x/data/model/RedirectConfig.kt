package com.storage.redirect.x.data.model

// 整体重定向配置（白名单模式）
data class RedirectConfig(
    val userId: Int = 0,
    val isFileMonitorEnabled: Boolean = true,
    val isFuseFixerEnabled: Boolean = false,
    val redirectApps: List<String> = emptyList(),
    val appConfigs: List<AppRedirectConfig> = emptyList()
) {
    // 检查应用是否在白名单中
    fun isAppInWhitelist(packageName: String): Boolean =
        redirectApps.contains(packageName)

    // 获取应用配置
    fun getAppConfig(packageName: String): AppRedirectConfig? =
        appConfigs.firstOrNull { it.packageName == packageName }

    // 添加应用到白名单
    fun addApp(packageName: String): RedirectConfig {
        if (redirectApps.contains(packageName)) return this
        val existing = getAppConfig(packageName)
        val newConfig = existing?.copy(isEnabled = true)
            ?: AppRedirectConfig(packageName = packageName, isEnabled = true)
        return updateAppConfig(newConfig)
    }

    // 从白名单移除应用（禁用不等于删除，保留配置方便恢复）
    fun removeApp(packageName: String): RedirectConfig {
        val existing = getAppConfig(packageName)
            ?: return copy(redirectApps = redirectApps.filter { it != packageName })
        return updateAppConfig(existing.copy(isEnabled = false))
    }

    // 更新应用配置
    fun updateAppConfig(config: AppRedirectConfig): RedirectConfig {
        // 标准化路径
        val cleanedPaths = config.allowedRealPaths
            .mapNotNull { normalizeAllowedPath(it) }
            .toSortedSet().toList()

        val mappingByRequest = linkedMapOf<String, StoragePathMapping>()
        for (mapping in config.pathMappings) {
            val requestPath = normalizeMappingPath(mapping.requestPath) ?: continue
            val finalPath = normalizeMappingPath(mapping.finalPath) ?: continue
            if (requestPath == finalPath) continue
            mappingByRequest[requestPath] = StoragePathMapping(requestPath, finalPath)
        }
        val cleanedMappings = mappingByRequest.values.sortedBy { it.requestPath }

        val cleanedConfig = config.copy(
            allowedRealPaths = cleanedPaths,
            pathMappings = cleanedMappings,
        )

        val newConfigs = appConfigs
            .filter { it.packageName != cleanedConfig.packageName }
            .plus(cleanedConfig)

        val newRedirectApps = if (cleanedConfig.isEnabled) {
            if (redirectApps.contains(cleanedConfig.packageName)) redirectApps
            else redirectApps + cleanedConfig.packageName
        } else {
            redirectApps.filter { it != cleanedConfig.packageName }
        }

        return copy(redirectApps = newRedirectApps, appConfigs = newConfigs)
    }

    // 添加允许访问的真实路径
    fun addAllowedRealPath(packageName: String, path: String): RedirectConfig {
        val existing = getAppConfig(packageName)
        val newConfig = existing?.copy(
            allowedRealPaths = existing.allowedRealPaths + path
        ) ?: AppRedirectConfig(
            packageName = packageName, isEnabled = true, allowedRealPaths = listOf(path)
        )
        return updateAppConfig(newConfig)
    }

    // 移除允许访问的真实路径
    fun removeAllowedRealPath(packageName: String, index: Int): RedirectConfig {
        val existing = getAppConfig(packageName) ?: return this
        if (index >= existing.allowedRealPaths.size) return this
        val newPaths = existing.allowedRealPaths.toMutableList().apply { removeAt(index) }
        return updateAppConfig(existing.copy(allowedRealPaths = newPaths))
    }

    // 更新允许访问的真实路径
    fun updateAllowedRealPath(packageName: String, index: Int, path: String): RedirectConfig {
        val existing = getAppConfig(packageName) ?: return this
        if (index >= existing.allowedRealPaths.size) return this
        val newPaths = existing.allowedRealPaths.toMutableList().apply { set(index, path) }
        return updateAppConfig(existing.copy(allowedRealPaths = newPaths))
    }

    // 添加路径映射
    fun addPathMapping(packageName: String, mapping: StoragePathMapping): RedirectConfig {
        val existing = getAppConfig(packageName)
        val newConfig = existing?.copy(
            pathMappings = existing.pathMappings + mapping
        ) ?: AppRedirectConfig(
            packageName = packageName, isEnabled = true, pathMappings = listOf(mapping)
        )
        return updateAppConfig(newConfig)
    }

    // 移除路径映射
    fun removePathMapping(packageName: String, index: Int): RedirectConfig {
        val existing = getAppConfig(packageName) ?: return this
        if (index >= existing.pathMappings.size) return this
        val newMappings = existing.pathMappings.toMutableList().apply { removeAt(index) }
        return updateAppConfig(existing.copy(pathMappings = newMappings))
    }

    // 更新路径映射
    fun updatePathMapping(packageName: String, index: Int, mapping: StoragePathMapping): RedirectConfig {
        val existing = getAppConfig(packageName) ?: return this
        if (index >= existing.pathMappings.size) return this
        val newMappings = existing.pathMappings.toMutableList().apply { set(index, mapping) }
        return updateAppConfig(existing.copy(pathMappings = newMappings))
    }

    // 设置应用配置（用于同步共享 UID 配置）
    fun setAppConfig(packageName: String, sourceConfig: AppRedirectConfig): RedirectConfig {
        val newConfig = sourceConfig.copy(packageName = packageName)
        return updateAppConfig(newConfig)
    }

    companion object {
        // 标准化允许路径规则：支持 ! 前缀和通配符
        fun normalizeAllowedPath(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null

            val (isExcluded, bodyRaw) = if (value.startsWith("!")) {
                true to value.drop(1).trimStart()
            } else {
                false to value
            }

            var body = normalizeRelativePath(bodyRaw) ?: return null
            if (isExcluded) {
                body = normalizeExcludedHiddenSegments(body)
            }
            return if (isExcluded) "!$body" else body
        }

        // 标准化映射路径：仅接受普通相对路径，不支持 ! 和通配符
        fun normalizeMappingPath(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            if (value.startsWith("!")) return null
            if (containsWildcard(value)) return null
            return normalizeRelativePath(value)
        }

        // 校验真实路径规则：允许 ! 前缀和通配符
        fun validateAllowedPath(raw: String): PathValidationResult {
            val rawValue = raw.trim()
            if (rawValue.isEmpty()) {
                return PathValidationResult.Empty
            }

            val (isExcluded, bodyRaw) = if (rawValue.startsWith("!")) {
                true to rawValue.drop(1).trimStart()
            } else {
                false to rawValue
            }

            val validation = validateRelativePath(bodyRaw)
            if (validation !is PathValidationResult.Valid) {
                return validation
            }

            val body = if (isExcluded) {
                normalizeExcludedHiddenSegments(validation.normalized)
            } else {
                validation.normalized
            }
            val normalized = if (isExcluded) "!$body" else body
            return PathValidationResult.Valid(normalized)
        }

        // 校验映射路径：不允许 ! 前缀和通配符
        fun validateMappingPath(raw: String): PathValidationResult {
            val value = raw.trim()
            if (value.isEmpty()) {
                return PathValidationResult.Empty
            }
            if (value.startsWith("!")) {
                return PathValidationResult.ExclusionNotAllowed
            }
            if (containsWildcard(value)) {
                return PathValidationResult.WildcardNotAllowed
            }
            return validateRelativePath(value)
        }

        private fun validateRelativePath(raw: String): PathValidationResult {
            val value = raw.trim()
            if (value.isEmpty()) {
                return PathValidationResult.Empty
            }
            if (value.startsWith("/")) {
                return PathValidationResult.Absolute
            }
            val segments = value.split("/").filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                return PathValidationResult.Empty
            }
            if (segments.any { it == "." || it == ".." }) {
                return PathValidationResult.Traversal
            }
            if (segments.size >= 2
                && segments[0].equals("Android", ignoreCase = true)
                && segments[1].equals("data", ignoreCase = true)
            ) {
                return PathValidationResult.AndroidDataPath
            }
            return PathValidationResult.Valid(segments.joinToString("/"))
        }

        private fun normalizeRelativePath(raw: String): String? {
            var value = raw.trim()
            if (value.isEmpty()) return null
            while (value.length > 1 && value.endsWith("/")) {
                value = value.dropLast(1)
            }
            val validation = validateRelativePath(value)
            return if (validation is PathValidationResult.Valid) validation.normalized else null
        }

        private fun containsWildcard(value: String): Boolean =
            value.contains('*') || value.contains('?')

        private fun normalizeExcludedHiddenSegments(path: String): String =
            path.split("/").joinToString("/") { segment ->
                normalizeExcludedHiddenSegment(segment)
            }

        private fun normalizeExcludedHiddenSegment(segment: String): String {
            if (!segment.startsWith("!.") || segment.length <= 2) return segment
            val normalized = segment.drop(1)
            if (normalized == "." || normalized == "..") return segment
            return normalized
        }
    }
}

sealed class PathValidationResult {
    data class Valid(val normalized: String) : PathValidationResult()
    data object Empty : PathValidationResult()
    data object Absolute : PathValidationResult()
    data object Traversal : PathValidationResult()
    data object AndroidDataPath : PathValidationResult()
    data object WildcardNotAllowed : PathValidationResult()
    data object ExclusionNotAllowed : PathValidationResult()
}
