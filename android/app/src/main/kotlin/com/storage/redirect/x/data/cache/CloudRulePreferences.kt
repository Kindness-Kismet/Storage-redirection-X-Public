package com.storage.redirect.x.data.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// 单一全局开关，应用配置页与设置页共享状态。
object CloudRulePreferences {
    private val state = MutableStateFlow(AppPreferences.getBoolean(PreferenceKeys.CLOUD_RULES_ENABLED))
    val enabled = state.asStateFlow()
    val isEnabled: Boolean get() = state.value

    fun setEnabled(isEnabled: Boolean) {
        AppPreferences.put(PreferenceKeys.CLOUD_RULES_ENABLED, isEnabled)
        state.value = isEnabled
    }
}
