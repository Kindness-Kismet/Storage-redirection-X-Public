package com.storage.redirect.x.ui.page

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storage.redirect.x.data.repository.BackupExportResult
import com.storage.redirect.x.data.repository.BackupRestoreRepository
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.RestoreResult
import com.storage.redirect.x.data.service.RootService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLogsPageVisible: Boolean = false,
    val isLicensesPageVisible: Boolean = false,
    val isUpdatePageVisible: Boolean = false,
    val hasRoot: Boolean? = null,
    val isWorking: Boolean = false,
    val isFuseFixerEnabled: Boolean = false,
)

sealed class SettingsUiEvent {
    data class BackupFinished(val result: BackupExportResult) : SettingsUiEvent()
    data class RestoreFinished(val result: RestoreResult) : SettingsUiEvent()
}

// 设置页状态管理，统一处理 Root 状态和备份还原任务。
class SettingsViewModel(
    private val backupRestoreRepo: BackupRestoreRepository = BackupRestoreRepository(),
    private val configRepo: ConfigRepository = ConfigRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsUiEvent>()
    val events: SharedFlow<SettingsUiEvent> = _events.asSharedFlow()

    init {
        refreshRootStatus()
    }

    // 刷新当前 Root 可用性。
    fun refreshRootStatus() {
        viewModelScope.launch {
            val hasRoot = RootService.isRootAvailable()
            _uiState.update { it.copy(hasRoot = hasRoot) }
            if (hasRoot) {
                refreshFuseFixerEnabled()
            }
        }
    }

    fun refreshFuseFixerEnabled() {
        viewModelScope.launch {
            val isEnabled = configRepo.readFuseFixerEnabled()
            _uiState.update { it.copy(isFuseFixerEnabled = isEnabled) }
        }
    }

    fun setLogsPageVisible(isVisible: Boolean) {
        _uiState.update { it.copy(isLogsPageVisible = isVisible) }
    }

    fun setLicensesPageVisible(isVisible: Boolean) {
        _uiState.update { it.copy(isLicensesPageVisible = isVisible) }
    }

    fun setUpdatePageVisible(isVisible: Boolean) {
        _uiState.update { it.copy(isUpdatePageVisible = isVisible) }
    }

    fun exportPackageConfigsZip(contentResolver: ContentResolver, outputUri: Uri) {
        if (_uiState.value.hasRoot != true || _uiState.value.isWorking) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val result = backupRestoreRepo.exportPackageConfigsZip(contentResolver, outputUri)
            _uiState.update { it.copy(isWorking = false) }
            _events.emit(SettingsUiEvent.BackupFinished(result))
        }
    }

    fun restorePackageConfigs() {
        if (_uiState.value.hasRoot != true || _uiState.value.isWorking) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val result = backupRestoreRepo.restorePackageConfigs()
            _uiState.update { it.copy(isWorking = false) }
            _events.emit(SettingsUiEvent.RestoreFinished(result))
        }
    }

    fun restorePackageConfigsFromUri(contentResolver: ContentResolver, inputUri: Uri) {
        if (_uiState.value.hasRoot != true || _uiState.value.isWorking) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val result = backupRestoreRepo.restorePackageConfigsFromUri(contentResolver, inputUri)
            _uiState.update { it.copy(isWorking = false) }
            _events.emit(SettingsUiEvent.RestoreFinished(result))
        }
    }

    fun setFuseFixerEnabled(isEnabled: Boolean) {
        if (_uiState.value.hasRoot != true || _uiState.value.isWorking) {
            return
        }

        viewModelScope.launch {
            val previous = _uiState.value.isFuseFixerEnabled
            _uiState.update { it.copy(isFuseFixerEnabled = isEnabled, isWorking = true) }
            val redirectApps = configRepo.load(0).redirectApps
            val isSaved = configRepo.setFuseFixerEnabled(isEnabled)
            if (isSaved) {
                RootService.restartMediaProvider(redirectApps)
            }
            _uiState.update {
                it.copy(
                    isFuseFixerEnabled = if (isSaved) isEnabled else previous,
                    isWorking = false,
                )
            }
        }
    }
}
