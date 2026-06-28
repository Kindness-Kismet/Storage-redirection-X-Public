package com.storage.redirect.x.data.repository

import com.storage.redirect.x.data.model.FileMonitorEntry
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Paths

// 文件监视仓库：读取/清空文件监视日志
class FileMonitorRepository {

    // 读取最后 N 行监视记录，返回最新在前的列表
    suspend fun loadEntries(lineCount: Int): List<FileMonitorEntry>? {
        if (!RootService.isRootAvailable()) return null

        // 检查文件是否存在
        val checkResult = RootService.runCommand(
            "test -f ${Paths.FILE_MONITOR_LOG} && echo 1 || echo 0"
        )
        if (checkResult.stdout.trim() != "1") return emptyList()

        val result = RootService.runCommand(
            "tail -n $lineCount ${Paths.FILE_MONITOR_LOG} 2>/dev/null"
        )
        if (!result.isSuccess) return null

        return result.stdout.lines()
            .filter { it.trim().isNotEmpty() && !it.startsWith("---------") }
            .map { FileMonitorEntry.parse(it) }
            .reversed()
    }

    suspend fun clear(): Boolean {
        if (!RootService.isRootAvailable()) return false
        return RootService.runCommand("echo \"\" > ${Paths.FILE_MONITOR_LOG}").isSuccess
    }
}
