package com.example.data.repository

import com.example.core.preferences.AppPreferencesStore
import com.example.data.local.CitationEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.FolioNoteDatabase
import com.example.data.local.MessageEntity
import com.example.data.local.MessageRole
import com.example.data.local.ModelEntity
import com.example.data.local.ProviderEntity
import com.example.engine.ai.AiProviderClient
import com.example.engine.ai.ChatCompletionRequest
import com.example.engine.ai.ChatMessagePayload
import com.example.engine.ai.ChatStreamEvent
import com.example.engine.ai.ToolCallRequest
import com.example.engine.context.ContextBudgetPlanner
import com.example.engine.context.ContextBudgetReport
import com.example.engine.context.ContextPlanResult
import com.example.engine.context.TokenEstimator
import com.example.engine.indexing.EmbeddingProvider
import com.example.engine.retrieval.HybridRetriever
import com.example.engine.tools.ToolEngine
import com.example.engine.tools.ToolExecutionOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingToolApproval(
    val conversationId: String,
    val notebookId: String,
    val call: ToolCallRequest,
    val humanDescription: String,
    val assistantMessageId: String,
    val modelInternalId: String,
    val currentMessages: List<ChatMessagePayload>,
    val remainingRounds: Int,
    val citations: List<CitationEntity>
)

data class ChatRuntimeState(
    val isStreaming: Boolean = false,
    val streamingText: String = "",
    val activeStatusText: String = "",
    val lastBudgetReport: ContextBudgetReport? = null,
    val pendingToolApproval: PendingToolApproval? = null,
    val errorMessage: String? = null
)

