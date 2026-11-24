package com.sjos.folionote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjos.folionote.AppContainer
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.engine.document.StorageStats
import com.sjos.folionote.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val storageStats: StorageStats = StorageStats(0, 0, 0),
    val providerCount: Int = 0,
    val modelCount: Int = 0,
    val notebookCount: Int = 0,
    val statusMessage: String? = null
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val prefsStore = container.preferencesStore
    private val notebookRepo = container.notebookRepository
    private val providerRepo = container.providerRepository
    private val statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        prefsStore.settingsFlow,
        notebookRepo.totalStorageBytes,
        providerRepo.allProviders,
        providerRepo.allModels,
        notebookRepo.allNotebooks,
        statusMessage
    ) { arr ->
        val settings = arr[0] as AppSettings
        val usedBytes = arr[1] as Long
        val providers = arr[2] as List<*>
        val models = arr[3] as List<*>
        val notebooks = arr[4] as List<*>
        val msg = arr[5] as String?
        SettingsUiState(
            settings = settings,
            storageStats = notebookRepo.getStorageStats(usedBytes),
            providerCount = providers.size,
            modelCount = models.size,
            notebookCount = notebooks.size,
            statusMessage = msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { prefsStore.setThemePreference(theme) }
    }

    fun updateChunkingConfig(chunkSize: Int, overlap: Int, topK: Int) {
        viewModelScope.launch {
            prefsStore.updateChunkingConfig(chunkSize, overlap, topK)
            statusMessage.value = "Parâmetros de segmentação e RAG salvos."
        }
    }

    fun clearAllApiCredentials() {
        viewModelScope.launch {
            providerRepo.clearAllKeys()
            statusMessage.value = "Todas as chaves de API foram removidas do dispositivo."
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
