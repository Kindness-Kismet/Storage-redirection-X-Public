package com.storage.redirect.x.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storage.redirect.x.data.model.FileMonitorEntry
import com.storage.redirect.x.data.repository.FileMonitorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_MONITOR_LINE_COUNT = 500

data class FileMonitorUiState(
    val isLoading: Boolean = true,
    val entries: List<FileMonitorEntry> = emptyList(),
    val searchQuery: String = "",
    val opTypeIndex: Int = 0,
    val isOpTypeMenuVisible: Boolean = false,
)

// 文件监视页状态管理，避免在 Composable 中堆叠业务逻辑。
class FileMonitorViewModel(
    private val monitorRepo: FileMonitorRepository = FileMonitorRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileMonitorUiState())
    val uiState: StateFlow<FileMonitorUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    // 刷新最新监视记录。
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = monitorRepo.loadEntries(DEFAULT_MONITOR_LINE_COUNT) ?: emptyList()
            _uiState.update { it.copy(entries = entries, isLoading = false) }
        }
    }

    fun updateSearchQuery(value: String) {
        _uiState.update { it.copy(searchQuery = value) }
    }

    fun updateOpTypeIndex(index: Int) {
        _uiState.update {
            it.copy(opTypeIndex = index, isOpTypeMenuVisible = false)
        }
    }

    fun setOpTypeMenuVisible(isVisible: Boolean) {
        _uiState.update { it.copy(isOpTypeMenuVisible = isVisible) }
    }

    // 重置搜索状态（页面离开时调用）
    fun resetFilters() {
        _uiState.update { it.copy(searchQuery = "", opTypeIndex = 0) }
    }
}
