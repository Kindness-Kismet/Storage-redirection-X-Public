package com.storage.redirect.x.data.repository

import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.service.RootService

// 目录浏览数据源，读取当前用户存储目录并转为相对路径
class PathBrowserRepository {

    // 列出相对目录，默认根目录为 /storage/emulated/{userId}
    suspend fun listRelativeDirectories(userId: Int, relativePath: String): List<String>? {
        val normalized = normalizeRelativePath(relativePath) ?: return null
        val baseDir = "/storage/emulated/$userId"
        val currentDir = if (normalized.isEmpty()) baseDir else "$baseDir/$normalized"

        // 仅列目录并保留一层名称，避免文件与额外元信息干扰
        val command = "ls -1 -p '$currentDir' 2>/dev/null"
        val result = RootService.runCommand(command)
        if (!result.isSuccess) {
            return emptyList()
        }

        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.endsWith("/") }
            .map { it.removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .mapNotNull { child ->
                val candidate = if (normalized.isEmpty()) child else "$normalized/$child"
                RedirectConfig.normalizeAllowedPath(candidate)
            }
            .sorted()
            .toList()
    }

    // 列出应用私有 sdcard 目录下的子目录
    suspend fun listAppPrivateDirectories(
        userId: Int,
        packageName: String,
        relativePath: String
    ): List<String>? {
        val normalized = normalizeSimpleRelativePath(relativePath) ?: return null
        val baseDir = "/storage/emulated/$userId/Android/data/$packageName/sdcard"
        val currentDir = if (normalized.isEmpty()) baseDir else "$baseDir/$normalized"

        val command = "ls -1 -p '$currentDir' 2>/dev/null"
        val result = RootService.runCommand(command)
        if (!result.isSuccess) return emptyList()

        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.endsWith("/") }
            .map { it.removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .map { child ->
                if (normalized.isEmpty()) child else "$normalized/$child"
            }
            .sorted()
            .toList()
    }

    private fun normalizeRelativePath(raw: String): String? {
        if (raw.isBlank()) return ""
        val validated = RedirectConfig.validateAllowedPath(raw)
        return when (validated) {
            is PathValidationResult.Valid -> validated.normalized
            else -> null
        }
    }

    // 不含 Android 前缀检查的简易路径规范化
    private fun normalizeSimpleRelativePath(raw: String): String? {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.startsWith("/") || trimmed.contains("..")) return null
        return trimmed
    }
}