class ChatOrchestrator(
    private val db: FolioNoteDatabase,
    private val aiClient: AiProviderClient,
    private val embeddingProvider: EmbeddingProvider,
    private val retriever: HybridRetriever,
    private val budgetPlanner: ContextBudgetPlanner,
    private val toolEngine: ToolEngine,
    private val preferencesStore: AppPreferencesStore
) {
    private val conversationDao = db.conversationAndMessageDao()
    private val providerDao = db.providerAndModelDao()
    private val sourceDao = db.sourceDao()
    private val chunkDao = db.chunkAndEmbeddingDao()
    private val toolDao = db.toolAndJobDao()

    private val _runtimeState = MutableStateFlow(ChatRuntimeState())
    val runtimeState: StateFlow<ChatRuntimeState> = _runtimeState.asStateFlow()

    fun observeConversations(notebookId: String): Flow<List<ConversationEntity>> =
        conversationDao.observeConversationsForNotebook(notebookId)

    fun observeConversation(conversationId: String): Flow<ConversationEntity?> =
        conversationDao.observeConversation(conversationId)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        conversationDao.observeMessagesForConversation(conversationId)

    fun observeCitations(conversationId: String): Flow<List<CitationEntity>> =
        conversationDao.observeCitationsForConversation(conversationId)

    suspend fun getOrCreateDefaultConversation(notebookId: String): ConversationEntity {
        val existing = conversationDao.observeConversationsForNotebook(notebookId).first().firstOrNull()
        if (existing != null) return existing
        return createNewConversation(notebookId, "Conversa principal")
    }

    suspend fun createNewConversation(
        notebookId: String,
        title: String = "Nova conversa",
        modelInternalId: String? = null
    ): ConversationEntity {
        val now = System.currentTimeMillis()
        val conv = ConversationEntity(
            id = UUID.randomUUID().toString(),
            notebookId = notebookId,
            title = title,
            selectedModelInternalId = modelInternalId,
            createdAt = now,
            updatedAt = now
        )
        conversationDao.upsertConversation(conv)
        return conv
    }

    suspend fun updateConversationSettings(conversation: ConversationEntity) {
        conversationDao.upsertConversation(conversation.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun clearConversationMessages(conversationId: String) {
        conversationDao.deleteMessagesForConversation(conversationId)
        conversationDao.getConversationById(conversationId)?.let { conv ->
            conversationDao.upsertConversation(
                conv.copy(runningSummary = "", summarizedUpToOrder = 0, updatedAt = System.currentTimeMillis())
            )
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteConversationById(conversationId)
    }

    suspend fun togglePinMessage(message: MessageEntity) {
        conversationDao.updateMessage(message.copy(isPinned = !message.isPinned))
    }

    fun clearError() {
        _runtimeState.value = _runtimeState.value.copy(errorMessage = null)
    }

    suspend fun sendMessage(
        notebookId: String,
        conversationId: String,
        userText: String,
        modelInternalId: String
    ) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return

        val model = providerDao.getModelByInternalId(modelInternalId)
        val provider = model?.let { providerDao.getProviderById(it.providerId) }
        if (model == null || provider == null || !model.enabled || !provider.isEnabled) {
            _runtimeState.value = _runtimeState.value.copy(
                errorMessage = "Selecione um modelo habilitado e um provedor ativo antes de enviar."
            )
            return
        }

        val conversation = conversationDao.getConversationById(conversationId) ?: return
        if (conversation.selectedModelInternalId != model.id) {
            conversationDao.upsertConversation(conversation.copy(selectedModelInternalId = model.id))
        }

        val maxOrder = conversationDao.getMaxOrderIndex(conversationId)
        val userMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.USER.name,
            content = trimmed,
            tokenCountEstimate = TokenEstimator.estimateTokens(trimmed),
            orderIndex = maxOrder + 1
        )
        conversationDao.upsertMessage(userMsg)
        runCompletionTurn(notebookId, conversationId, trimmed, model, provider)
    }

    suspend fun regenerateLastAssistantResponse(
        notebookId: String,
        conversationId: String,
        modelInternalId: String
    ) {
        val messages = conversationDao.getMessagesForConversationOnce(conversationId)
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER.name } ?: return
        val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT.name }
        if (lastAssistant != null && lastAssistant.orderIndex > lastUser.orderIndex) {
            conversationDao.deleteMessageById(lastAssistant.id)
        }
        val model = providerDao.getModelByInternalId(modelInternalId) ?: return
        val provider = providerDao.getProviderById(model.providerId) ?: return
        runCompletionTurn(notebookId, conversationId, lastUser.content, model, provider)
    }

    suspend fun resolvePendingToolApproval(approved: Boolean) {
        val pending = _runtimeState.value.pendingToolApproval ?: return
        _runtimeState.value = _runtimeState.value.copy(pendingToolApproval = null)

        val model = providerDao.getModelByInternalId(pending.modelInternalId) ?: return
        val provider = providerDao.getProviderById(model.providerId) ?: return
        val enabledTools = toolDao.getEnabledToolsOnce()

        val outcome = if (approved) {
            toolEngine.evaluateAndExecute(pending.notebookId, pending.call, enabledTools, userApproved = true)
        } else {
            ToolExecutionOutcome.Rejected("O usuário recusou a execução de '${pending.call.name}'.")
        }

        val toolContent = when (outcome) {
            is ToolExecutionOutcome.Executed -> outcome.outputJson
            is ToolExecutionOutcome.Rejected -> JSONObject().put("error", outcome.reason).toString()
            is ToolExecutionOutcome.RequiresUserApproval -> JSONObject().put("error", "Aprovação pendente").toString()
        }

        val nextMessages = pending.currentMessages.toMutableList()
        nextMessages.add(
            ChatMessagePayload(
                role = "tool",
                content = toolContent,
                toolCallId = pending.call.id,
                name = pending.call.name
            )
        )

        executeModelLoop(
            notebookId = pending.notebookId,
            conversationId = pending.conversationId,
            model = model,
            provider = provider,
            messagesPayload = nextMessages,
            citations = pending.citations,
            remainingToolRounds = pending.remainingRounds,
            existingAssistantMessageId = pending.assistantMessageId
        )
    }

    fun resetStreamingUiAfterCancel() {
        _runtimeState.value = _runtimeState.value.copy(
            isStreaming = false,
            streamingText = "",
            activeStatusText = ""
        )
    }

    private suspend fun runCompletionTurn(
        notebookId: String,
        conversationId: String,
        userQuery: String,
        model: ModelEntity,
        provider: ProviderEntity
    ) {
        _runtimeState.value = ChatRuntimeState(
            isStreaming = true,
            activeStatusText = "Buscando trechos relevantes..."
        )

        val conversation = conversationDao.getConversationById(conversationId) ?: return
        val fullHistory = conversationDao.getMessagesForConversationOnce(conversationId)
            .dropLastWhile { it.role == MessageRole.USER.name && it.content == userQuery }

        val settings = preferencesStore.settingsFlow.first()
        val selectedSources = sourceDao.getSelectedReadySources(notebookId)
        val sourceIds = selectedSources.map { it.id }

        val chunks = if (sourceIds.isNotEmpty()) {
            chunkDao.getChunksForSources(sourceIds)
        } else {
            emptyList()
        }

        // Optional semantic query embedding if a decoupled embedding model is active
        var queryEmbedding: FloatArray? = null
        var storedEmbeddings = emptyList<com.example.data.local.EmbeddingEntity>()
        val embModelId = settings.activeEmbeddingModelId
        if (!embModelId.isNullOrBlank() && chunks.isNotEmpty()) {
            val embModel = providerDao.getModelByInternalId(embModelId)
            val embProvider = embModel?.let { providerDao.getProviderById(it.providerId) }
            if (embModel != null && embProvider != null && embModel.enabled && embProvider.isEnabled) {
                val qRes = embeddingProvider.embedTexts(embProvider, embModel, listOf(userQuery))
                if (qRes.isSuccess) {
                    val vecResult = qRes.getOrThrow()
                    queryEmbedding = vecResult.vectors.firstOrNull()
                    if (vecResult.dimensions > 0) {
                        storedEmbeddings = chunkDao.getEmbeddingsForSources(
                            sourceIds = sourceIds,
                            embeddingModelId = embModel.modelId,
                            dimensions = vecResult.dimensions
                        )
                    }
                }
            }
        }

        val passages = retriever.retrieve(
            query = userQuery,
            sources = selectedSources,
            chunks = chunks,
            embeddings = storedEmbeddings,
            queryEmbedding = queryEmbedding,
            topK = settings.ragTopK
        )

        val enabledTools = toolDao.getEnabledToolsOnce()
        val plan = budgetPlanner.buildContextPlan(
            model = model,
            conversation = conversation,
            fullHistory = fullHistory,
            userQuery = userQuery,
            retrievedPassages = passages,
            enabledTools = enabledTools
        )

        when (plan) {
            is ContextPlanResult.BudgetExceeded -> {
                _runtimeState.value = ChatRuntimeState(
                    isStreaming = false,
                    lastBudgetReport = plan.report,
                    errorMessage = plan.reason
                )
                return
            }
            is ContextPlanResult.Ready -> {
                _runtimeState.value = _runtimeState.value.copy(
                    lastBudgetReport = plan.report,
                    activeStatusText = "Conectando a ${provider.name}..."
                )

                // Progressive background summary if conversation grew beyond recent budget
                if (plan.report.needsProgressiveSummary) {
                    runCatching {
                        updateProgressiveSummary(conversation, fullHistory, plan.report.includedHistoryCount, model, provider)
                    }
                }

                executeModelLoop(
                    notebookId = notebookId,
                    conversationId = conversationId,
                    model = model,
                    provider = provider,
                    messagesPayload = plan.messages,
                    citations = plan.citations,
                    remainingToolRounds = 4,
                    existingAssistantMessageId = null
                )
            }
        }
    }

    private suspend fun executeModelLoop(
        notebookId: String,
        conversationId: String,
        model: ModelEntity,
        provider: ProviderEntity,
        messagesPayload: List<ChatMessagePayload>,
        citations: List<CitationEntity>,
        remainingToolRounds: Int,
        existingAssistantMessageId: String?
    ) {
        val enabledTools = toolDao.getEnabledToolsOnce()
        val toolSchemas = if (model.supportsTools) {
            enabledTools.map {
                com.example.engine.ai.ToolSchemaPayload(
                    name = it.name,
                    description = it.description,
                    parametersSchemaJson = it.parametersSchemaJson
                )
            }
        } else {
            emptyList()
        }

        val req = ChatCompletionRequest(
            messages = messagesPayload,
            tools = toolSchemas,
            temperature = model.temperature,
            topP = model.topP,
            frequencyPenalty = model.frequencyPenalty,
            presencePenalty = model.presencePenalty,
            maxOutputTokens = model.maxOutputTokens,
            stream = model.supportsStreaming
        )

        val textBuffer = StringBuilder()
        var pendingCalls: List<ToolCallRequest> = emptyList()
        var streamError: String? = null

        aiClient.streamChatCompletion(provider, model, req).collect { event ->
            when (event) {
                is ChatStreamEvent.TextDelta -> {
                    textBuffer.append(event.text)
                    _runtimeState.value = _runtimeState.value.copy(
                        isStreaming = true,
                        streamingText = textBuffer.toString(),
                        activeStatusText = "${model.displayName} respondendo..."
                    )
                }
                is ChatStreamEvent.ToolCallsReady -> {
                    pendingCalls = event.calls
                }
                is ChatStreamEvent.Error -> {
                    streamError = event.message
                }
                is ChatStreamEvent.Completed -> Unit
            }
        }

        if (streamError != null) {
            val partial = textBuffer.toString().trim()
            if (partial.isNotEmpty()) {
                saveAssistantMessage(
                    conversationId = conversationId,
                    content = "$partial\n\n[Interrompido: $streamError]",
                    modelDisplayName = model.displayName,
                    citations = citations,
                    isError = true,
                    existingId = existingAssistantMessageId
                )
            }
            _runtimeState.value = _runtimeState.value.copy(
                isStreaming = false,
                streamingText = "",
                activeStatusText = "",
                errorMessage = streamError
            )
            return
        }

        if (pendingCalls.isNotEmpty() && remainingToolRounds > 0) {
            val nextPayload = messagesPayload.toMutableList()
            nextPayload.add(
                ChatMessagePayload(
                    role = "assistant",
                    content = textBuffer.toString().takeIf { it.isNotBlank() },
                    toolCalls = pendingCalls
                )
            )

            for (call in pendingCalls) {
                _runtimeState.value = _runtimeState.value.copy(
                    activeStatusText = "Executando ferramenta: ${call.name}..."
                )
                when (val outcome = toolEngine.evaluateAndExecute(notebookId, call, enabledTools, userApproved = false)) {
                    is ToolExecutionOutcome.Executed -> {
                        nextPayload.add(
                            ChatMessagePayload(
                                role = "tool",
                                content = outcome.outputJson,
                                toolCallId = call.id,
                                name = call.name
                            )
                        )
                    }
                    is ToolExecutionOutcome.Rejected -> {
                        nextPayload.add(
                            ChatMessagePayload(
                                role = "tool",
                                content = JSONObject().put("error", outcome.reason).toString(),
                                toolCallId = call.id,
                                name = call.name
                            )
                        )
                    }
                    is ToolExecutionOutcome.RequiresUserApproval -> {
                        val draftId = existingAssistantMessageId ?: UUID.randomUUID().toString()
                        _runtimeState.value = _runtimeState.value.copy(
                            isStreaming = false,
                            activeStatusText = "Aguardando autorização...",
                            pendingToolApproval = PendingToolApproval(
                                conversationId = conversationId,
                                notebookId = notebookId,
                                call = call,
                                humanDescription = outcome.humanDescription,
                                assistantMessageId = draftId,
                                modelInternalId = model.id,
                                currentMessages = nextPayload,
                                remainingRounds = remainingToolRounds - 1,
                                citations = citations
                            )
                        )
                        return
                    }
                }
            }

            executeModelLoop(
                notebookId = notebookId,
                conversationId = conversationId,
                model = model,
                provider = provider,
                messagesPayload = nextPayload,
                citations = citations,
                remainingToolRounds = remainingToolRounds - 1,
                existingAssistantMessageId = existingAssistantMessageId
            )
            return
        }

        val finalContent = textBuffer.toString().trim().ifEmpty {
            "O modelo finalizou sem retornar texto."
        }
        saveAssistantMessage(
            conversationId = conversationId,
            content = finalContent,
            modelDisplayName = model.displayName,
            citations = citations,
            isError = false,
            existingId = existingAssistantMessageId
        )

        db.notebookDao().getNotebookById(notebookId)?.let { nb ->
            db.notebookDao().upsertNotebook(nb.copy(updatedAt = System.currentTimeMillis()))
        }

        _runtimeState.value = _runtimeState.value.copy(
            isStreaming = false,
            streamingText = "",
            activeStatusText = ""
        )
    }

    private suspend fun saveAssistantMessage(
        conversationId: String,
        content: String,
        modelDisplayName: String,
        citations: List<CitationEntity>,
        isError: Boolean,
        existingId: String?
    ) {
        val msgId = existingId ?: UUID.randomUUID().toString()
        val nextOrder = conversationDao.getMaxOrderIndex(conversationId) + 1
        val usedContext = _runtimeState.value.lastBudgetReport?.totalUsedInputTokens ?: 0
        conversationDao.upsertMessage(
            MessageEntity(
                id = msgId,
                conversationId = conversationId,
                role = MessageRole.ASSISTANT.name,
                content = content,
                modelDisplayName = modelDisplayName,
                tokenCountEstimate = TokenEstimator.estimateTokens(content),
                contextTokensUsed = usedContext,
                isError = isError,
                orderIndex = nextOrder
            )
        )
        if (citations.isNotEmpty()) {
            conversationDao.insertCitations(citations.map { it.copy(messageId = msgId) })
        }
        conversationDao.getConversationById(conversationId)?.let { conv ->
            val updatedTitle = if (conv.title == "Conversa principal" || conv.title == "Nova conversa") {
                val firstUser = conversationDao.getMessagesForConversationOnce(conversationId)
                    .firstOrNull { it.role == MessageRole.USER.name }
                firstUser?.content?.take(44)?.trim()?.ifBlank { conv.title } ?: conv.title
            } else {
                conv.title
            }
            conversationDao.upsertConversation(conv.copy(title = updatedTitle, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Generates a progressive summary of older conversation turns without deleting original messages.
     */
    private suspend fun updateProgressiveSummary(
        conversation: ConversationEntity,
        fullHistory: List<MessageEntity>,
        recentKeptCount: Int,
        model: ModelEntity,
        provider: ProviderEntity
    ) {
        val olderMessages = fullHistory.dropLast(recentKeptCount)
            .filter { it.orderIndex > conversation.summarizedUpToOrder }
        if (olderMessages.size < 4) return

        val excerpt = olderMessages.take(8).joinToString("\n") { "${it.role}: ${it.content.take(300)}" }
        val prompt = buildString {
            append("Atualize o resumo objetivo desta conversa mantendo fatos, decisões e dúvidas em aberto (máx. 180 palavras).\n")
            if (conversation.runningSummary.isNotBlank()) {
                append("Resumo anterior:\n${conversation.runningSummary}\n\n")
            }
            append("Novas mensagens:\n$excerpt")
        }

        val summaryReq = ChatCompletionRequest(
            messages = listOf(ChatMessagePayload(role = "user", content = prompt)),
            maxOutputTokens = 320,
            temperature = 0.2f,
            stream = false
        )
        val sb = StringBuilder()
        aiClient.streamChatCompletion(provider, model, summaryReq).collect { ev ->
            if (ev is ChatStreamEvent.TextDelta) sb.append(ev.text)
        }
        val newSummary = sb.toString().trim()
        if (newSummary.isNotEmpty()) {
            conversationDao.upsertConversation(
                conversation.copy(
                    runningSummary = newSummary,
                    summarizedUpToOrder = olderMessages.last().orderIndex
                )
            )
        }
    }

    /**
     * Generates a Studio artifact (Resumo, Guia de Estudo, Mapa de Tópicos, Cartões Didáticos, Quiz)
     * using the selected provider & model and saves it locally.
     */
    suspend fun generateStudioArtifact(
        notebookId: String,
        artifactType: String,
        promptTitle: String,
        instructionPrompt: String,
        modelInternalId: String
    ): Result<String> {
        val model = providerDao.getModelByInternalId(modelInternalId)
            ?: return Result.failure(IllegalStateException("Nenhum modelo selecionado ou habilitado."))
        val provider = providerDao.getProviderById(model.providerId)
            ?: return Result.failure(IllegalStateException("Provedor não encontrado."))

        val sources = sourceDao.getSelectedReadySources(notebookId)
        if (sources.isEmpty()) {
            return Result.failure(IllegalStateException("Adicione e selecione pelo menos uma fonte processada."))
        }
        val chunks = chunkDao.getChunksForSources(sources.map { it.id })
        val budgetChars = ((model.contextWindow - model.maxOutputTokens - 512).coerceAtLeast(1000) * 3)
        val contextJoined = buildString {
            for (chunk in chunks) {
                val next = "[${sources.firstOrNull { it.id == chunk.sourceId }?.name ?: "Fonte"} • p.${chunk.pageNumber}]\n${chunk.content}\n\n"
                if (length + next.length > budgetChars) break
                append(next)
            }
        }

        val req = ChatCompletionRequest(
            messages = listOf(
                ChatMessagePayload("system", "Você gera materiais de estudo claros e estruturados em Markdown baseados estritamente nas fontes fornecidas."),
                ChatMessagePayload("user", "$instructionPrompt\n\nFontes:\n$contextJoined")
            ),
            maxOutputTokens = model.maxOutputTokens,
            temperature = 0.4f,
            stream = false
        )

        val sb = StringBuilder()
        var err: String? = null
        aiClient.streamChatCompletion(provider, model, req).collect { ev ->
            when (ev) {
                is ChatStreamEvent.TextDelta -> sb.append(ev.text)
                is ChatStreamEvent.Error -> err = ev.message
                else -> Unit
            }
        }
        if (err != null) return Result.failure(IllegalStateException(err))

        val content = sb.toString().trim()
        if (content.isEmpty()) return Result.failure(IllegalStateException("Resposta vazia do modelo."))

        conversationDao.upsertStudioArtifact(
            com.example.data.local.StudioArtifactEntity(
                id = UUID.randomUUID().toString(),
                notebookId = notebookId,
                artifactType = artifactType,
                title = promptTitle,
                content = content,
                sourceCount = sources.size,
                modelDisplayName = model.displayName
            )
        )
        return Result.success(content)
    }
}
