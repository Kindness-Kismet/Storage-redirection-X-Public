package com.storage.redirect.x.data.repository

import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Paths

// 日志仓库：读取/清空运行日志
class LogRepository {

    // 文件修改时间缓存
    private val lastModificationTimes = mutableMapOf<String, Long>()

    suspend fun load(lineCount: Int): String? {
        if (!RootService.isRootAvailable()) return null
        val result = RootService.runCommand(
            "tail -n $lineCount ${Paths.RUNNING_LOG} 2>/dev/null"
        )
        return if (result.isSuccess) result.stdout else null
    }

    suspend fun clear(): Boolean {
        if (!RootService.isRootAvailable()) return false
        return RootService.runCommand("echo \"\" > ${Paths.RUNNING_LOG}").isSuccess
    }

    // 检查日志文件是否发生变化（用于自动刷新）
    suspend fun hasLogFileChanged(): Boolean {
        val path = Paths.RUNNING_LOG
        val result = RootService.runCommand("stat -c %Y $path 2>/dev/null")
        val modTime = result.stdout.trim().toLongOrNull() ?: return false
        val lastModTime = lastModificationTimes[path]
        if (lastModTime != null && modTime <= lastModTime) return false
        lastModificationTimes[path] = modTime
        return true
    }
}
