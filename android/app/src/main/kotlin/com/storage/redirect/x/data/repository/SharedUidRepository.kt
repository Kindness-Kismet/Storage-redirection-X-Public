package com.storage.redirect.x.data.repository

import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Paths

// 共享 UID 仓库：读取系统代写进程的 UID 映射
class SharedUidRepository {

    // 缓存：UID -> 包名列表
    private var uidToPackages: Map<Int, List<String>> = emptyMap()

    // 缓存：包名 -> UID
    private var packageToUid: Map<String, Int> = emptyMap()

    // 加载共享 UID 映射
    suspend fun load(): Boolean {
        if (!RootService.isRootAvailable()) return false

        val result = RootService.runCommand("cat ${Paths.SYSTEM_WRITER_UIDS_FILE} 2>/dev/null")
        if (!result.isSuccess) return false

        val uidMap = mutableMapOf<Int, MutableList<String>>()
        val pkgMap = mutableMapOf<String, Int>()

        result.stdout.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

            val parts = trimmed.split(":")
            if (parts.size != 2) return@forEach

            val packageName = parts[0].trim()
            val uid = parts[1].trim().toIntOrNull() ?: return@forEach

            pkgMap[packageName] = uid
            uidMap.getOrPut(uid) { mutableListOf() }.add(packageName)
        }

        // 过滤：只保留有 >= 2 个包的 UID（真正的共享 UID）
        uidToPackages = uidMap.filter { it.value.size >= 2 }
            .mapValues { it.value.sorted() }
        packageToUid = pkgMap

        return true
    }

    // 获取与指定包名共享 UID 的所有包名（不含自身）
    fun getSharedPackages(packageName: String): List<String> {
        val uid = packageToUid[packageName] ?: return emptyList()
        val packages = uidToPackages[uid] ?: return emptyList()
        return packages.filter { it != packageName }
    }

    // 获取与指定包名共享 UID 的所有包名（含自身）
    fun getAllSharedPackages(packageName: String): List<String> {
        val uid = packageToUid[packageName] ?: return emptyList()
        return uidToPackages[uid] ?: emptyList()
    }

    // 判断包名是否属于共享 UID 组
    fun isSharedUidPackage(packageName: String): Boolean {
        val uid = packageToUid[packageName] ?: return false
        return uidToPackages.containsKey(uid)
    }
}
