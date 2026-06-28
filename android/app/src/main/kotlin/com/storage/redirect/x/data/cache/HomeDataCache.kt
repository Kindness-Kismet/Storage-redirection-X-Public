package com.storage.redirect.x.data.cache

import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.ModuleRepository
import com.storage.redirect.x.BuildConfig
import com.storage.redirect.x.ui.component.ModuleStatus

// 主页数据缓存，优先从 SharedPreferences 恢复避免 UI 闪烁
// 后台刷新后持久化新值
object HomeDataCache {
    private var loaded = false

    private const val KEY_MODULE_STATUS = "home_module_status"
    private const val KEY_MODULE_VERSION = "home_module_version"
    private const val KEY_REDIRECT_APPS = "home_redirect_apps"
    private const val KEY_REDIRECT_COUNT = "home_redirect_count"

    var moduleStatus by mutableStateOf(ModuleStatus.LOADING)
        private set
    var moduleVersion by mutableStateOf(BuildConfig.VERSION_NAME)
        private set
    var redirectedAppsCount by mutableStateOf("--")
        private set
    var redirectCount by mutableIntStateOf(0)
        private set

    // 从 SharedPreferences 同步恢复上次缓存的值
    fun restoreFromPrefs() {
        val statusOrdinal = AppPreferences.getInt(KEY_MODULE_STATUS, -1)
        if (statusOrdinal >= 0 && statusOrdinal < ModuleStatus.entries.size) {
            moduleStatus = ModuleStatus.entries[statusOrdinal]
        }
        val cachedApps = AppPreferences.getString(KEY_REDIRECT_APPS)
        if (cachedApps.isNotEmpty()) {
            redirectedAppsCount = cachedApps
        }
        redirectCount = AppPreferences.getInt(KEY_REDIRECT_COUNT)
        val cachedVersion = AppPreferences.getString(KEY_MODULE_VERSION)
        if (cachedVersion.isNotEmpty()) {
            moduleVersion = cachedVersion
        }
    }

    // 后台刷新实际值并持久化
    suspend fun ensureLoaded(
        moduleRepo: ModuleRepository,
        configRepo: ConfigRepository,
        packageManager: PackageManager,
        selfPackage: String,
    ) {
        if (loaded) return

        val isInstalled = moduleRepo.isModuleInstalled()
        moduleStatus = if (isInstalled) ModuleStatus.INSTALLED else ModuleStatus.NOT_INSTALLED

        if (isInstalled) {
            moduleRepo.getInstalledVersion()?.let { moduleVersion = it }
        }

        val config: RedirectConfig = configRepo.load(0)
        val installedPackages = withContext(Dispatchers.IO) {
            packageManager.getInstalledApplications(0)
                .asSequence()
                .map { it.packageName }
                .filter { it != selfPackage }
                .toHashSet()
        }
        redirectedAppsCount = config.redirectApps.count { it in installedPackages }.toString()
        redirectCount = moduleRepo.loadRedirectCount()

        // 持久化
        AppPreferences.put(KEY_MODULE_STATUS, moduleStatus.ordinal)
        AppPreferences.put(KEY_MODULE_VERSION, moduleVersion)
        AppPreferences.put(KEY_REDIRECT_APPS, redirectedAppsCount)
        AppPreferences.put(KEY_REDIRECT_COUNT, redirectCount)

        loaded = true
    }
}
