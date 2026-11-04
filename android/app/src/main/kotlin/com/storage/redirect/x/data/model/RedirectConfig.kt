package com.storage.redirect.x.data.model

// 真实路径规则以前缀区分类型，与核心配置解析保持一致
enum class RealPathRuleMode(val prefix: String) {
    Allow(""),
    Exclude("!"),
    ReadOnly("^");

    fun next(): RealPathRuleMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(rule: String): RealPathRuleMode {
            val value = rule.trim()
            return entries.firstOrNull { it.prefix.isNotEmpty() && value.startsWith(it.prefix) } ?: Allow
        }
    }
}

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
        val cleanedPaths = normalizeAllowedPaths(config.allowedRealPaths)

        val mappingByRequest = linkedMapOf<String, StoragePathMapping>()
        for (mapping in config.pathMappings) {
            val requestPath = normalizeMappingPath(mapping.requestPath, config.packageName) ?: continue
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
        // 编辑结果移到末尾，与同一路径的其他规则去重时以本次编辑为准
        val newPaths = existing.allowedRealPaths.filterIndexed { i, _ -> i != index } + path
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
        // 后端始终恢复真实 Android 目录，视为隐含的允许规则
        private const val ANDROID_DIRECTORY = "Android"

        // 标准化允许路径规则：支持 ! 与 ^ 前缀和通配符
        fun normalizeAllowedPath(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null

            val mode = RealPathRuleMode.of(value)
            var body = normalizeRelativePath(value.drop(mode.prefix.length).trimStart()) ?: return null
            if (mode == RealPathRuleMode.Exclude) {
                body = normalizeExcludedHiddenSegments(body)
            }
            return mode.prefix + body
        }

        // 同一路径只保留一条规则，靠后的覆盖靠前的；再按最近的祖先规则剔除不生效的规则
        // 后端先恢复允许与只读目录，再挂排除，最后整体改只读，子挂载保留原有读写属性
        fun normalizeAllowedPaths(paths: List<String>): List<String> {
            val rulesByPath = paths.mapNotNull { normalizeAllowedPath(it) }
                .associateBy { it.drop(RealPathRuleMode.of(it).prefix.length).lowercase() }
            val keptRules = mutableListOf(ANDROID_DIRECTORY to RealPathRuleMode.Allow)
            for (rule in rulesByPath.values.sortedBy { it.count { c -> c == '/' } }) {
                val mode = RealPathRuleMode.of(rule)
                val path = rule.drop(mode.prefix.length)
                // 按深度加入，最后一个匹配的就是最近的祖先；通配符规则无法静态判断，原样保留
                val ancestorMode = keptRules.lastOrNull { isPathCoveredBy(path, it.first) }?.second
                val isEffective = containsWildcard(path) || when (mode) {
                    // 祖先已恢复则重复，祖先为只读则一起只读，祖先被排除则被遮住
                    RealPathRuleMode.Allow -> ancestorMode == null
                    // 排除只作用于已恢复的真实目录
                    RealPathRuleMode.Exclude ->
                        ancestorMode == RealPathRuleMode.Allow || ancestorMode == RealPathRuleMode.ReadOnly
                    RealPathRuleMode.ReadOnly -> ancestorMode != RealPathRuleMode.ReadOnly
                }
                if (isEffective) keptRules += path to mode
            }
            return keptRules.drop(1).map { (path, mode) -> mode.prefix + path }.sorted()
        }

        // 祖先或同一路径；祖先可以是通配符规则，存储按大小写不敏感处理
        private fun isPathCoveredBy(path: String, ancestor: String): Boolean {
            val segments = path.split('/')
            val ancestorSegments = ancestor.split('/')
            if (ancestorSegments.size > segments.size) return false
            return ancestorSegments.zip(segments).all { (pattern, segment) ->
                val regex = pattern.map { c ->
                    when (c) {
                        '*' -> ".*"
                        '?' -> "."
                        else -> Regex.escape(c.toString())
                    }
                }.joinToString("")
                Regex(regex, RegexOption.IGNORE_CASE).matches(segment)
            }
        }

        // 标准化映射路径：仅接受普通相对路径，不支持规则前缀和通配符；privateOwner 允许该应用自己的私有子目录
        fun normalizeMappingPath(raw: String, privateOwner: String? = null): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            if (RealPathRuleMode.of(value) != RealPathRuleMode.Allow) return null
            if (containsWildcard(value)) return null
            return normalizeRelativePath(value, privateOwner)
        }

        // 校验真实路径规则：允许 ! 与 ^ 前缀和通配符
        fun validateAllowedPath(raw: String): PathValidationResult {
            val rawValue = raw.trim()
            if (rawValue.isEmpty()) {
                return PathValidationResult.Empty
            }

            val mode = RealPathRuleMode.of(rawValue)
            val validation = validateRelativePath(rawValue.drop(mode.prefix.length).trimStart())
            if (validation !is PathValidationResult.Valid) {
                return validation
            }

            val body = if (mode == RealPathRuleMode.Exclude) {
                normalizeExcludedHiddenSegments(validation.normalized)
            } else {
                validation.normalized
            }
            return PathValidationResult.Valid(mode.prefix + body)
        }

        // 后端始终恢复真实 Android 目录，只有 Android/media 下的排除与只读规则有意义
        fun validateRealPathRule(raw: String): PathValidationResult {
            val validation = validateAllowedPath(raw)
            if (validation !is PathValidationResult.Valid) return validation
            val mode = RealPathRuleMode.of(validation.normalized)
            val segments = validation.normalized.drop(mode.prefix.length).split('/')
            if (!segments[0].equals(ANDROID_DIRECTORY, ignoreCase = true)) return validation
            val isMediaPath = segments.getOrNull(1).equals("media", ignoreCase = true)
            return if (mode != RealPathRuleMode.Allow && isMediaPath) validation
            else PathValidationResult.AndroidAlwaysAllowed
        }

        // 校验映射路径：不允许规则前缀和通配符；privateOwner 允许该应用自己的私有子目录
        fun validateMappingPath(raw: String, privateOwner: String? = null): PathValidationResult {
            val value = raw.trim()
            if (value.isEmpty()) {
                return PathValidationResult.Empty
            }
            if (RealPathRuleMode.of(value) != RealPathRuleMode.Allow) {
                return PathValidationResult.ExclusionNotAllowed
            }
            if (containsWildcard(value)) {
                return PathValidationResult.WildcardNotAllowed
            }
            return validateRelativePath(value, privateOwner)
        }

        private fun validateRelativePath(raw: String, privateOwner: String? = null): PathValidationResult {
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
                && !isOwnPrivateSubdirectory(segments, privateOwner)
            ) {
                return PathValidationResult.AndroidDataPath
            }
            return PathValidationResult.Valid(segments.joinToString("/"))
        }

        // 包根目录与 sdcard 沙盒不可作为映射挂载点，否则会遮住整个沙盒
        private fun isOwnPrivateSubdirectory(segments: List<String>, privateOwner: String?): Boolean =
            privateOwner != null &&
                segments.size >= 4 &&
                segments[2] == privateOwner &&
                !segments[3].equals("sdcard", ignoreCase = true)

        private fun normalizeRelativePath(raw: String, privateOwner: String? = null): String? {
            var value = raw.trim()
            if (value.isEmpty()) return null
            while (value.length > 1 && value.endsWith("/")) {
                value = value.dropLast(1)
            }
            val validation = validateRelativePath(value, privateOwner)
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
    data object AndroidAlwaysAllowed : PathValidationResult()
    data object WildcardNotAllowed : PathValidationResult()
    data object ExclusionNotAllowed : PathValidationResult()
}
