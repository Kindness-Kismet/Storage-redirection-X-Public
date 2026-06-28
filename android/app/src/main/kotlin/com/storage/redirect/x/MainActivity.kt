package com.storage.redirect.x

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.DeviceInfoCache
import com.storage.redirect.x.data.cache.HomeDataCache
import com.storage.redirect.x.data.cache.PreferenceKeys
import com.storage.redirect.x.data.repository.UpdateRepository
import com.storage.redirect.x.util.BackupRestoreNotifier
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 在 Context 初始化前应用已保存的语言设置
        val prefs = newBase.getSharedPreferences("app_cache", Context.MODE_PRIVATE)
        val langIndex = prefs.getInt(PreferenceKeys.LANGUAGE, 0)
        val tags = arrayOf("", "en", "zh")
        val tag = tags.getOrElse(langIndex) { "" }

        if (tag.isNotEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 初始化持久化并同步恢复缓存，确保首帧即有数据
        AppPreferences.init(this)
        DeviceInfoCache.restoreFromPrefs()
        HomeDataCache.restoreFromPrefs()
        BackupRestoreNotifier.ensureChannel(this)

        setContent {
            App()
        }

        // 根据偏好设置决定是否在启动时检查更新
        if (AppPreferences.getBoolean(PreferenceKeys.CHECK_UPDATE_ON_LAUNCH, true)) {
            val activity = this
            lifecycleScope.launch {
                try {
                    val result = UpdateRepository().checkForUpdate()
                    val ignored = AppPreferences.getString(PreferenceKeys.IGNORED_UPDATE_VERSION, "")
                    if (result.hasUpdate && result.latestVersion != ignored) {
                        Toast.makeText(
                            activity,
                            getString(R.string.settings_update_toast, result.latestVersion),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (_: Exception) {
                    // 静默失败，不打扰用户
                }
            }
        }
    }
}
