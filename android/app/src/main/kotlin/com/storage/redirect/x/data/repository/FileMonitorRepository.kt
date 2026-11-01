package com.storage.redirect.x.data.repository

import com.google.gson.JsonParser
import com.storage.redirect.x.data.model.FileMonitorEntry
import com.storage.redirect.x.data.service.MonitorService
import com.storage.redirect.x.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MonitorPage(
    val entries: List<FileMonitorEntry> = emptyList(),
    val hasMore: Boolean = false,
    val lost: Long = 0,
    val writeErrors: Long = 0,
    val daemonUpdatedMs: Long = 0,
    val isMonitoringEnabled: Boolean = true,
)

class FileMonitorRepository {
    // 游标按原始记录递进，列表合并不改变数据库中的记录。
    suspend fun loadPage(before: Long = 0, lineCount: Int = 500): MonitorPage? =
        withContext(Dispatchers.IO) { readPage(before, lineCount.coerceIn(1, 500)) }

    private suspend fun readPage(before: Long, lineCount: Int): MonitorPage? {
        val result = MonitorService.query(before.coerceAtLeast(0), lineCount)
        if (!result.isSuccess) return null
        return try {
            val lines = result.stdout.lineSequence().filter { it.isNotBlank() }.toList()
            val header = JsonParser.parseString(lines.firstOrNull()).asJsonObject
            require(header.get("version").asInt == 2)
            fun number(key: String) = header.get(key).asLong
            MonitorPage(
                entries = lines.drop(1).map(FileMonitorEntry::parse),
                hasMore = header.get("has_more").asBoolean,
                lost = number("lost"),
                writeErrors = number("write_errors"),
                daemonUpdatedMs = number("updated_ms"),
                isMonitoringEnabled = header.get("enabled").asBoolean,
            )
        } catch (error: RuntimeException) {
            Logger.error("Parse monitor page failed", error)
            null
        }
    }

    suspend fun loadEntries(lineCount: Int): List<FileMonitorEntry>? = loadPage(lineCount = lineCount)?.entries

    suspend fun clear(): Boolean = MonitorService.clear()
}
