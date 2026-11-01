package com.storage.redirect.x.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storage.redirect.x.data.model.FileMonitorEntry
import com.storage.redirect.x.data.model.MonitorAggregation
import com.storage.redirect.x.data.repository.FileMonitorRepository
import com.storage.redirect.x.data.repository.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileMonitorUiState(
    val isLoading: Boolean = true,
    val entries: List<FileMonitorEntry> = emptyList(),
    val searchQuery: String = "",
    val isMonitoringEnabled: Boolean = true,
    val opTypeIndex: Int = 0,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val lostRecords: Long = 0,
    val writeErrors: Long = 0,
    val hasLoadError: Boolean = false,
    val isDaemonStale: Boolean = false,
)

class FileMonitorViewModel(
    private val monitorRepo: FileMonitorRepository = FileMonitorRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(FileMonitorUiState())
    val uiState: StateFlow<FileMonitorUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var before = 0L

    init {
        refresh()
        viewModelScope.launch {
            ConfigRepository.monitorEnabled.filterNotNull().collect { isEnabled ->
                _uiState.update { it.copy(isMonitoringEnabled = isEnabled) }
            }
        }
    }

    fun refresh(isSilent: Boolean = false) {
        if (isSilent && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!isSilent) {
                _uiState.update { it.copy(isLoading = true, isLoadingMore = false) }
            }
            val page = monitorRepo.loadPage()
            if (page == null) {
                if (!isSilent) {
                    _uiState.update { it.copy(isLoading = false, hasLoadError = true) }
                }
                return@launch
            }
            ConfigRepository.publishMonitorEnabled(page.isMonitoringEnabled)
            val grouped =
                withContext(Dispatchers.Default) {
                    MonitorAggregation.group(page.entries)
                }
            before = page.entries.lastOrNull()?.sequence ?: 0L
            _uiState.update {
                it.copy(
                    entries = grouped,
                    isLoading = false,
                    hasMore = page.hasMore,
                    lostRecords = page.lost,
                    writeErrors = page.writeErrors,
                    hasLoadError = false,
                    isDaemonStale = page.daemonUpdatedMs == 0L ||
                        System.currentTimeMillis() - page.daemonUpdatedMs > 15000,
                )
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            monitorRepo.clear()
            before = 0L
            refresh(isSilent = false)
        }
    }

    fun loadMore() {
        if (!_uiState.value.hasMore || _uiState.value.isLoading || _uiState.value.isLoadingMore)
            return
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val page = monitorRepo.loadPage(before = before)
            if (page == null) {
                _uiState.update { it.copy(isLoadingMore = false, hasLoadError = true) }
                return@launch
            }
            val combined = _uiState.value.entries + page.entries
            val grouped = withContext(Dispatchers.Default) { MonitorAggregation.group(combined) }
            before = page.entries.lastOrNull()?.sequence ?: before
            _uiState.update {
                it.copy(entries = grouped, hasMore = page.hasMore, isLoadingMore = false, hasLoadError = false)
            }
        }
    }

    fun updateSearchQuery(value: String) {
        _uiState.update { it.copy(searchQuery = value) }
    }

    fun updateOpTypeIndex(index: Int) {
        _uiState.update { it.copy(opTypeIndex = index) }
    }

    fun resetFilters() {
        _uiState.update { it.copy(searchQuery = "", opTypeIndex = 0) }
    }
}
