package com.sjos.folionote.ui.notebook

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjos.folionote.AppContainer
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.data.local.CitationEntity
import com.sjos.folionote.data.local.ConversationEntity
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.EmbeddingGroup
import com.sjos.folionote.data.local.NotebookChunkStats
import com.sjos.folionote.data.local.SourceChunkCount
import com.sjos.folionote.data.local.EmbeddingEntity
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.ProcessingJobEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.StudioArtifactEntity
import com.sjos.folionote.data.repository.ChatRuntimeState
import com.sjos.folionote.engine.drive.DriveAuthResult
import com.sjos.folionote.engine.drive.DriveFileItem
import com.sjos.folionote.engine.drive.DriveHttpException
import com.sjos.folionote.engine.retrieval.RetrievedPassage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class NotebookTab(val label: String) {
    SOURCES("Fontes"),
    CHAT("Chat"),
    STUDIO("Estúdio")
}

data class NotebookUiState(
    val selectedTab: NotebookTab = NotebookTab.CHAT,
    val notebook: NotebookEntity? = null,
    val sources: List<SourceEntity> = emptyList(),
    val sourceSearchQuery: String = "",
    val visibleSources: List<SourceEntity> = emptyList(),
    val chunkStats: NotebookChunkStats = NotebookChunkStats(0, 0L),
    val chunkCountsBySource: Map<String, Int> = emptyMap(),
    val embeddingGroups: List<EmbeddingGroup> = emptyList(),
    val jobs: List<ProcessingJobEntity> = emptyList(),
    val conversations: List<ConversationEntity> = emptyList(),
    val activeConversation: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val citationsByMessage: Map<String, List<CitationEntity>> = emptyMap(),
    val studioArtifacts: List<StudioArtifactEntity> = emptyList(),
    val enabledChatModels: List<ModelEntity> = emptyList(),
    val activeEmbeddingModel: ModelEntity? = null,
    val hasIncompatibleEmbeddings: Boolean = false,
    val incompatibleEmbeddingLabels: List<String> = emptyList(),
    val providersById: Map<String, ProviderEntity> = emptyMap(),
    val selectedModel: ModelEntity? = null,
    val chatRuntime: ChatRuntimeState = ChatRuntimeState(),
    val settings: AppSettings = AppSettings(),
    val lexicalSearchQuery: String = "",
    val lexicalSearchResults: List<RetrievedPassage> = emptyList(),
    val isGeneratingArtifact: Boolean = false,
    val isClippingWebUrl: Boolean = false,
    val isLoadingDriveFiles: Boolean = false,
    val isImportingFromDrive: Boolean = false,
    val driveImportFiles: List<DriveFileItem> = emptyList(),
    val driveImportSearchQuery: String = "",
    val driveImportError: String? = null,
    val snackbarMessage: String? = null
)

