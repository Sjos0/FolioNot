package com.sjos.folionote.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjos.folionote.AppContainer
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.ToolDefinitionEntity
import com.sjos.folionote.engine.ai.ConnectionTestResult
import com.sjos.folionote.engine.ai.DiscoveredModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiscoveryDialogState(
    val provider: ProviderEntity,
    val isLoading: Boolean = false,
    val discovered: List<DiscoveredModel> = emptyList(),
    val selectedModelIds: Set<String> = emptySet(),
    val errorMessage: String? = null
)

data class ProvidersUiState(
    val providers: List<ProviderEntity> = emptyList(),
    val models: List<ModelEntity> = emptyList(),
    val tools: List<ToolDefinitionEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val testingProviderId: String? = null,
    val testResultByProvider: Map<String, ConnectionTestResult> = emptyMap(),
    val discoveryState: DiscoveryDialogState? = null,
    val statusMessage: String? = null
)

class ProvidersViewModel(private val container: AppContainer) : ViewModel() {

    private val providerRepo = container.providerRepository
    private val prefsStore = container.preferencesStore
    private val toolDao = container.database.toolAndJobDao()

    private val testingProviderId = MutableStateFlow<String?>(null)
    private val testResults = MutableStateFlow<Map<String, ConnectionTestResult>>(emptyMap())
    private val discoveryState = MutableStateFlow<DiscoveryDialogState?>(null)
    private val statusMessage = MutableStateFlow<String?>(null)

    private val transientBundle = combine(
        testingProviderId,
        testResults,
        discoveryState,
        statusMessage
    ) { tId, tRes, disc, msg ->
        TransientState(tId, tRes, disc, msg)
    }

    private data class TransientState(
        val testingProviderId: String?,
        val testResults: Map<String, ConnectionTestResult>,
        val discoveryState: DiscoveryDialogState?,
        val statusMessage: String?
    )

