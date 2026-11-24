package com.sjos.folionote.ui.notebook

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjos.folionote.AppContainer
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.data.local.CitationEntity
import com.sjos.folionote.data.local.ConversationEntity
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.EmbeddingEntity
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.ProcessingJobEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.StudioArtifactEntity
import com.sjos.folionote.data.repository.ChatRuntimeState
import com.sjos.folionote.engine.retrieval.RetrievedPassage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val chunks: List<DocumentChunkEntity> = emptyList(),
    val embeddings: List<EmbeddingEntity> = emptyList(),
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
    private val snackbarMessage = MutableStateFlow<String?>(null)

    private var activeGenerationJob: Job? = null

    init {
        viewModelScope.launch {
            val defaultConv = chatOrchestrator.getOrCreateDefaultConversation(notebookId)
            activeConversationId.value = defaultConv.id
        }
    }

    private data class NotebookSourcesBundle(
        val notebook: NotebookEntity?,
        val sources: List<SourceEntity>,
        val chunks: List<DocumentChunkEntity>,
        val embeddings: List<EmbeddingEntity>,
        val jobs: List<ProcessingJobEntity>
    )

    private data class ConversationBundle(
        val conversations: List<ConversationEntity>,
        val messages: List<MessageEntity>,
        val citations: List<CitationEntity>,
        val artifacts: List<StudioArtifactEntity>
    )

    private val sourcesBundleFlow = combine(
        notebookRepo.observeNotebook(notebookId),
        notebookRepo.observeSources(notebookId),
        notebookRepo.observeChunksForNotebook(notebookId),
        notebookRepo.observeEmbeddingsForNotebook(notebookId),
        notebookRepo.observeProcessingJobs(notebookId)
    ) { nb, sources, chunks, embeddings, jobs ->
        NotebookSourcesBundle(nb, sources, chunks, embeddings, jobs)
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
        snackbarMessage
    ) { arr ->
        ControlsData(
            tab = arr[0] as NotebookTab,
            srcQuery = arr[1] as String,
            convId = arr[2] as String?,
            modelId = arr[3] as String?,
            genArtifact = arr[4] as Boolean,
            snack = arr[5] as String?
        )
    }

    private data class ControlsData(
        val tab: NotebookTab,
        val srcQuery: String,
        val convId: String?,
        val modelId: String?,
        val genArtifact: Boolean,
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

        val activeEmbModel = settings.activeEmbeddingModelId?.let { id ->
            allModels.firstOrNull { it.id == id }
        }

        val incompatibleLabels = if (activeEmbModel != null && srcBundle.embeddings.isNotEmpty()) {
            srcBundle.embeddings
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

        val distinctEmbeddingCollections = srcBundle.embeddings
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
            sources = filteredSources,
            sourceSearchQuery = ctrl.srcQuery,
            chunks = srcBundle.chunks,
            embeddings = srcBundle.embeddings,
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

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var imported = 0
            for (uri in uris) {
                runCatching { notebookRepo.importUriSource(notebookId, uri) }
                    .onSuccess { imported++ }
                    .onFailure { snackbarMessage.value = "Erro ao importar arquivo: ${it.message}" }
            }
            if (imported > 0) {
                snackbarMessage.value = "$imported fonte(s) importada(s) e colocada(s) na fila de processamento."
            }
        }
    }

    fun createNoteSource(title: String, content: String) {
        viewModelScope.launch {
            notebookRepo.createNoteSource(notebookId, title, content)
            snackbarMessage.value = "Nota adicionada às fontes e indexada."
        }
    }

    fun toggleSourceSelected(source: SourceEntity) {
        viewModelScope.launch {
            notebookRepo.setSourceSelected(source.id, !source.isSelectedForChat)
        }
    }

    fun setAllSourcesSelected(selected: Boolean) {
        viewModelScope.launch {
            notebookRepo.setAllSourcesSelected(notebookId, selected)
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
