package com.storage.redirect.x.data.repository

import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.ui.component.ModuleStatus
import com.storage.redirect.x.util.Paths

// 模块仓库：读取模块安装状态与运行统计信息
class ModuleRepository {

    suspend fun getModuleStatus(): ModuleStatus {
        if (!RootService.isRootAvailable()) return ModuleStatus.NOT_INSTALLED
        val script = """
            if [ ! -d "${Paths.MODULE_DIR}" ]; then
                echo "NOT_INSTALLED"
            elif [ -f "${Paths.MODULE_DIR}/disable" ] || [ -f "${Paths.MODULE_DIR}/remove" ]; then
                echo "DISABLED"
            elif pidof srx_logd >/dev/null 2>&1 || ( [ -f "${Paths.LOGS_DIR}/.logd.pid" ] && kill -0 "$$(cat "${Paths.LOGS_DIR}/.logd.pid" 2>/dev/null)" 2>/dev/null ); then
                echo "INSTALLED"
            else
                echo "NOT_RUNNING"
            fi
        """.trimIndent()
        val result = RootService.runCommand(script)
        if (!result.isSuccess) return ModuleStatus.NOT_INSTALLED
        return when (result.stdout.trim()) {
            "INSTALLED" -> ModuleStatus.INSTALLED
            "DISABLED" -> ModuleStatus.DISABLED
            "NOT_RUNNING" -> ModuleStatus.NOT_RUNNING
            else -> ModuleStatus.NOT_INSTALLED
        }
    }

    suspend fun isModuleInstalled(): Boolean = getModuleStatus() == ModuleStatus.INSTALLED

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
            "cat ${Paths.MODULE_DIR}/stats 2>/dev/null || cat /data/adb/storage-redirect-x/stats 2>/dev/null || echo 0"
        )
        return if (result.isSuccess) result.stdout.trim().toIntOrNull() ?: 0 else 0
    }
}
