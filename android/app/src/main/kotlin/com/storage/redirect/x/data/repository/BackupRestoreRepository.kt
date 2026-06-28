package com.storage.redirect.x.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.storage.redirect.x.data.model.AppRedirectConfig
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Logger
import com.storage.redirect.x.util.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

data class BackupExportResult(
    val isSuccess: Boolean,
    val packageCount: Int,
)

data class RestoreResult(
    val isSuccess: Boolean,
    val restoredPackageCount: Int,
)

// 备份与还原仓库：负责包名配置的导出与恢复。
class BackupRestoreRepository {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val packageNameRegex = Regex("^[A-Za-z0-9_.]+$")
    private val templateRepo = TemplateRepository()

    // 备份所有包名配置到统一文件。
    suspend fun backupPackageConfigs(): BackupExportResult {
        if (!RootService.isRootAvailable()) {
            Logger.warn("Backup package configs failed: root unavailable")
            return BackupExportResult(isSuccess = false, packageCount = 0)
        }

        if (!RootService.runCommand("mkdir -p ${Paths.BACKUP_DIR}").isSuccess) {
            Logger.error("Backup package configs failed: cannot create backup directory")
            return BackupExportResult(isSuccess = false, packageCount = 0)
        }

        val backupJson = JsonObject().apply {
            addProperty("version", 1)
            addProperty("created_at", System.currentTimeMillis())
        }
        val packagesObj = JsonObject()

        val listResult = RootService.runCommand("ls ${Paths.APPS_CONFIG_DIR}/*.json 2>/dev/null")
        if (listResult.isSuccess) {
            val configPaths = listResult.stdout.trim().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.endsWith(".json") }

            for (configPath in configPaths) {
                val fileName = configPath.substringAfterLast('/')
                val packageName = fileName.removeSuffix(".json")
                if (!packageNameRegex.matches(packageName)) {
                    Logger.warn("Skip invalid package config: $packageName")
                    continue
                }

                val content = RootService.readFile(configPath)
                if (content.isNullOrBlank()) {
                    Logger.warn("Skip empty config file: $configPath")
                    continue
                }

                val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                packagesObj.addProperty(packageName, encoded)
            }
        }

