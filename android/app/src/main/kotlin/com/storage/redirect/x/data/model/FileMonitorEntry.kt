package com.storage.redirect.x.data.model

import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class FileMonitorEntry(
    val timestamp: String,
    val packageName: String,
    val caller: String,
    val opType: String,
    val path: String,
    val resultCode: Int? = null,
    val errorNo: Int? = null,
    val extra: String? = null,
    val sequence: Long = 0,
    val timeMillis: Long = 0,
    val lastTimeMillis: Long = timeMillis,
    val count: Long = 1,
    val bytes: Long = 0,
    val nativeResult: Long? = resultCode?.toLong(),
    val lostRecords: Long = 0,
    val processId: Int = 0,
    val uid: Int = 0,
    val identifyMethod: String = "",
) {
    // 监视链路只接收主用户共享存储路径，展示时省略固定根目录；多用户接入时需改为保留用户标识
    val displayPath: String get() = path.removePrefix(PRIMARY_STORAGE_ROOT).ifEmpty { "/" }

    // 共享 UID 保留整体身份，不能随意归到其中一个应用。
    val actorIdentity: String get() = caller.ifBlank { packageName }
    val subjectPackage: String get() = actorIdentity
    val isSuccess: Boolean get() = (nativeResult ?: -1) >= 0
    val resultState: MonitorResultState
        get() = if (isSuccess) MonitorResultState.SUCCESS else MonitorResultState.FAILED

    val isDelegatedBySystemWriter: Boolean get() = identifyMethod == "binder_uid"
    val delegatedWriterType: MonitorWriterType
        get() = when {
            !isDelegatedBySystemWriter -> MonitorWriterType.NONE
            packageName == "com.android.providers.downloads" -> MonitorWriterType.DOWNLOAD
            packageName.contains("providers.media") -> MonitorWriterType.MEDIA
            else -> MonitorWriterType.SYSTEM
        }

    val actionType: MonitorActionType
        get() = when (opType) {
            "CREATE" -> MonitorActionType.CREATE
            "DELETE" -> MonitorActionType.DELETE
            "RENAME" -> MonitorActionType.RENAME
            "UPDATE" -> MonitorActionType.UPDATE
            "READ" -> MonitorActionType.READ
            "WRITE" -> MonitorActionType.WRITE
            "TRUNCATE" -> MonitorActionType.TRUNCATE
            "MMAP" -> MonitorActionType.MAP
            "SYNC" -> MonitorActionType.SYNC
            else -> MonitorActionType.OPEN
        }

    companion object {
        private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

        fun parse(line: String): FileMonitorEntry {
            val json = JsonParser.parseString(line).asJsonObject
            require(json.get("version").asInt == 2)
            fun text(key: String) = json.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            fun number(key: String) = json.get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
            val time = number("time_ms")
            val result = json.get("result").asLong
            return FileMonitorEntry(
                timestamp = TIME_FORMAT.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())),
                packageName = json.get("process").asString,
                caller = text("caller"),
                opType = json.get("operation").asString,
                path = json.get("path").asString,
                resultCode = result.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                nativeResult = result,
                errorNo = number("errno").toInt(),
                extra = text("extra").ifBlank { null },
                identifyMethod = text("method"),
                sequence = number("sequence"),
                timeMillis = time,
                bytes = if (text("operation") in listOf("READ", "WRITE")) result.coerceAtLeast(0) else 0,
                lostRecords = number("lost"),
                processId = number("pid").toInt(),
                uid = number("uid").toInt(),
            )
        }
    }
}

enum class MonitorActionType { OPEN, CREATE, DELETE, RENAME, UPDATE, READ, WRITE, TRUNCATE, MAP, SYNC }
enum class MonitorResultState { SUCCESS, FAILED }
enum class MonitorWriterType { NONE, MEDIA, DOWNLOAD, MTP, SYSTEM }
