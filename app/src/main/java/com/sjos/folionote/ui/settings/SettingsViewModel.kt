package com.sjos.folionote.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjos.folionote.AppContainer
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.preferences.DriveConflictPolicy
import com.sjos.folionote.core.preferences.OcrProviderType
import com.sjos.folionote.core.preferences.TranscriptionProviderType
import com.sjos.folionote.data.local.SyncConflictEntity
import com.sjos.folionote.engine.document.StorageStats
import com.sjos.folionote.engine.drive.DriveSyncRuntimeState
import com.sjos.folionote.engine.drive.DriveSyncWorker
import com.sjos.folionote.engine.drive.GoogleConnectOutcome
import com.sjos.folionote.engine.updater.AvailableReleaseUpdate
import com.sjos.folionote.engine.updater.UpdateUiState
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
    val pendingConflictCount: Int = 0,
    val driveRuntime: DriveSyncRuntimeState = DriveSyncRuntimeState(),
    val isTestingGemini: Boolean = false,
    val geminiValidationResult: Pair<Boolean, String>? = null,
    val isConnectingGoogle: Boolean = false,
    val updateState: UpdateUiState = UpdateUiState.Idle,
    val showUpdateDialog: Boolean = false,
    val installedVersionLabel: String = "",
    val statusMessage: String? = null
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val prefsStore = container.preferencesStore
    private val notebookRepo = container.notebookRepository
    private val providerRepo = container.providerRepository
    private val cryptoManager = container.cryptoManager
    private val geminiManager = container.geminiEmbeddingManager
    private val driveAuth = container.googleDriveAuthManager
    private val driveSync = container.driveSyncEngine
    private val updateManager = container.appUpdateManager
    private val syncDao = container.database.syncDao()

    private val isTestingGemini = MutableStateFlow(false)
    private val geminiValidationResult = MutableStateFlow<Pair<Boolean, String>?>(null)
    private val isConnectingGoogle = MutableStateFlow(false)
    private val showUpdateDialog = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val installedInfo by lazy { updateManager.getInstalledVersionInfo() }

    private data class CoreMetaBundle(
        val settings: AppSettings,
        val usedBytes: Long,
        val providerCount: Int,
        val modelCount: Int,
        val notebookCount: Int
    )

    private val coreMetaFlow = combine(
        prefsStore.settingsFlow,
        notebookRepo.totalStorageBytes,
        providerRepo.allProviders,
        providerRepo.allModels,
        notebookRepo.allNotebooks
    ) { settings, usedBytes, providers, models, notebooks ->
        CoreMetaBundle(
            settings = settings,
            usedBytes = usedBytes,
            providerCount = providers.size,
            modelCount = models.size,
            notebookCount = notebooks.size
        )
    }

    private data class RuntimeFlagsBundle(
        val conflicts: List<SyncConflictEntity>,
        val driveRuntime: DriveSyncRuntimeState,
        val testingGemini: Boolean,
        val geminiValidation: Pair<Boolean, String>?,
        val connectingGoogle: Boolean
    )

    private val runtimeFlagsFlow = combine(
        syncDao.observePendingConflicts(),
        driveSync.runtimeState,
        isTestingGemini,
        geminiValidationResult,
        isConnectingGoogle
    ) { conflicts, driveState, testing, validation, connecting ->
        RuntimeFlagsBundle(
            conflicts = conflicts,
            driveRuntime = driveState,
            testingGemini = testing,
            geminiValidation = validation,
            connectingGoogle = connecting
        )
    }

    private val updateBundleFlow = combine(
        updateManager.uiState,
        showUpdateDialog
    ) { state, showDialog ->
        state to showDialog
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        coreMetaFlow,
        runtimeFlagsFlow,
        updateBundleFlow,
        statusMessage
    ) { core, flags, (updState, dialogOpen), msg ->
        SettingsUiState(
            settings = core.settings,
            storageStats = notebookRepo.getStorageStats(core.usedBytes),
            providerCount = core.providerCount,
            modelCount = core.modelCount,
            notebookCount = core.notebookCount,
            pendingConflictCount = flags.conflicts.size,
            driveRuntime = flags.driveRuntime,
            isTestingGemini = flags.testingGemini,
            geminiValidationResult = flags.geminiValidation,
            isConnectingGoogle = flags.connectingGoogle,
            updateState = updState,
            showUpdateDialog = dialogOpen,
            installedVersionLabel = "v${installedInfo.versionName} (${installedInfo.versionCode})",
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

    fun getMaskedGeminiKey(settings: AppSettings): String {
        return geminiManager.getMaskedKey(settings)
    }

    fun saveAndValidateGeminiKey(
        rawKey: String,
        modelId: String,
        dimensions: Int,
        enabled: Boolean
    ) {
        viewModelScope.launch {
            isTestingGemini.value = true
            val result = geminiManager.saveAndValidateKey(
                rawApiKey = rawKey,
                modelId = modelId,
                dimensions = dimensions,
                enableAfterValidation = enabled
            )
            isTestingGemini.value = false
            geminiValidationResult.value = result.success to result.message
            statusMessage.value = result.message
        }
    }

    fun testGeminiConnection() {
        viewModelScope.launch {
            isTestingGemini.value = true
            val result = geminiManager.testStoredKey(uiState.value.settings)
            isTestingGemini.value = false
            geminiValidationResult.value = result.success to result.message
            statusMessage.value = result.message
        }
    }

    fun toggleGeminiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            geminiManager.setEnabled(uiState.value.settings, enabled)
            statusMessage.value = if (enabled) {
                "Gemini Embedding 2 ativado para indexação e busca vetorial."
            } else {
                "Gemini Embedding 2 desativado. Busca lexical BM25 permanece ativa offline."
            }
        }
    }

    fun updateGeminiModelAndDimensions(modelId: String, dimensions: Int) {
        viewModelScope.launch {
            geminiManager.updateModelAndDimensions(uiState.value.settings, modelId, dimensions)
            statusMessage.value = "Modelo ($modelId • ${dimensions}d) atualizado para Gemini Embedding 2."
        }
    }

    fun removeGeminiKey() {
        viewModelScope.launch {
            geminiManager.removeKey()
            geminiValidationResult.value = null
            statusMessage.value = "Chave do Gemini Embedding 2 removida do dispositivo."
        }
    }

    fun getMaskedOcrKey(settings: AppSettings): String {
        val plain = cryptoManager.decrypt(settings.ocrEncryptedApiKey)
        return cryptoManager.maskKey(plain)
    }

    fun saveOcrConfig(
        providerType: OcrProviderType,
        endpointUrl: String,
        modelId: String,
        rawKey: String,
        keepExisting: Boolean
    ) {
        viewModelScope.launch {
            val isLocal = providerType == OcrProviderType.ML_KIT_LOCAL
            val encrypted = when {
                isLocal -> null
                rawKey.isNotBlank() -> cryptoManager.encrypt(rawKey.trim())
                keepExisting -> null
                else -> ""
            }
            prefsStore.saveOcrConfig(
                providerType = providerType,
                endpointUrl = if (isLocal) "" else endpointUrl.ifBlank { providerType.defaultEndpoint },
                modelId = if (isLocal) "" else modelId,
                encryptedKey = encrypted
            )
            statusMessage.value = if (isLocal) {
                "OCR local offline (ML Kit) ativado."
            } else {
                "Configuração de OCR (${providerType.label}) salva."
            }
        }
    }

    fun clearOcrKey() {
        viewModelScope.launch {
            prefsStore.clearOcrKey()
            statusMessage.value = "Chave de API do OCR removida."
        }
    }

    fun getMaskedTranscriptionKey(settings: AppSettings): String {
        val plain = cryptoManager.decrypt(settings.transcriptionEncryptedApiKey)
        return cryptoManager.maskKey(plain)
    }

    fun saveTranscriptionConfig(
        providerType: TranscriptionProviderType,
        endpointUrl: String,
        modelId: String,
        language: String,
        rawKey: String,
        keepExisting: Boolean
    ) {
        viewModelScope.launch {
            val defaultModel = if (providerType == TranscriptionProviderType.GROQ_WHISPER) {
                "whisper-large-v3-turbo"
            } else {
                "whisper-1"
            }
            val encrypted = when {
                rawKey.isNotBlank() -> cryptoManager.encrypt(rawKey.trim())
                keepExisting -> null
                else -> ""
            }
            prefsStore.saveTranscriptionConfig(
                providerType = providerType,
                endpointUrl = endpointUrl.ifBlank { providerType.defaultEndpoint },
                modelId = modelId.ifBlank { defaultModel },
                language = language.ifBlank { "pt" },
                encryptedKey = encrypted
            )
            statusMessage.value = "Configuração de transcrição (${providerType.label}) salva."
        }
    }

    fun clearTranscriptionKey() {
        viewModelScope.launch {
            prefsStore.clearTranscriptionKey()
            statusMessage.value = "Chave de API de transcrição removida."
        }
    }

    fun checkForUpdates() {
        showUpdateDialog.value = true
        updateManager.checkForUpdates()
    }

    fun startOrResumeUpdateDownload(update: AvailableReleaseUpdate) {
        updateManager.startOrResumeDownload(update)
    }

    fun cancelUpdateDownload() {
        updateManager.cancelDownload()
    }

    fun dismissUpdateDialog() {
        showUpdateDialog.value = false
        updateManager.dismissOrResetToIdle()
    }

    fun openInstallPermissionSettings(activity: Activity) {
        val intent = updateManager.createUnknownSourcesSettingsIntent()
        runCatching {
            activity.startActivity(intent)
        }.onFailure {
            statusMessage.value = "Abra as Configurações do Android para permitir a instalação de APKs."
        }
    }

    fun launchUpdateInstaller(activity: Activity) {
        updateManager.launchSystemInstaller(activity).onFailure { err ->
            if (updateManager.uiState.value !is UpdateUiState.AwaitingInstallPermission) {
                statusMessage.value = err.message ?: "Não foi possível abrir o instalador do Android."
            }
        }
    }

    fun refreshUpdateStateAfterExternalReturn() {
        updateManager.refreshStateAfterExternalReturn()
    }

    fun connectGoogleDrive(
        activity: Activity,
        switchAccount: Boolean = false,
        onNeedsResolution: (IntentSenderRequest) -> Unit
    ) {
        viewModelScope.launch {
            isConnectingGoogle.value = true
            when (val outcome = driveAuth.connectGoogleAccount(activity, switchAccount = switchAccount)) {
                is GoogleConnectOutcome.Connected -> {
                    isConnectingGoogle.value = false
                    if (uiState.value.settings.driveSyncEnabled) {
                        DriveSyncWorker.schedulePeriodicSync(container.appContext, true)
                    }
                    statusMessage.value = "Conta Google conectada: ${outcome.profile.email}"
                }
                is GoogleConnectOutcome.NeedsConsentResolution -> {
                    isConnectingGoogle.value = false
                    onNeedsResolution(outcome.intentSenderRequest)
                }
                GoogleConnectOutcome.Cancelled -> {
                    isConnectingGoogle.value = false
                    statusMessage.value = "Conexão Google cancelada."
                }
                is GoogleConnectOutcome.Failed -> {
                    isConnectingGoogle.value = false
                    statusMessage.value = outcome.message
                }
            }
        }
    }

    fun completeGoogleConsent(activity: Activity, data: Intent?) {
        viewModelScope.launch {
            isConnectingGoogle.value = true
            val res = driveAuth.completeAuthorizationResult(activity, data)
            isConnectingGoogle.value = false
            res.onSuccess { profile ->
                if (uiState.value.settings.driveSyncEnabled) {
                    DriveSyncWorker.schedulePeriodicSync(container.appContext, true)
                }
                statusMessage.value = "Conta Google conectada: ${profile.email}"
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Autorização Google cancelada ou falhou."
            }
        }
    }

    fun refreshGoogleProfile() {
        viewModelScope.launch {
            isConnectingGoogle.value = true
            val res = driveAuth.refreshProfile()
            isConnectingGoogle.value = false
            res.onSuccess { profile ->
                statusMessage.value = "Perfil atualizado: ${profile.displayName ?: profile.email}"
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Falha ao atualizar perfil."
            }
        }
    }

    fun disconnectGoogleAccount() {
        viewModelScope.launch {
            isConnectingGoogle.value = true
            DriveSyncWorker.schedulePeriodicSync(container.appContext, false)
            driveAuth.disconnectAndRevoke()
            isConnectingGoogle.value = false
            statusMessage.value = "Conta Google desconectada e tokens locais removidos."
        }
    }

    fun setDriveSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.setDriveSyncEnabled(enabled)
            DriveSyncWorker.schedulePeriodicSync(container.appContext, enabled)
            statusMessage.value = if (enabled) {
                "Sincronização automática em segundo plano ativada."
            } else {
                "Sincronização automática pausada."
            }
        }
    }

    fun setDriveConflictPolicy(policy: DriveConflictPolicy) {
        viewModelScope.launch {
            prefsStore.setDriveConflictPolicy(policy)
            statusMessage.value = "Política de conflito atualizada."
        }
    }

    fun syncDriveNow() {
        viewModelScope.launch {
            val res = driveSync.syncNow()
            res.onSuccess { outcome ->
                statusMessage.value = "Sync concluído: ↑${outcome.uploadedCount} ↓${outcome.downloadedCount}" +
                    if (outcome.conflictsDetected > 0) " (${outcome.conflictsDetected} conflito(s))" else ""
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Falha na sincronização."
            }
        }
    }

    fun clearAllApiCredentials() {
        viewModelScope.launch {
            providerRepo.clearAllKeys()
            prefsStore.clearAllSensitiveCredentials()
            DriveSyncWorker.schedulePeriodicSync(container.appContext, false)
            geminiValidationResult.value = null
            statusMessage.value = "Todas as chaves de API e tokens OAuth foram removidos do dispositivo."
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
