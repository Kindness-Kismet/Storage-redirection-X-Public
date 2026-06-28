package com.storage.redirect.x.data.repository

import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Paths

// 模块仓库：读取模块安装状态与运行统计信息
class ModuleRepository {

    suspend fun isModuleInstalled(): Boolean {
        if (!RootService.isRootAvailable()) return false
        val result = RootService.runCommand("test -d ${Paths.MODULE_DIR} && echo 1 || echo 0")
        return result.isSuccess && result.stdout.trim() == "1"
    }

    // 从 module.prop 解析已安装模块的版本号
    suspend fun getInstalledVersion(): String? {
        val content = RootService.readFile("${Paths.MODULE_DIR}/module.prop") ?: return null
        return content.lineSequence()
            .firstOrNull { it.startsWith("version=") }
            ?.substringAfter("version=")
            ?.trim()
            ?.ifEmpty { null }
    }

    suspend fun loadRedirectCount(): Int {
        if (!RootService.isRootAvailable()) return 0
        val result = RootService.runCommand(
            "cat ${Paths.GLOBAL_STATS_FILE} 2>/dev/null || " +
                "cat ${Paths.MODULE_DIR}/stats 2>/dev/null || echo 0"
        )
        return if (result.isSuccess) result.stdout.trim().toIntOrNull() ?: 0 else 0
    }
}
