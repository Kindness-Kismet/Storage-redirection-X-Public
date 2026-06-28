package com.storage.redirect.x.data.model

// 文件监视记录
// 原生侧日志格式（`|` 分隔，共 7~8 字段）：
// 时间戳|包名|调用方|操作类型|路径|ret=返回值|errno=错误码[|额外信息]
// 包名/调用方格式：单包名 或 pkg1,pkg2,pkg3（共享 UID 进程）
data class FileMonitorEntry(
    val timestamp: String,
    val packageName: String,
    val caller: String,
    val opType: String,
    val path: String,
    val resultCode: Int? = null,
    val errorNo: Int? = null,
    val extra: String? = null,
    // 共享 UID 场景下，指定当前条目对应的具体包名
    val resolvedPackage: String? = null
) {
    // 保留 /storage/emulated/<user_id>/ 前缀以显式标注用户
    val displayPath: String
        get() = path

    // packageName 是否为共享 UID 格式（逗号分隔的多包名）
    val isSharedUidPackage: Boolean
        get() = packageName.contains(',')

    // caller 是否为共享 UID 格式
    val isSharedUidCaller: Boolean
        get() = caller.contains(',')

    // 是否为共享 UID 格式（packageName 或 caller）
    val isSharedUid: Boolean
        get() = isSharedUidPackage || isSharedUidCaller

    // 解析共享 UID 中的所有包名（优先从 packageName 解析）
    val sharedUidPackages: List<String>
        get() = when {
            isSharedUidPackage -> packageName.split(",").filter { it.isNotBlank() }
            isSharedUidCaller -> caller.split(",").filter { it.isNotBlank() }
            else -> emptyList()
        }

    // 有效的包名（优先使用 resolvedPackage）
    private val effectivePackage: String
        get() = resolvedPackage ?: if (isSharedUidPackage) sharedUidPackages.firstOrNull() ?: packageName else packageName

    // 有效的调用方（用于判断代写场景）
    private val effectiveCaller: String
        get() = if (isSharedUidCaller) sharedUidPackages.firstOrNull() ?: caller else caller

    private val extraFields: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        parseExtraFields(extra)
    }

    private val identifyMethod: String
        get() = parseExtraField("identify_method").orEmpty()

    private val identifyReliability: String
        get() = parseExtraField("identify_reliability").orEmpty()

    private val subjectResolution: SubjectResolution by lazy(LazyThreadSafetyMode.NONE) {
        resolveSubjectResolution()
    }

    val isDelegatedBySystemWriter: Boolean
        get() = subjectResolution.isDelegated

    val subjectPackage: String
        get() = subjectResolution.packageName

    val delegatedWriterType: MonitorWriterType
        get() {
            if (!isDelegatedBySystemWriter) {
                return MonitorWriterType.NONE
            }

            // 共享 UID 未展开时无法确定具体执行包，按识别方法推断
            if (resolvedPackage == null && isSharedUidPackage) {
                return when (identifyMethod) {
                    "downloads_db" -> MonitorWriterType.DOWNLOAD
                    else -> MonitorWriterType.SYSTEM
                }
            }

            val writerPackage = effectivePackage
            return when (writerPackage.lowercase()) {
                "com.google.android.providers.media.module",
                "com.android.providers.media.module",
                "com.android.providers.media" -> MonitorWriterType.MEDIA

                "com.android.providers.downloads" -> MonitorWriterType.DOWNLOAD
                "com.android.mtp" -> MonitorWriterType.MTP
                else -> MonitorWriterType.SYSTEM
            }
        }

    val isSuccess: Boolean
        get() = (resultCode ?: -1) >= 0

    val resultState: MonitorResultState
        get() = if (isSuccess) MonitorResultState.SUCCESS else MonitorResultState.FAILED

    private val opName: String
        get() = parseExtraField("op")?.lowercase().orEmpty()

    private val openFlags: Long?
        get() {
            val raw = parseExtraField("flags") ?: return null
            val normalized = raw.removePrefix("0x")
            return normalized.toLongOrNull(16)
        }

    val actionType: MonitorActionType
        get() {
            if (opName.startsWith("rename") || opType.equals("RENAME", ignoreCase = true)) {
                return MonitorActionType.RENAME
            }
            if (opName.startsWith("unlink") || opType.equals("DELETE", ignoreCase = true)) {
                return MonitorActionType.DELETE
            }
            if (opName.startsWith("mkdir")) {
                return MonitorActionType.CREATE
            }

            val flags = openFlags ?: 0L
            val hasCreateFlag = hasCreateIntent(flags)
            if (hasCreateFlag || opType.equals("CREATE", ignoreCase = true)) {
                return MonitorActionType.CREATE
            }

            if (opType.equals("WRITE", ignoreCase = true)) {
                return MonitorActionType.OPEN
            }
            return MonitorActionType.OPEN
        }

    private fun hasCreateIntent(flags: Long): Boolean {
        return (flags and O_CREAT_FLAG) != 0L || (flags and O_TMPFILE_MASK) == O_TMPFILE_MASK
    }

    private fun resolveSubjectResolution(): SubjectResolution {
        val actualPackage = effectivePackage
        val actualCaller = effectiveCaller

        if (isReliableCaller(actualCaller, actualPackage)) {
            return SubjectResolution(packageName = actualCaller, isDelegated = true)
        }

        if (isSystemWriterPackage(actualPackage)) {
            val inferred = inferPackageFromPath(path)
            if (!inferred.isNullOrBlank()) {
                return SubjectResolution(packageName = inferred, isDelegated = true)
            }
        }

        return SubjectResolution(packageName = actualPackage, isDelegated = false)
    }

    private fun isReliableCaller(actualCaller: String, actualPackage: String): Boolean {
        if (actualCaller.isBlank() || actualCaller == "-" || actualCaller.contains(',')) {
            return false
        }
        if (actualCaller.equals(actualPackage, ignoreCase = true)) {
            return false
        }
        return identifyMethod != "unknown" || identifyReliability != "none"
    }

    private fun parseExtraField(key: String): String? {
        return extraFields[key]
    }

    private fun parseExtraFields(rawExtra: String?): Map<String, String> {
        if (rawExtra.isNullOrBlank()) {
            return emptyMap()
        }

        val fields = linkedMapOf<String, String>()
        val segments = rawExtra.split("|")
        for (segment in segments) {
            val separatorIndex = segment.indexOf('=')
            if (separatorIndex <= 0 || separatorIndex >= segment.length - 1) {
                continue
            }
            val key = segment.substring(0, separatorIndex).trim()
            val value = segment.substring(separatorIndex + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                fields[key] = value
            }
        }
        return fields
    }

    private fun inferPackageFromPath(rawPath: String): String? {
        val androidMatch = ANDROID_PACKAGE_PATH_REGEX.find(rawPath)?.groupValues?.getOrNull(1)
        if (!androidMatch.isNullOrBlank() && !isSystemWriterPackage(androidMatch)) {
            return androidMatch
        }

        val gsMatch = GS_PACKAGE_PATH_REGEX.find(rawPath)?.groupValues?.getOrNull(1)
        if (!gsMatch.isNullOrBlank() && !isSystemWriterPackage(gsMatch)) {
            return gsMatch
        }

        val segments = rawPath.split("/")
        for (segment in segments) {
            if (!GENERIC_PACKAGE_SEGMENT_REGEX.matches(segment)) {
                continue
            }
            if (!isSystemWriterPackage(segment)) {
                return segment
            }
        }
        return null
    }

    private fun isSystemWriterPackage(packageName: String): Boolean {
        return when (packageName.lowercase()) {
            "com.google.android.providers.media.module",
            "com.android.providers.media.module",
            "com.android.providers.media",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.mtp" -> true

            else -> false
        }
    }

    companion object {
        private const val MIN_FIELD_COUNT = 7
        private const val O_CREAT_FLAG = 0x40L
        private const val O_TMPFILE_MASK = 0x410000L
        private val ANDROID_PACKAGE_PATH_REGEX =
            Regex("/Android/(?:data|media)/([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)(?:/|$)")
        private val GS_PACKAGE_PATH_REGEX =
            Regex("/\\.gs/([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)(?:/|$)")
        private val GENERIC_PACKAGE_SEGMENT_REGEX =
            Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){2,}$")

        // 从日志单行解析监视记录
        fun parse(line: String): FileMonitorEntry {
            val parts = line.split("|")
            if (parts.size < MIN_FIELD_COUNT) {
                return FileMonitorEntry(
                    timestamp = parts.getOrElse(0) { "" },
                    packageName = parts.getOrElse(1) { "" },
                    caller = parts.getOrElse(2) { "" },
                    opType = parts.getOrElse(3) { "" },
                    path = parts.getOrElse(4) { "" },
                )
            }

            val resultCode = parts[5].removePrefix("ret=").toIntOrNull()
            val errorNo = parts[6].removePrefix("errno=").toIntOrNull()
            return FileMonitorEntry(
                timestamp = parts[0],
                packageName = parts[1],
                caller = parts[2],
                opType = parts[3],
                path = parts[4],
                resultCode = resultCode,
                errorNo = errorNo,
                extra = if (parts.size > MIN_FIELD_COUNT) {
                    parts.subList(MIN_FIELD_COUNT, parts.size).joinToString("|")
                } else {
                    null
                },
            )
        }
    }

    private data class SubjectResolution(
        val packageName: String,
        val isDelegated: Boolean,
    )
}

enum class MonitorActionType {
    OPEN,
    CREATE,
    DELETE,
    RENAME,
}

enum class MonitorResultState {
    SUCCESS,
    FAILED,
}

enum class MonitorWriterType {
    NONE,
    MEDIA,
    DOWNLOAD,
    MTP,
    SYSTEM,
}
