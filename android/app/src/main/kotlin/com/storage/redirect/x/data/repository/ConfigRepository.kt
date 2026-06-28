package com.storage.redirect.x.data.repository

import android.util.Base64
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.storage.redirect.x.data.model.AppRedirectConfig
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Logger
import com.storage.redirect.x.util.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 配置仓库：读写全局配置和应用配置
class ConfigRepository {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // 加载完整配置
    suspend fun load(userId: Int): RedirectConfig {
        Logger.debug("Load config: userId=$userId")
        val globalConfig = readGlobalConfig()
        val appConfigs = readAllAppConfigs(userId)
        val redirectApps = appConfigs.filter { it.isEnabled }.map { it.packageName }

        Logger.debug("Load config done: redirected=${redirectApps.size}, total=${appConfigs.size}")
        return RedirectConfig(
            userId = userId,
            isFileMonitorEnabled = globalConfig.isFileMonitorEnabled,
            isFuseFixerEnabled = globalConfig.isFuseFixerEnabled,
            redirectApps = redirectApps,
            appConfigs = appConfigs,
        )
    }

    suspend fun loadPackages(userId: Int, packageNames: Collection<String>): RedirectConfig {
        val targetPackages = packageNames.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
        Logger.debug("Load partial config: userId=$userId, appCount=${targetPackages.size}")
        val globalConfig = readGlobalConfig()
        val appConfigs = targetPackages.mapNotNull { readAppConfig(it, userId) }
        val redirectApps = appConfigs.filter { it.isEnabled }.map { it.packageName }
        return RedirectConfig(
            userId = userId,
            isFileMonitorEnabled = globalConfig.isFileMonitorEnabled,
            isFuseFixerEnabled = globalConfig.isFuseFixerEnabled,
            redirectApps = redirectApps,
            appConfigs = appConfigs,
        )
    }

    // 保存完整配置
    suspend fun save(config: RedirectConfig): Boolean = withContext(Dispatchers.IO) {
        Logger.debug("Save config: userId=${config.userId}, appCount=${config.appConfigs.size}")
        if (!writeGlobalConfig(config.isFileMonitorEnabled, config.isFuseFixerEnabled)) {
            Logger.error("Save global config failed")
            return@withContext false
        }
        if (!ensureAppsConfigDir()) {
            Logger.error("Prepare app config directory failed")
            return@withContext false
        }

        for (appConfig in config.appConfigs) {
            if (!writeAppConfigFile(appConfig, config.userId)) {
                Logger.error("Save app config failed: ${appConfig.packageName}")
                return@withContext false
            }
        }
        Logger.debug("Config saved")
        true
    }

    // 仅保存受影响的应用配置，避免小改动触发整份配置重写
    suspend fun saveAppConfigs(config: RedirectConfig, packageNames: Collection<String>): Boolean =
        withContext(Dispatchers.IO) {
            val targetPackages = packageNames.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSortedSet()
            Logger.debug("Save partial app configs: userId=${config.userId}, appCount=${targetPackages.size}")
            if (targetPackages.isEmpty()) {
                return@withContext true
            }
            if (!ensureAppsConfigDir()) {
                Logger.error("Prepare app config directory failed")
                return@withContext false
            }

            for (packageName in targetPackages) {
                val appConfig = config.getAppConfig(packageName)
                if (appConfig == null) {
                    Logger.debug("App config missing, skip saving: $packageName")
                    continue
                }
                if (!writeAppConfigFile(appConfig, config.userId)) {
                    Logger.error("Save app config failed: $packageName")
                    return@withContext false
                }
            }
            Logger.debug("Partial app configs saved")
            true
        }

    suspend fun setFuseFixerEnabled(isEnabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val globalConfig = readGlobalConfig()
        writeGlobalConfig(globalConfig.isFileMonitorEnabled, isEnabled)
    }

    suspend fun readFuseFixerEnabled(): Boolean = readGlobalConfig().isFuseFixerEnabled

    // 读取全局配置，字段缺失时走保守默认
    private suspend fun readGlobalConfig(): GlobalConfig {
        val content = RootService.readFile(Paths.GLOBAL_CONFIG_FILE)
        if (content.isNullOrBlank()) return GlobalConfig()
        return try {
            val json = JsonParser.parseString(content).asJsonObject
            GlobalConfig(
                isFileMonitorEnabled = json.get("file_monitor_enabled")?.asBoolean ?: true,
                isFuseFixerEnabled = json.get("fuse_fixer_enabled")?.asBoolean ?: false,
            )
        } catch (_: Exception) {
            GlobalConfig()
        }
    }

