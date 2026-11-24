package com.sjos.folionote.ui.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjos.folionote.AppContainer
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.preferences.DriveConflictPolicy
import com.sjos.folionote.data.local.SyncConflictEntity
import com.sjos.folionote.engine.drive.ConflictResolutionChoice
import com.sjos.folionote.engine.drive.DriveFileItem
import com.sjos.folionote.engine.drive.DriveSyncRuntimeState
import com.sjos.folionote.engine.drive.DriveSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DriveUiState(
    val settings: AppSettings = AppSettings(),
    val runtime: DriveSyncRuntimeState = DriveSyncRuntimeState(),
    val pendingConflicts: List<SyncConflictEntity> = emptyList(),
    val statusMessage: String? = null
)

class DriveViewModel(private val container: AppContainer) : ViewModel() {

    private val prefsStore = container.preferencesStore
    private val driveSync = container.driveSyncEngine
    private val authManager = container.googleDriveAuthManager
    private val syncDao = container.database.syncDao()
    private val statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DriveUiState> = combine(
        prefsStore.settingsFlow,
        driveSync.runtimeState,
        syncDao.observePendingConflicts(),
        statusMessage
    ) { settings, runtime, conflicts, msg ->
        DriveUiState(
            settings = settings,
            runtime = runtime,
            pendingConflicts = conflicts,
            statusMessage = msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DriveUiState())

    init {
        viewModelScope.launch {
            if (uiState.value.settings.isGoogleDriveConnected) {
                driveSync.refreshBackupList()
            }
        }
    }

    fun refreshBackups() {
        viewModelScope.launch {
            driveSync.refreshBackupList().onFailure { err ->
                statusMessage.value = err.message ?: "Erro ao listar backups."
            }
        }
    }

    fun createBackup(customName: String? = null) {
        viewModelScope.launch {
            driveSync.createAndUploadBackup(customName).onSuccess { item ->
                statusMessage.value = "Backup enviado: ${item.name}"
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Falha ao criar backup."
            }
        }
    }

    fun renameBackup(fileId: String, newName: String) {
        viewModelScope.launch {
            driveSync.renameBackup(fileId, newName).onSuccess { item ->
                statusMessage.value = "Backup renomeado para ${item.name}"
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Erro ao renomear backup."
            }
        }
    }

    fun restoreBackup(item: DriveFileItem) {
        viewModelScope.launch {
            driveSync.restoreBackup(item).onSuccess { summary ->
                statusMessage.value = "Restaurado: ${summary.notebooksRestored} notebook(s), ${summary.sourcesRestored} fonte(s)."
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Falha ao restaurar backup."
            }
        }
    }

    fun deleteBackup(fileId: String) {
        viewModelScope.launch {
            driveSync.deleteBackup(fileId).onSuccess {
                statusMessage.value = "Backup excluído do Google Drive."
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Erro ao excluir backup."
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            driveSync.syncNow().onSuccess { res ->
                statusMessage.value = "Sincronização concluída: ↑${res.uploadedCount} ↓${res.downloadedCount}" +
                    if (res.conflictsDetected > 0) " • ${res.conflictsDetected} conflito(s)" else ""
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Erro na sincronização."
            }
        }
    }

    fun resolveConflict(conflictId: String, choice: ConflictResolutionChoice) {
        viewModelScope.launch {
            driveSync.resolveConflict(conflictId, choice).onSuccess {
                statusMessage.value = "Conflito resolvido. Execute Sincronizar agora para propagar."
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Erro ao resolver conflito."
            }
        }
    }

    fun cancelActiveOperation() {
        driveSync.cancelActiveOperation()
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.setDriveSyncEnabled(enabled)
            DriveSyncWorker.schedulePeriodicSync(container.appContext, enabled)
        }
    }

    fun setConflictPolicy(policy: DriveConflictPolicy) {
        viewModelScope.launch {
            prefsStore.setDriveConflictPolicy(policy)
        }
    }

    fun refreshProfile() {
        viewModelScope.launch {
            authManager.refreshProfile().onSuccess { p ->
                statusMessage.value = "Perfil atualizado: ${p.displayName ?: p.email}"
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Erro ao atualizar perfil."
            }
        }
    }

    fun disconnectAccount() {
        viewModelScope.launch {
            DriveSyncWorker.schedulePeriodicSync(container.appContext, false)
            authManager.disconnectAndRevoke()
            statusMessage.value = "Conta Google desconectada."
        }
    }

    fun clearError() {
        driveSync.clearError()
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DriveViewModel(container) as T
    }
}
