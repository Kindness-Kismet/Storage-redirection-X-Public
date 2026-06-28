package com.storage.redirect.x.data.model

// 应用信息
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val firstInstallTime: Long = 0L,
    val userId: Int = 0
)
