package com.storage.redirect.x.data.service

import android.util.Base64
import com.storage.redirect.x.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Root 命令执行结果
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

// 系统用户信息
data class SystemUser(
    val id: Int,
    val name: String
)

// Root 命令执行服务（直接调用 su，无需 MethodChannel）
// 无状态单例，避免各页面重复创建实例
object RootService {

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            val available = exitCode == 0
            Logger.debug("Root check: available=$available")
            available
        } catch (e: Exception) {
            Logger.error("Root check error: ${e.message}")
            false
        }
    }

    // MediaProvider 一重启，正在运行的重定向 app 里的 FUSE 就失效了，得让它们冷启动重建挂载
    suspend fun restartMediaProvider(redirectApps: List<String> = emptyList()): Boolean {
        val runningApps = filterRunningApps(redirectApps.filter { isSafePackageName(it) })

        val mediaStop = listOf(
            "am force-stop com.google.android.providers.media.module 2>/dev/null",
            "am force-stop com.android.providers.media.module 2>/dev/null",
            "am force-stop com.android.providers.media 2>/dev/null",
        ).joinToString(" ; ")

        // 查询会把新的 MediaProvider 唤起来；丢后台跟杀 app 并行
        val warmup = "( content query --uri content://media/external/file " +
            "--projection _id --where '1=0' >/dev/null 2>&1 ) &"

        val appStops = runningApps
            .joinToString(" ; ") { "am force-stop ${shellQuote(it)} 2>/dev/null" }

        val command = buildString {
            append(mediaStop)
            append(" ; ")
            append(warmup)
            if (appStops.isNotEmpty()) {
                append(' ')
                append(appStops)
                append(" ;")
            }
            append(" wait")
        }

        val result = runCommand(command)
        if (!result.isSuccess) {
            Logger.warn("restart MediaProvider failed: exit=${result.exitCode} stderr=${result.stderr}")
        } else {
            Logger.info("restart MediaProvider done, killed apps=${runningApps.size}")
        }
        return result.isSuccess
    }

    private suspend fun filterRunningApps(packages: List<String>): List<String> {
        if (packages.isEmpty()) return emptyList()
        val result = runCommand("ps -A -o NAME 2>/dev/null")
        if (!result.isSuccess) return packages  // 查不到就保守地全算上
        val running = result.stdout.lineSequence()
            .map { it.trim().substringBefore(':') }  // 进程名可能带 :子进程，取主包名
            .toHashSet()
        return packages.filter { it in running }
    }

    private fun isSafePackageName(packageName: String): Boolean =
        packageName.isNotEmpty() &&
            packageName != "com.storage.redirect.x" &&
            packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun runCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Logger.warn("Command failed [$exitCode]: $command | stderr=$stderr")
            }
            CommandResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Logger.error("Command error: $command | ${e.message}")
            CommandResult(-1, "", e.message ?: "")
        }
    }

    suspend fun readFile(path: String): String? {
        val result = runCommand("cat '$path' 2>/dev/null")
        if (!result.isSuccess) Logger.warn("Read file failed: $path")
        return if (result.isSuccess) result.stdout else null
    }

    // 写入文件：使用 base64 编码避免 shell 特殊字符问题
    suspend fun writeFile(path: String, content: String): Boolean {
        val encoded = Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val result = runCommand("echo '$encoded' | base64 -d > '$path'")
        if (!result.isSuccess) Logger.error("Write file failed: $path")
        return result.isSuccess
    }

    suspend fun getSystemUsers(): List<SystemUser> {
        val result = runCommand("ls -1 /data/system/users/ 2>/dev/null")
        if (!result.isSuccess || result.stdout.isBlank()) {
            Logger.warn("Get user list failed, fallback to Owner")
            return listOf(SystemUser(0, DEFAULT_USER_NAMES[0] ?: "Owner"))
        }

        val users = result.stdout.trim().lines().mapNotNull { line ->
            val userId = line.trim().toIntOrNull() ?: return@mapNotNull null
            if (userId < 0) return@mapNotNull null

            val nameResult = runCommand("getprop 'persist.sys.user.$userId.name'")
            val name = if (nameResult.isSuccess && nameResult.stdout.trim().isNotEmpty()) {
                nameResult.stdout.trim()
            } else {
                DEFAULT_USER_NAMES[userId] ?: "User $userId"
            }
            SystemUser(userId, name)
        }.sortedBy { it.id }

        return users.ifEmpty { listOf(SystemUser(0, DEFAULT_USER_NAMES[0] ?: "Owner")) }
    }

    // 用户名默认值在 UI 层通过 strings.xml 覆盖显示，此处仅作 fallback
    private val DEFAULT_USER_NAMES = mapOf(
        0 to "Owner",
        10 to "Work Profile",
        11 to "Guest",
        12 to "Accessibility",
    )
}