    private suspend fun readAllAppConfigs(userId: Int): List<AppRedirectConfig> {
        val result = RootService.runCommand(
            "for f in ${Paths.APPS_CONFIG_DIR}/*.json; do " +
                "[ -f \"\$f\" ] || continue; " +
                "name=\${f##*/}; name=\${name%.json}; " +
                "printf '%s\\t' \"\$name\"; base64 \"\$f\" | tr -d '\\n'; printf '\\n'; " +
                "done"
        )
        if (!result.isSuccess || result.stdout.isBlank()) return emptyList()
        return result.stdout.lineSequence()
            .mapNotNull { line -> parseAppConfigLine(line, userId) }
            .sortedBy { it.packageName }
            .toList()
    }

    // 读取单个应用配置
    private suspend fun readAppConfig(packageName: String, userId: Int): AppRedirectConfig? {
        val path = Paths.appConfigFile(packageName)
        val content = RootService.readFile(path) ?: return null
        return parseAppConfigContent(packageName, content, userId)
    }

    private fun parseAppConfigLine(line: String, userId: Int): AppRedirectConfig? {
        val separatorIndex = line.indexOf('\t')
        if (separatorIndex <= 0) return null
        val packageName = line.substring(0, separatorIndex).trim()
        val encoded = line.substring(separatorIndex + 1).trim()
        if (packageName.isEmpty() || encoded.isEmpty()) return null
        val content = try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.error("Decode app config failed: $packageName", e)
            return null
        }
        return parseAppConfigContent(packageName, content, userId)
    }

    private fun parseAppConfigContent(packageName: String, content: String, userId: Int): AppRedirectConfig? {
        if (content.isBlank()) return null
        return try {
            val json = JsonParser.parseString(content).asJsonObject
            val users = json.getAsJsonObject("users") ?: return null
            val userObj = users.getAsJsonObject(userId.toString()) ?: return null
            AppRedirectConfig.fromUserJson(packageName, userObj)
        } catch (e: Exception) {
            Logger.error("Parse app config failed: $packageName", e)
            null
        }
    }

    // 写入全局配置
    private suspend fun writeGlobalConfig(isFileMonitorEnabled: Boolean, isFuseFixerEnabled: Boolean): Boolean {
        if (!ensureConfigDir()) {
            return false
        }
        val globalJson = JsonObject().apply {
            addProperty("file_monitor_enabled", isFileMonitorEnabled)
            addProperty("fuse_fixer_enabled", isFuseFixerEnabled)
        }
        return RootService.writeFile(Paths.GLOBAL_CONFIG_FILE, gson.toJson(globalJson) + "\n")
    }

    // 确保配置目录存在
    private suspend fun ensureConfigDir(): Boolean {
        val result = RootService.runCommand("mkdir -p ${Paths.CONFIG_DIR}")
        return result.isSuccess
    }

    // 确保应用配置目录存在
    private suspend fun ensureAppsConfigDir(): Boolean {
        val result = RootService.runCommand("mkdir -p ${Paths.APPS_CONFIG_DIR}")
        return result.isSuccess
    }

    // 写入单个应用配置，保留其他用户的配置
    private suspend fun writeAppConfigFile(config: AppRedirectConfig, userId: Int): Boolean {
        val path = Paths.appConfigFile(config.packageName)

        val mergedJson = try {
            val existing = RootService.readFile(path)
            if (!existing.isNullOrBlank()) {
                JsonParser.parseString(existing).asJsonObject
            } else {
                JsonObject()
            }
        } catch (_: Exception) {
            JsonObject()
        }

        mergedJson.remove("package")
        mergedJson.remove("enabled")
        mergedJson.remove("allowed_real_paths")

        val usersObj = mergedJson.getAsJsonObject("users") ?: JsonObject()
        usersObj.add(userId.toString(), config.toUserJson())
        mergedJson.add("users", usersObj)

        return RootService.writeFile(path, gson.toJson(mergedJson) + "\n")
    }

    private data class GlobalConfig(
        val isFileMonitorEnabled: Boolean = true,
        val isFuseFixerEnabled: Boolean = false,
    )
}