class NotebookViewModel(
    private val notebookId: String,
    private val container: AppContainer
) : ViewModel() {

    private val notebookRepo = container.notebookRepository
    private val providerRepo = container.providerRepository
    private val chatOrchestrator = container.chatOrchestrator
    private val prefsStore = container.preferencesStore

    private val selectedTab = MutableStateFlow(NotebookTab.CHAT)
    private val sourceSearchQuery = MutableStateFlow("")
    private val activeConversationId = MutableStateFlow<String?>(null)
    private val manualSelectedModelId = MutableStateFlow<String?>(null)
    private val lexicalSearchQuery = MutableStateFlow("")
    private val lexicalSearchResults = MutableStateFlow<List<RetrievedPassage>>(emptyList())
    private val isGeneratingArtifact = MutableStateFlow(false)
    private val isClippingWebUrl = MutableStateFlow(false)
    private val driveImportState = MutableStateFlow(DriveImportUiState())
    private val snackbarMessage = MutableStateFlow<String?>(null)

    private var activeGenerationJob: Job? = null
    private var driveImportJob: Job? = null
    private var driveImportAccessToken: String? = null
    private val driveImportCancelled = AtomicBoolean(false)

    private data class DriveImportUiState(
        val isLoading: Boolean = false,
        val isImporting: Boolean = false,
        val files: List<DriveFileItem> = emptyList(),
        val searchQuery: String = "",
        val error: String? = null
    )

    init {
        viewModelScope.launch {
            val defaultConv = chatOrchestrator.getOrCreateDefaultConversation(notebookId)
            activeConversationId.value = defaultConv.id
        }
    }

    private data class NotebookSourcesBundle(
        val notebook: NotebookEntity?,
        val sources: List<SourceEntity>,
        val chunkStats: NotebookChunkStats,
        val chunkCountsBySource: List<SourceChunkCount>,
        val embeddingGroups: List<EmbeddingGroup>,
        val jobs: List<ProcessingJobEntity>
    )

    private data class ConversationBundle(
        val conversations: List<ConversationEntity>,
        val messages: List<MessageEntity>,
        val citations: List<CitationEntity>,
        val artifacts: List<StudioArtifactEntity>
    )

    private val chunkSummaryFlow = combine(
        notebookRepo.observeChunkStats(notebookId),
        notebookRepo.observeChunkCountsBySource(notebookId)
    ) { stats, counts -> stats to counts }

    private val sourcesBundleFlow = combine(
        notebookRepo.observeNotebook(notebookId),
        notebookRepo.observeSources(notebookId),
        chunkSummaryFlow,
        notebookRepo.observeEmbeddingGroups(notebookId),
        notebookRepo.observeProcessingJobs(notebookId)
    ) { nb, sources, chunkSummary, groups, jobs ->
        NotebookSourcesBundle(nb, sources, chunkSummary.first, chunkSummary.second, groups, jobs)
    }

    private val activeMessagesFlow = MutableStateFlow<List<MessageEntity>>(emptyList())
    private val activeCitationsFlow = MutableStateFlow<List<CitationEntity>>(emptyList())
    private var messageObserveJob: Job? = null

    init {
        viewModelScope.launch {
            activeConversationId.collect { convId ->
                messageObserveJob?.cancel()
                if (convId != null) {
                    messageObserveJob = viewModelScope.launch {
                        launch {
                            chatOrchestrator.observeMessages(convId).collect { activeMessagesFlow.value = it }
                        }
                        launch {
                            chatOrchestrator.observeCitations(convId).collect { activeCitationsFlow.value = it }
                        }
                    }
                }
            }
        }
    }

    private val conversationBundleFlow = combine(
        chatOrchestrator.observeConversations(notebookId),
        activeMessagesFlow,
        activeCitationsFlow,
        notebookRepo.observeStudioArtifacts(notebookId)
    ) { convs, msgs, cits, arts ->
        ConversationBundle(convs, msgs, cits, arts)
    }

    private val controlsBundleFlow = combine<Any?, ControlsData>(
        selectedTab,
        sourceSearchQuery,
        activeConversationId,
        manualSelectedModelId,
        isGeneratingArtifact,
        isClippingWebUrl,
        driveImportState,
        snackbarMessage
    ) { arr ->
        ControlsData(
            tab = arr[0] as NotebookTab,
            srcQuery = arr[1] as String,
            convId = arr[2] as String?,
            modelId = arr[3] as String?,
            genArtifact = arr[4] as Boolean,
            clippingWeb = arr[5] as Boolean,
            driveImport = arr[6] as DriveImportUiState,
            snack = arr[7] as String?
        )
    }

    private data class ControlsData(
        val tab: NotebookTab,
        val srcQuery: String,
        val convId: String?,
        val modelId: String?,
        val genArtifact: Boolean,
        val clippingWeb: Boolean,
        val driveImport: DriveImportUiState,
        val snack: String?
    )

    val uiState: StateFlow<NotebookUiState> = combine(
        sourcesBundleFlow,
        conversationBundleFlow,
        controlsBundleFlow,
        providerRepo.allModels,
        providerRepo.allProviders,
        chatOrchestrator.runtimeState,
        prefsStore.settingsFlow,
        lexicalSearchQuery,
        lexicalSearchResults
    ) { arr ->
        val srcBundle = arr[0] as NotebookSourcesBundle
        val convBundle = arr[1] as ConversationBundle
        val ctrl = arr[2] as ControlsData
        @Suppress("UNCHECKED_CAST")
        val allModels = arr[3] as List<ModelEntity>
        @Suppress("UNCHECKED_CAST")
        val providers = arr[4] as List<ProviderEntity>
        val runtime = arr[5] as ChatRuntimeState
        val settings = arr[6] as AppSettings
        val lexQ = arr[7] as String
        @Suppress("UNCHECKED_CAST")
        val lexRes = arr[8] as List<RetrievedPassage>

        val providersMap = providers.associateBy { it.id }
        val activeProvidersIds = providers.filter { it.isEnabled }.map { it.id }.toSet()
        val usableModels = allModels.filter { it.enabled && !it.isEmbeddingModel && it.providerId in activeProvidersIds }

        val activeEmbModel = if (settings.isGeminiEmbeddingActive) {
            container.geminiEmbeddingManager.buildDedicatedSpec(
                encryptedKey = settings.geminiEmbeddingEncryptedKey,
                modelId = settings.geminiEmbeddingModelId,
                dimensions = settings.geminiEmbeddingDimensions
            ).model
        } else {
            settings.activeEmbeddingModelId?.let { id ->
                allModels.firstOrNull { it.id == id }
            }
        }

        val incompatibleLabels = if (activeEmbModel != null && srcBundle.embeddingGroups.isNotEmpty()) {
            srcBundle.embeddingGroups
                .filter {
                    it.embeddingModelId != activeEmbModel.modelId ||
                        (activeEmbModel.embeddingDimensions != null && activeEmbModel.embeddingDimensions > 0 &&
                            it.dimensions != activeEmbModel.embeddingDimensions)
                }
                .map { "${it.embeddingModelId} (${it.dimensions}d)" }
                .distinct()
        } else {
            emptyList()
        }

        val distinctEmbeddingCollections = srcBundle.embeddingGroups
            .map { "${it.embeddingModelId} (${it.dimensions}d)" }
            .distinct()
        val hasMismatch = incompatibleLabels.isNotEmpty() || distinctEmbeddingCollections.size > 1

        val activeConv = convBundle.conversations.firstOrNull { it.id == ctrl.convId }
            ?: convBundle.conversations.firstOrNull()

        val chosenModelId = ctrl.modelId ?: activeConv?.selectedModelInternalId
        val selectedModel = usableModels.firstOrNull { it.id == chosenModelId }
            ?: usableModels.firstOrNull()

        val filteredSources = if (ctrl.srcQuery.isBlank()) {
            srcBundle.sources
        } else {
            srcBundle.sources.filter {
                it.name.contains(ctrl.srcQuery, ignoreCase = true) ||
                    it.extractedText.contains(ctrl.srcQuery, ignoreCase = true)
            }
        }

        NotebookUiState(
            selectedTab = ctrl.tab,
            notebook = srcBundle.notebook,
            sources = srcBundle.sources,
            visibleSources = filteredSources,
            sourceSearchQuery = ctrl.srcQuery,
            chunkStats = srcBundle.chunkStats,
            chunkCountsBySource = srcBundle.chunkCountsBySource.associate { it.sourceId to it.chunkCount },
            embeddingGroups = srcBundle.embeddingGroups,
            jobs = srcBundle.jobs,
            conversations = convBundle.conversations,
            activeConversation = activeConv,
            messages = convBundle.messages,
            citationsByMessage = convBundle.citations.groupBy { it.messageId },
            studioArtifacts = convBundle.artifacts,
            enabledChatModels = usableModels,
            activeEmbeddingModel = activeEmbModel,
            hasIncompatibleEmbeddings = hasMismatch,
            incompatibleEmbeddingLabels = if (incompatibleLabels.isNotEmpty()) incompatibleLabels else distinctEmbeddingCollections,
            providersById = providersMap,
            selectedModel = selectedModel,
            chatRuntime = runtime,
            settings = settings,
            lexicalSearchQuery = lexQ,
            lexicalSearchResults = lexRes,
            isGeneratingArtifact = ctrl.genArtifact,
            isClippingWebUrl = ctrl.clippingWeb,
            isLoadingDriveFiles = ctrl.driveImport.isLoading,
            isImportingFromDrive = ctrl.driveImport.isImporting,
            driveImportFiles = ctrl.driveImport.files,
            driveImportSearchQuery = ctrl.driveImport.searchQuery,
            driveImportError = ctrl.driveImport.error,
            snackbarMessage = ctrl.snack
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotebookUiState())

    fun selectTab(tab: NotebookTab) {
        selectedTab.value = tab
    }

    fun updateSourceSearch(query: String) {
        sourceSearchQuery.value = query
    }

    fun dismissSourcesBanner() {
        viewModelScope.launch { prefsStore.dismissSourcesBanner() }
    }

    fun acknowledgeExternalDisclosure() {
        viewModelScope.launch { prefsStore.acknowledgeExternalDisclosure() }
    }

    fun importUris(uris: List<Uri>, isFromGoogleDrive: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var imported = 0
            for (uri in uris) {
                runCatching {
                    notebookRepo.importUriSource(
                        notebookId = notebookId,
                        uri = uri,
                        isFromGoogleDrive = isFromGoogleDrive
                    )
                }
                    .onSuccess { imported++ }
                    .onFailure { snackbarMessage.value = "Erro ao importar arquivo: ${it.message}" }
            }
            if (imported > 0) {
                snackbarMessage.value = "$imported fonte(s) importada(s) e colocada(s) na fila de processamento."
            }
        }
    }

    fun prepareDriveBrowser(
        activity: Activity,
        onNeedsResolution: (IntentSenderRequest) -> Unit
    ) {
        driveImportJob?.cancel()
        driveImportJob = viewModelScope.launch {
            driveImportState.value = driveImportState.value.copy(isLoading = true, error = null)
            when (val res = container.googleDriveAuthManager.authorizeDriveFileImport(activity)) {
                is DriveAuthResult.Authorized -> {
                    driveImportAccessToken = res.accessToken
                    loadDriveFilesInternal(res.accessToken, driveImportState.value.searchQuery)
                }
                is DriveAuthResult.NeedsResolution -> {
                    driveImportState.value = driveImportState.value.copy(isLoading = false)
                    onNeedsResolution(res.intentSenderRequest)
                }
                DriveAuthResult.Cancelled -> {
                    driveImportState.value = driveImportState.value.copy(
                        isLoading = false,
                        error = "Autorização de leitura do Google Drive cancelada."
                    )
                }
                is DriveAuthResult.NetworkError -> {
                    driveImportState.value = driveImportState.value.copy(
                        isLoading = false,
                        error = "Sem conexão com a internet para listar arquivos do Google Drive."
                    )
                }
                is DriveAuthResult.Unavailable -> {
                    driveImportState.value = driveImportState.value.copy(
                        isLoading = false,
                        error = res.reason
                    )
                }
                is DriveAuthResult.Failed -> {
                    driveImportState.value = driveImportState.value.copy(
                        isLoading = false,
                        error = res.message
                    )
                }
            }
        }
    }

    fun completeDriveImportAuth(activity: Activity, data: Intent?) {
        driveImportJob?.cancel()
        driveImportJob = viewModelScope.launch {
            when (val res = container.googleDriveAuthManager.extractDriveImportAuthorizationResult(activity, data)) {
                is DriveAuthResult.Authorized -> {
                    driveImportAccessToken = res.accessToken
                    loadDriveFilesInternal(res.accessToken, driveImportState.value.searchQuery)
                }
                DriveAuthResult.Cancelled -> {
                    driveImportState.value = driveImportState.value.copy(
                        isLoading = false,
                        error = "Autorização de leitura do Google Drive cancelada."
                    )
                }
                is DriveAuthResult.NetworkError -> {
                    driveImportState.value = driveImportState.value.copy(
                        isLoading = false,
                        error = "Erro de rede ao autorizar Google Drive."
                    )
                }
                is DriveAuthResult.Unavailable -> {
                    driveImportState.value = driveImportState.value.copy(isLoading = false, error = res.reason)
                }
                is DriveAuthResult.Failed -> {
                    driveImportState.value = driveImportState.value.copy(isLoading = false, error = res.message)
                }
                is DriveAuthResult.NeedsResolution -> {
                    driveImportState.value = driveImportState.value.copy(
                        isLoading = false,
                        error = "Consentimento adicional necessário para ler arquivos do Drive."
                    )
                }
            }
        }
    }

    fun searchDriveImportFiles(query: String) {
        driveImportState.value = driveImportState.value.copy(searchQuery = query)
        val token = driveImportAccessToken ?: return
        driveImportJob?.cancel()
        driveImportJob = viewModelScope.launch {
            loadDriveFilesInternal(token, query)
        }
    }

    private suspend fun loadDriveFilesInternal(token: String, query: String) {
        driveImportState.value = driveImportState.value.copy(isLoading = true, error = null)
        val res = container.googleDriveApiClient.listUserDriveFiles(
            accessToken = token,
            nameQuery = query.takeIf { it.isNotBlank() }
        )
        res.onSuccess { items ->
            driveImportState.value = driveImportState.value.copy(
                isLoading = false,
                files = items,
                error = null
            )
        }.onFailure { err ->
            if (err is DriveHttpException && (err.code == 401 || err.code == 403)) {
                driveImportAccessToken = null
            }
            driveImportState.value = driveImportState.value.copy(
                isLoading = false,
                error = err.message ?: "Falha ao listar documentos no Google Drive."
            )
        }
    }

    fun importDriveDocument(item: DriveFileItem, onImported: () -> Unit = {}) {
        val token = driveImportAccessToken
        if (token.isNullOrBlank()) {
            driveImportState.value = driveImportState.value.copy(
                error = "Autorize novamente a leitura do Google Drive para importar."
            )
            return
        }
        driveImportCancelled.set(false)
        driveImportJob?.cancel()
        driveImportJob = viewModelScope.launch {
            driveImportState.value = driveImportState.value.copy(isImporting = true, error = null)
            val res = notebookRepo.importDriveFile(
                notebookId = notebookId,
                accessToken = token,
                item = item,
                apiClient = container.googleDriveApiClient,
                isCancelled = { driveImportCancelled.get() }
            )
            res.onSuccess { src ->
                driveImportState.value = driveImportState.value.copy(isImporting = false, error = null)
                snackbarMessage.value = "'${src.name}' importado do Google Drive e na fila de indexação."
                onImported()
            }.onFailure { err ->
                if (err is DriveHttpException && (err.code == 401 || err.code == 403)) {
                    driveImportAccessToken = null
                }
                val msg = if (err is CancellationException) {
                    "Importação do Google Drive cancelada."
                } else {
                    err.message ?: "Falha ao baixar documento do Google Drive."
                }
                driveImportState.value = driveImportState.value.copy(
                    isImporting = false,
                    error = msg
                )
            }
        }
    }

    fun cancelDriveImport() {
        driveImportCancelled.set(true)
        driveImportJob?.cancel()
        driveImportState.value = driveImportState.value.copy(
            isLoading = false,
            isImporting = false
        )
    }

    fun clearDriveImportError() {
        driveImportState.value = driveImportState.value.copy(error = null)
    }

    fun createNoteSource(title: String, content: String) {
        viewModelScope.launch {
            notebookRepo.createNoteSource(notebookId, title, content)
            snackbarMessage.value = "Nota adicionada às fontes e indexada."
        }
    }

    fun importWebUrl(rawUrl: String) {
        if (rawUrl.isBlank()) return
        viewModelScope.launch {
            isClippingWebUrl.value = true
            val res = notebookRepo.importWebUrl(notebookId, rawUrl)
            isClippingWebUrl.value = false
            res.onSuccess { src ->
                snackbarMessage.value = "Página capturada: ${src.name}"
            }.onFailure { err ->
                snackbarMessage.value = err.message ?: "Não foi possível capturar a URL."
            }
        }
    }

    fun toggleSourceSelected(source: SourceEntity) {
        viewModelScope.launch {
            notebookRepo.setSourceSelected(source.id, !source.isSelectedForChat)
        }
    }

    fun setAllSourcesSelected(selected: Boolean) {
        // Seleciona apenas as fontes visíveis (respeita o filtro de busca).
        val visibleIds = uiState.value.visibleSources.map { it.id }
        viewModelScope.launch {
            notebookRepo.setSourcesSelected(visibleIds, selected)
        }
    }

    fun reprocessSource(source: SourceEntity) {
        viewModelScope.launch {
            notebookRepo.reprocessSource(source)
            snackbarMessage.value = "Reprocessando '${source.name}'..."
        }
    }

    fun reindexAllSources() {
        viewModelScope.launch {
            notebookRepo.reindexNotebook(notebookId)
            snackbarMessage.value = "Reindexação em segundo plano iniciada."
        }
    }

    fun deleteSource(source: SourceEntity) {
        viewModelScope.launch {
            notebookRepo.deleteSource(source)
            snackbarMessage.value = "Fonte removida."
        }
    }

    fun selectModel(modelInternalId: String) {
        if (activeGenerationJob?.isActive == true) {
            // Trocar de modelo no meio de uma resposta travada: para a geração e mantém o parcial.
            stopGeneration()
            snackbarMessage.value = "Geração interrompida ao trocar de modelo."
        }
        manualSelectedModelId.value = modelInternalId
        val conv = uiState.value.activeConversation
        if (conv != null) {
            viewModelScope.launch {
                chatOrchestrator.updateConversationSettings(conv.copy(selectedModelInternalId = modelInternalId))
            }
        }
    }

    fun sendMessage(text: String) {
        val state = uiState.value
        val model = state.selectedModel
        val conv = state.activeConversation
        if (model == null) {
            snackbarMessage.value = "Cadastre ou habilite um modelo de IA nas configurações de Provedores."
            return
        }
        if (conv == null) return

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            chatOrchestrator.sendMessage(
                notebookId = notebookId,
                conversationId = conv.id,
                userText = text,
                modelInternalId = model.id
            )
        }
    }

    fun stopGeneration() {
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        val state = uiState.value
        val convId = state.activeConversation?.id
        val modelName = state.selectedModel?.displayName
        viewModelScope.launch {
            chatOrchestrator.cancelStreamingAndPreservePartial(convId, modelName)
        }
    }

    fun regenerateLastResponse() {
        val state = uiState.value
        val model = state.selectedModel ?: return
        val conv = state.activeConversation ?: return
        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            chatOrchestrator.regenerateLastAssistantResponse(notebookId, conv.id, model.id)
        }
    }

    fun resolveToolApproval(approved: Boolean) {
        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            chatOrchestrator.resolvePendingToolApproval(approved)
        }
    }

    fun togglePinMessage(message: MessageEntity) {
        viewModelScope.launch {
            chatOrchestrator.togglePinMessage(message)
            if (!message.isPinned) {
                notebookRepo.saveStudioArtifact(
                    notebookId = notebookId,
                    artifactType = "PINNED_NOTE",
                    title = message.content.lineSequence().firstOrNull()?.take(60) ?: "Resposta fixada",
                    content = message.content,
                    sourceCount = uiState.value.sources.count { it.isSelectedForChat },
                    modelDisplayName = message.modelDisplayName
                )
                snackbarMessage.value = "Resposta fixada e salva no Estúdio."
            }
        }
    }

    fun updateConversationConfig(
        goalPreset: String,
        responseLength: String,
        customPrompt: String
    ) {
        val conv = uiState.value.activeConversation ?: return
        viewModelScope.launch {
            chatOrchestrator.updateConversationSettings(
                conv.copy(
                    systemRolePreset = goalPreset,
                    responseLength = responseLength,
                    customSystemPrompt = customPrompt
                )
            )
            snackbarMessage.value = "Configurações da conversa atualizadas."
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val conv = chatOrchestrator.createNewConversation(
                notebookId = notebookId,
                title = "Nova conversa",
                modelInternalId = uiState.value.selectedModel?.id
            )
            activeConversationId.value = conv.id
            selectedTab.value = NotebookTab.CHAT
        }
    }

    fun switchConversation(conversationId: String) {
        activeConversationId.value = conversationId
        selectedTab.value = NotebookTab.CHAT
    }

    fun clearActiveConversationHistory() {
        val convId = uiState.value.activeConversation?.id ?: return
        viewModelScope.launch {
            chatOrchestrator.clearConversationMessages(convId)
            snackbarMessage.value = "Histórico desta conversa excluído."
        }
    }

    fun updateNotebookDetails(title: String, description: String, iconKey: String) {
        val nb = uiState.value.notebook ?: return
        viewModelScope.launch {
            notebookRepo.updateNotebook(nb.copy(title = title.trim().ifBlank { nb.title }, description = description.trim(), iconKey = iconKey))
            snackbarMessage.value = "Notebook atualizado."
        }
    }

    fun runOfflineLexicalSearch(query: String) {
        lexicalSearchQuery.value = query
        viewModelScope.launch {
            lexicalSearchResults.value = notebookRepo.searchLexicalInNotebook(notebookId, query)
        }
    }

    fun generateStudioArtifact(type: String, title: String, instructionPrompt: String) {
        val model = uiState.value.selectedModel
        if (model == null) {
            snackbarMessage.value = "Configure um modelo de IA para gerar materiais no Estúdio."
            return
        }
        viewModelScope.launch {
            isGeneratingArtifact.value = true
            val res = chatOrchestrator.generateStudioArtifact(
                notebookId = notebookId,
                artifactType = type,
                promptTitle = title,
                instructionPrompt = instructionPrompt,
                modelInternalId = model.id
            )
            isGeneratingArtifact.value = false
            res.onSuccess {
                snackbarMessage.value = "$title gerado e salvo localmente."
            }.onFailure {
                snackbarMessage.value = "Erro: ${it.message}"
            }
        }
    }

    fun deleteStudioArtifact(artifactId: String) {
        viewModelScope.launch { notebookRepo.deleteStudioArtifact(artifactId) }
    }

    fun clearChatError() = chatOrchestrator.clearError()
    fun clearSnackbar() {
        snackbarMessage.value = null
    }

    class Factory(
        private val notebookId: String,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NotebookViewModel(notebookId, container) as T
    }
}