        backupJson.add("packages", packagesObj)
        backupJson.add("templates", templateRepo.templatesToBackupJson())
        val backupContent = gson.toJson(backupJson) + "\n"
        val packageCount = packagesObj.entrySet().size
        val isSuccess = RootService.writeFile(Paths.APPS_CONFIG_BACKUP_FILE, backupContent)
        if (isSuccess) {
            Logger.info("Backup package configs succeeded: ${Paths.APPS_CONFIG_BACKUP_FILE}, packageCount=$packageCount")
        } else {
            Logger.error("Backup package configs failed: write backup file failed")
        }
        return BackupExportResult(isSuccess = isSuccess, packageCount = packageCount)
    }

    // 导出包名配置备份到 SAF 选中的 ZIP 文件。
    suspend fun exportPackageConfigsZip(contentResolver: ContentResolver, outputUri: Uri): BackupExportResult {
        if (!RootService.isRootAvailable()) {
            Logger.warn("Export package configs failed: root unavailable")
            return BackupExportResult(isSuccess = false, packageCount = 0)
        }

        val backupResult = backupPackageConfigs()
        if (!backupResult.isSuccess) {
            Logger.error("Export package configs failed: local backup generation failed")
            return backupResult
        }

        val backupContent = RootService.readFile(Paths.APPS_CONFIG_BACKUP_FILE)
        if (backupContent.isNullOrBlank()) {
            Logger.error("Export package configs failed: local backup content is empty")
            return BackupExportResult(isSuccess = false, packageCount = backupResult.packageCount)
        }

        val zipSuccess = withContext(Dispatchers.IO) {
            try {
                val outputStream = contentResolver.openOutputStream(outputUri, "w")
                if (outputStream == null) {
                    Logger.error("Export package configs failed: cannot open output stream")
                    return@withContext false
                }

                outputStream.use { stream ->
                    ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                        val entry = ZipEntry(Paths.APPS_CONFIG_BACKUP_FILE.substringAfterLast('/'))
                        zip.putNextEntry(entry)
                        zip.write(backupContent.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }

                Logger.info("Export package configs ZIP succeeded: $outputUri")
                true
            } catch (e: Exception) {
                Logger.error("Export package configs ZIP failed", e)
                false
            }
        }

        return BackupExportResult(isSuccess = zipSuccess, packageCount = backupResult.packageCount)
    }

    // 从本地备份文件恢复包名配置。
    suspend fun restorePackageConfigs(): RestoreResult {
        if (!RootService.isRootAvailable()) {
            Logger.warn("Restore package configs failed: root unavailable")
            return RestoreResult(isSuccess = false, restoredPackageCount = 0)
        }

        val backupContent = RootService.readFile(Paths.APPS_CONFIG_BACKUP_FILE)
        if (backupContent.isNullOrBlank()) {
            Logger.error("Restore package configs failed: backup file missing or empty")
            return RestoreResult(isSuccess = false, restoredPackageCount = 0)
        }

        return restorePackageConfigsFromContent(backupContent, Paths.APPS_CONFIG_BACKUP_FILE)
    }

    // 从 SAF 选中的备份文件恢复包名配置。
    suspend fun restorePackageConfigsFromUri(contentResolver: ContentResolver, inputUri: Uri): RestoreResult {
        if (!RootService.isRootAvailable()) {
            Logger.warn("Restore package configs failed: root unavailable")
            return RestoreResult(isSuccess = false, restoredPackageCount = 0)
        }

        val backupContent = loadBackupContentFromUri(contentResolver, inputUri)
        if (backupContent.isNullOrBlank()) {
            Logger.error("Restore package configs failed: selected file is empty or unsupported: $inputUri")
            return RestoreResult(isSuccess = false, restoredPackageCount = 0)
        }

        val normalizedContent = ensureTrailingNewline(backupContent)
        val restoreResult = restorePackageConfigsFromContent(normalizedContent, inputUri.toString())
        if (!restoreResult.isSuccess) {
            return restoreResult
        }

        val prepareBackupDirResult = RootService.runCommand("mkdir -p ${Paths.BACKUP_DIR}")
        if (!prepareBackupDirResult.isSuccess) {
            Logger.warn("Restore package configs: cannot create backup directory, skip local backup cache refresh")
        } else if (!RootService.writeFile(Paths.APPS_CONFIG_BACKUP_FILE, normalizedContent)) {
            Logger.warn("Restore package configs: cannot refresh local backup cache")
        }

        return restoreResult
    }

    private suspend fun loadBackupContentFromUri(contentResolver: ContentResolver, inputUri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(inputUri)
                if (inputStream == null) {
                    Logger.error("Restore package configs failed: cannot open input stream: $inputUri")
                    return@withContext null
                }

                val bytes = inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) {
                    return@withContext null
                }

                extractBackupJsonFromZip(bytes)?.let { return@withContext it }
                if (looksLikeZip(bytes)) {
                    return@withContext null
                }

                val text = bytes.toString(Charsets.UTF_8)
                if (text.isBlank()) {
                    return@withContext null
                }
                text
            } catch (e: Exception) {
                Logger.error("Restore package configs failed: read selected file error", e)
                null
            }
        }
    }

    private fun extractBackupJsonFromZip(zipBytes: ByteArray): String? {
        if (!looksLikeZip(zipBytes)) {
            return null
        }

        val targetName = Paths.APPS_CONFIG_BACKUP_FILE.substringAfterLast('/')
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var fallbackJson: String? = null
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryContent = readCurrentZipEntryToString(zip)
                        if (!entryContent.isNullOrBlank()) {
                            val entryName = entry.name.substringAfterLast('/')
                            if (entryName == targetName) {
                                return entryContent
                            }
                            if (entryName.endsWith(".json") && fallbackJson == null) {
                                fallbackJson = entryContent
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                return fallbackJson
            }
        } catch (e: Exception) {
            Logger.error("Restore package configs failed: parse ZIP error", e)
            return null
        }
    }

    private fun readCurrentZipEntryToString(zip: ZipInputStream): String? {
        val output = ByteArrayOutputStream()
        zip.copyTo(output)
        val content = output.toString(Charsets.UTF_8.name())
        if (content.isBlank()) {
            return null
        }
        return content
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4) {
            return false
        }
        return bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    }

    private fun ensureTrailingNewline(content: String): String {
        return if (content.endsWith("\n")) content else "$content\n"
    }

    private fun isValidPackageConfigContent(packageName: String, content: String): Boolean {
        return try {
            val json = JsonParser.parseString(content).asJsonObject
            val users = json.getAsJsonObject("users") ?: return false
            users.entrySet().isNotEmpty() && users.entrySet().all { (_, userElement) ->
                userElement.isJsonObject && runCatching {
                    AppRedirectConfig.fromUserJson(packageName, userElement.asJsonObject)
                }.isSuccess
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun restorePackageConfigsFromContent(backupContent: String, source: String): RestoreResult {
        val backupJson = try {
            JsonParser.parseString(backupContent).asJsonObject
        } catch (e: Exception) {
            Logger.error("Restore package configs failed: invalid backup file format", e)
            return RestoreResult(isSuccess = false, restoredPackageCount = 0)
        }
        if (!backupJson.has("packages") || !backupJson.get("packages").isJsonObject) {
            Logger.warn("Restore package configs failed: missing packages object")
            return RestoreResult(isSuccess = false, restoredPackageCount = 0)
        }
        val packagesObj = backupJson.getAsJsonObject("packages")

        data class RestorePayload(
            val content: String,
        )

        val payloads = linkedMapOf<String, RestorePayload>()

        for ((packageName, encodedElement) in packagesObj.entrySet()) {
            if (!packageNameRegex.matches(packageName)) {
                Logger.warn("Restore package configs failed: invalid package name $packageName")
                return RestoreResult(isSuccess = false, restoredPackageCount = 0)
            }
            if (!encodedElement.isJsonPrimitive || !encodedElement.asJsonPrimitive.isString) {
                Logger.warn("Restore package configs failed: invalid package data format $packageName")
                return RestoreResult(isSuccess = false, restoredPackageCount = 0)
            }

            val decoded = try {
                val bytes = Base64.decode(encodedElement.asString, Base64.DEFAULT)
                bytes.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                Logger.error("Restore package configs failed: Base64 decode failed $packageName", e)
                return RestoreResult(isSuccess = false, restoredPackageCount = 0)
            }
            if (!isValidPackageConfigContent(packageName, decoded)) {
                Logger.warn("Restore package configs failed: invalid package config content $packageName")
                return RestoreResult(isSuccess = false, restoredPackageCount = 0)
            }

            val normalizedContent = if (decoded.endsWith("\n")) decoded else "$decoded\n"
            payloads[packageName] = RestorePayload(
                content = normalizedContent,
            )
        }

        val tempDir = "${Paths.CONFIG_DIR}/apps.restore_tmp"
        val prepareResult = RootService.runCommand("rm -rf $tempDir && mkdir -p $tempDir")
        if (!prepareResult.isSuccess) {
            Logger.error("Restore package configs failed: cannot prepare temp directory")
            return RestoreResult(isSuccess = false, restoredPackageCount = 0)
        }

        var restoredPackageCount = 0
        for ((packageName, payload) in payloads) {
            if (!RootService.writeFile("$tempDir/$packageName.json", payload.content)) {
                RootService.runCommand("rm -rf $tempDir")
                Logger.error("Restore package configs failed: write temp failed $packageName")
                return RestoreResult(isSuccess = false, restoredPackageCount = restoredPackageCount)
            }
            restoredPackageCount++
        }

        val replaceResult = RootService.runCommand("mkdir -p ${Paths.CONFIG_DIR} && rm -rf ${Paths.APPS_CONFIG_DIR} && mv $tempDir ${Paths.APPS_CONFIG_DIR}")
        if (!replaceResult.isSuccess) {
            RootService.runCommand("rm -rf $tempDir")
            Logger.error("Restore package configs failed: cannot replace config directory")
            return RestoreResult(isSuccess = false, restoredPackageCount = 0)
        }

        templateRepo.restoreTemplatesFromBackup(backupJson)

        Logger.info("Restore package configs succeeded: source=${source}, restored=${restoredPackageCount}")
        return RestoreResult(isSuccess = true, restoredPackageCount = restoredPackageCount)
    }
}
