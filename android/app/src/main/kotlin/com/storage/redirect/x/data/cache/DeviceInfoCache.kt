package com.storage.redirect.x.data.cache

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.storage.redirect.x.data.service.RootService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// 设备信息缓存，优先从 SharedPreferences 恢复避免 UI 闪烁
// 后台刷新后持久化新值
object DeviceInfoCache {
    private val mutex = Mutex()
    private var loaded = false

    private const val KEY_KERNEL = "device_kernel"
    private const val KEY_SELINUX = "device_selinux"
    private const val KEY_MODEL = "device_model"
    private const val KEY_ANDROID = "device_android"
    private const val KEY_ARCH = "device_arch"

    var kernelVersion by mutableStateOf("")
        private set
    var selinuxStatus by mutableStateOf("--")
        private set
    var deviceModel by mutableStateOf("")
        private set
    var androidVersion by mutableStateOf("")
        private set
    var systemArch by mutableStateOf("")
        private set

    // 从 SharedPreferences 同步恢复上次缓存的值
    fun restoreFromPrefs() {
        kernelVersion = AppPreferences.getString(KEY_KERNEL)
        selinuxStatus = AppPreferences.getString(KEY_SELINUX, "--")
        deviceModel = AppPreferences.getString(KEY_MODEL)
        androidVersion = AppPreferences.getString(KEY_ANDROID)
        systemArch = AppPreferences.getString(KEY_ARCH)
    }

    // 后台刷新实际值并持久化
    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return

            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            systemArch = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"

            kernelVersion = try {
                System.getProperty("os.version") ?: ""
            } catch (_: Exception) { "" }

            val seResult = RootService.runCommand("getenforce 2>/dev/null")
            selinuxStatus = if (seResult.isSuccess && seResult.stdout.trim().isNotEmpty()) {
                seResult.stdout.trim()
            } else {
                "Unknown"
            }

            // 持久化到 SharedPreferences
            AppPreferences.put(KEY_KERNEL, kernelVersion)
            AppPreferences.put(KEY_SELINUX, selinuxStatus)
            AppPreferences.put(KEY_MODEL, deviceModel)
            AppPreferences.put(KEY_ANDROID, androidVersion)
            AppPreferences.put(KEY_ARCH, systemArch)

            loaded = true
        }
    }
}
