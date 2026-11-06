package com.storage.redirect.x.data.model

// 云端规则作为开启时的默认值，同一路径仍以已有手动配置为准。
object CloudRuleDefaults {
    fun enable(config: RedirectConfig, packageName: String, rule: CloudRule?): RedirectConfig {
        val current = config.getAppConfig(packageName) ?: AppRedirectConfig(packageName)
        return config.updateAppConfig(current.copy(
            isEnabled = true,
            allowedRealPaths = rule?.allowedRealPaths.orEmpty() + current.allowedRealPaths,
            pathMappings = rule?.pathMappings.orEmpty() + current.pathMappings,
        ))
    }
}
