package com.storage.redirect.x.data.model

// 存储路径映射：当应用访问 requestPath 时，改为访问 finalPath
@androidx.compose.runtime.Immutable
data class StoragePathMapping(
    val requestPath: String,
    val finalPath: String,
)