    val uiState: StateFlow<ProvidersUiState> = combine(
        providerRepo.allProviders,
        providerRepo.allModels,
        toolDao.observeAllTools(),
        prefsStore.settingsFlow,
        transientBundle
    ) { providers, models, tools, settings, tr ->
        ProvidersUiState(
            providers = providers,
            models = models,
            tools = tools,
            settings = settings,
            testingProviderId = tr.testingProviderId,
            testResultByProvider = tr.testResults,
            discoveryState = tr.discoveryState,
            statusMessage = tr.statusMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProvidersUiState())

    fun getMaskedKey(provider: ProviderEntity): String = providerRepo.getMaskedKey(provider)

    fun saveProvider(
        existingId: String?,
        name: String,
        baseUrl: String,
        chatPath: String,
        modelsPath: String,
        embeddingsPath: String,
        authHeaderName: String,
        authPrefix: String,
        plainApiKeyInput: String,
        keepExistingKeyIfBlank: Boolean,
        extraHeadersJson: String,
        defaultModelId: String?,
        timeoutSeconds: Int,
        supportsStreamingDefault: Boolean,
        extraParamsJson: String,
        providerType: String,
        isEnabled: Boolean
    ) {
        viewModelScope.launch {
            providerRepo.saveProvider(
                existingId = existingId,
                name = name,
                baseUrl = baseUrl,
                chatPath = chatPath,
                modelsPath = modelsPath,
                embeddingsPath = embeddingsPath,
                authHeaderName = authHeaderName,
                authPrefix = authPrefix,
                plainApiKeyInput = plainApiKeyInput,
                keepExistingKeyIfBlank = keepExistingKeyIfBlank,
                extraHeadersJson = extraHeadersJson,
                defaultModelId = defaultModelId,
                timeoutSeconds = timeoutSeconds,
                supportsStreamingDefault = supportsStreamingDefault,
                extraParamsJson = extraParamsJson,
                providerType = providerType,
                isEnabled = isEnabled
            )
            statusMessage.value = "Provedor salvo com segurança."
        }
    }

    fun duplicateProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            providerRepo.duplicateProvider(provider)
            statusMessage.value = "Provedor duplicado."
        }
    }

    fun toggleProviderEnabled(provider: ProviderEntity) {
        viewModelScope.launch { providerRepo.toggleProviderEnabled(provider) }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            providerRepo.deleteProvider(providerId)
            statusMessage.value = "Provedor excluído."
        }
    }

    fun clearProviderKey(providerId: String) {
        viewModelScope.launch {
            providerRepo.clearProviderKey(providerId)
            statusMessage.value = "Credencial removida do dispositivo."
        }
    }

    fun testConnection(provider: ProviderEntity) {
        viewModelScope.launch {
            testingProviderId.value = provider.id
            val result = providerRepo.testConnection(provider)
            testResults.value = testResults.value + (provider.id to result)
            testingProviderId.value = null
        }
    }

    fun openFetchModels(provider: ProviderEntity) {
        discoveryState.value = DiscoveryDialogState(provider = provider, isLoading = true)
        refreshDiscoveryModels(provider)
    }

    fun refreshDiscoveryModels(provider: ProviderEntity) {
        viewModelScope.launch {
            discoveryState.value = DiscoveryDialogState(provider = provider, isLoading = true)
            val result = providerRepo.fetchModelsFromEndpoint(provider)
            val existingIds = uiState.value.models
                .filter { it.providerId == provider.id }
                .map { it.modelId }
                .toSet()

            result.onSuccess { list ->
                discoveryState.value = DiscoveryDialogState(
                    provider = provider,
                    isLoading = false,
                    discovered = list,
                    selectedModelIds = list.map { it.modelId }.filter { it in existingIds }.toSet()
                )
            }.onFailure { err ->
                discoveryState.value = DiscoveryDialogState(
                    provider = provider,
                    isLoading = false,
                    errorMessage = err.message ?: "Erro ao buscar modelos."
                )
            }
        }
    }

    fun toggleDiscoveredSelection(modelId: String) {
        val current = discoveryState.value ?: return
        val next = if (modelId in current.selectedModelIds) {
            current.selectedModelIds - modelId
        } else {
            current.selectedModelIds + modelId
        }
        discoveryState.value = current.copy(selectedModelIds = next)
    }

    fun selectAllDiscovered(selectAll: Boolean) {
        val current = discoveryState.value ?: return
        val next = if (selectAll) current.discovered.map { it.modelId }.toSet() else emptySet()
        discoveryState.value = current.copy(selectedModelIds = next)
    }

    fun confirmAddDiscoveredModels() {
        val current = discoveryState.value ?: return
        val chosen = current.discovered.filter { it.modelId in current.selectedModelIds }
        if (chosen.isEmpty()) {
            discoveryState.value = null
            return
        }
        viewModelScope.launch {
            providerRepo.addDiscoveredModels(current.provider.id, chosen)
            discoveryState.value = null
            statusMessage.value = "${chosen.size} modelo(s) adicionado(s)/atualizado(s) preservando configurações locais."
        }
    }

    fun dismissDiscovery() {
        discoveryState.value = null
    }

    fun saveModel(model: ModelEntity) {
        viewModelScope.launch {
            providerRepo.saveModel(model)
            statusMessage.value = "Modelo '${model.displayName}' salvo."
        }
    }

    fun deleteModel(internalId: String) {
        viewModelScope.launch {
            if (uiState.value.settings.activeEmbeddingModelId == internalId) {
                prefsStore.setActiveEmbeddingModelId(null)
            }
            providerRepo.deleteModel(internalId)
            statusMessage.value = "Modelo removido da biblioteca."
        }
    }

    fun setActiveEmbeddingModel(modelInternalId: String?) {
        viewModelScope.launch {
            prefsStore.setActiveEmbeddingModelId(modelInternalId)
            statusMessage.value = if (modelInternalId == null) {
                "Modo lexical offline selecionado para buscas."
            } else {
                "Provedor de embeddings atualizado. Reindexe notebooks existentes para migrar coleções."
            }
        }
    }

    fun toggleToolEnabled(tool: ToolDefinitionEntity) {
        viewModelScope.launch { toolDao.setToolEnabled(tool.id, !tool.enabled) }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProvidersViewModel(container) as T
    }
}
