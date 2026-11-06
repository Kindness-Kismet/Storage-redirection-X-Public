package com.storage.redirect.x.ui.page.cloudrules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.storage.redirect.x.data.model.CloudRule
import com.storage.redirect.x.data.cache.CloudRulePreferences
import com.storage.redirect.x.data.repository.CloudRuleRepository
import com.storage.redirect.x.data.repository.CloudRuleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class CloudRuleState(
    private val repository: CloudRuleRepository,
    private val scope: CoroutineScope,
) {
    var isLoading by mutableStateOf(false)
        private set
    var result by mutableStateOf<CloudRuleResult?>(null)
        private set
    var isEnabling by mutableStateOf(false)
        private set
    private var loadTask: Deferred<CloudRuleResult>? = null

    fun load() {
        if (!CloudRulePreferences.isEnabled) return
        if (loadTask == null) loadTask = request(shouldReadCache = true)
    }

    fun refresh() {
        if (!CloudRulePreferences.isEnabled) return
        if (!isLoading) loadTask = request(shouldReadCache = false)
    }

    fun enable(packageName: String, onEnable: (CloudRule?) -> Unit) {
        if (isEnabling) return
        if (!CloudRulePreferences.isEnabled) {
            onEnable(null)
            return
        }
        isEnabling = true
        scope.launch {
            try {
                if (!CloudRulePreferences.isEnabled) {
                    onEnable(null)
                    return@launch
                }
                val rule = awaitForEnable().snapshot?.catalog?.rulesByPackage?.get(packageName)
                onEnable(rule.takeIf { CloudRulePreferences.isEnabled })
            } finally {
                isEnabling = false
            }
        }
    }

    // 开启时优先使用已展示的有效缓存，首次下载则等待同一个任务。
    private suspend fun awaitForEnable(): CloudRuleResult {
        result?.takeIf { it.snapshot != null }?.let { return it }
        load()
        return requireNotNull(loadTask).await()
    }

    private fun request(shouldReadCache: Boolean): Deferred<CloudRuleResult> {
        isLoading = true
        return scope.async {
            try {
                if (shouldReadCache) result = repository.loadCached()
                val snapshot = result?.snapshot
                val age = snapshot?.let { System.currentTimeMillis() - it.fetchedAtMillis }
                if (!shouldReadCache || age == null || age !in 0 until CACHE_MAX_AGE_MS) {
                    result = repository.refresh()
                }
                requireNotNull(result)
            } finally {
                isLoading = false
            }
        }
    }

    companion object {
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

@Composable
fun rememberCloudRuleState(): CloudRuleState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        CloudRuleState(CloudRuleRepository(context.filesDir), scope)
    }
}
