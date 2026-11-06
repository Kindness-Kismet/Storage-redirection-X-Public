package com.storage.redirect.x.ui.page.cloudrules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.storage.redirect.x.data.repository.CloudRuleRepository
import com.storage.redirect.x.data.repository.CloudRuleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class CloudRuleState(
    private val repository: CloudRuleRepository,
    private val scope: CoroutineScope,
) {
    var isVisible by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var result by mutableStateOf<CloudRuleResult?>(null)
        private set

    fun open() {
        isVisible = true
        if (isLoading) return
        isLoading = true
        scope.launch {
            try {
                val cached = repository.loadCached()
                result = cached
                if (cached.snapshot == null) result = repository.refresh()
            } finally {
                isLoading = false
            }
        }
    }

    fun refresh() {
        if (isLoading) return
        isLoading = true
        scope.launch {
            try {
                result = repository.refresh()
            } finally {
                isLoading = false
            }
        }
    }

    fun close() { isVisible = false }
}

@Composable
fun rememberCloudRuleState(): CloudRuleState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        CloudRuleState(CloudRuleRepository(context.filesDir), scope)
    }
}
