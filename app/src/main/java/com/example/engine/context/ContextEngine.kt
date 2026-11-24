package com.example.engine.context

import com.example.data.local.CitationEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.ConversationGoalPreset
import com.example.data.local.MessageEntity
import com.example.data.local.MessageRole
import com.example.data.local.ModelEntity
import com.example.data.local.ResponseLengthPreset
import com.example.data.local.ToolDefinitionEntity
import com.example.engine.ai.ChatMessagePayload
import com.example.engine.ai.ToolSchemaPayload
import com.example.engine.retrieval.RetrievedPassage
import java.util.UUID
import kotlin.math.ceil

object TokenEstimator {
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return ceil(text.length / 3.6).toInt().coerceAtLeast(1)
    }

    fun estimateMessageTokens(role: String, content: String): Int {
        return 4 + estimateTokens(role) + estimateTokens(content)
    }

    fun estimateToolsTokens(tools: List<ToolSchemaPayload>): Int {
        if (tools.isEmpty()) return 0
        return tools.sumOf { 18 + estimateTokens(it.name) + estimateTokens(it.description) + estimateTokens(it.parametersSchemaJson) }
    }
}

data class ContextBudgetReport(
    val contextWindow: Int,
    val reservedOutputTokens: Int,
    val safetyMarginTokens: Int,
    val systemTokens: Int,
    val summaryTokens: Int,
    val historyTokens: Int,
    val ragTokens: Int,
    val toolsTokens: Int,
    val currentQueryTokens: Int,
    val totalUsedInputTokens: Int,
    val availableInputBudget: Int,
    val includedRagCount: Int,
    val droppedRagCount: Int,
    val includedHistoryCount: Int,
    val droppedHistoryCount: Int,
    val needsProgressiveSummary: Boolean
)

sealed interface ContextPlanResult {
    data class Ready(
        val messages: List<ChatMessagePayload>,
        val tools: List<ToolSchemaPayload>,
        val citations: List<CitationEntity>,
        val report: ContextBudgetReport
    ) : ContextPlanResult

    data class BudgetExceeded(
        val reason: String,
        val report: ContextBudgetReport
    ) : ContextPlanResult
}

/**
 * Computes token budgets before every AI request, allocating space across system instructions,
 * progressive conversation summaries, recent + on-demand history, retrieved RAG excerpts,
 * tool schemas, output reservation, and safety margin.
 */
class ContextBudgetPlanner {

    fun buildContextPlan(
        model: ModelEntity,
        conversation: ConversationEntity,
        fullHistory: List<MessageEntity>,
        userQuery: String,
        retrievedPassages: List<RetrievedPassage>,
        enabledTools: List<ToolDefinitionEntity>
    ): ContextPlanResult {
        val contextWindow = model.contextWindow.coerceAtLeast(512)
        val reservedOutput = model.maxOutputTokens.coerceIn(64, contextWindow)
        val safetyMargin = (contextWindow * 0.05).toInt().coerceIn(128, 1024)
        val availableInputBudget = (contextWindow - reservedOutput - safetyMargin)

        val toolPayloads = if (model.supportsTools) {
            enabledTools.filter { it.enabled }.map {
                ToolSchemaPayload(
                    name = it.name,
                    description = it.description,
                    parametersSchemaJson = it.parametersSchemaJson
                )
            }
        } else {
            emptyList()
        }
        val toolsTokens = TokenEstimator.estimateToolsTokens(toolPayloads)

        val systemInstructionText = buildSystemPrompt(conversation)
        val systemTokens = if (model.supportsSystemMessage) {
            TokenEstimator.estimateMessageTokens("system", systemInstructionText)
        } else {
            TokenEstimator.estimateTokens(systemInstructionText)
        }

        val queryTokens = TokenEstimator.estimateMessageTokens("user", userQuery)
        val fixedMinimum = systemTokens + toolsTokens + queryTokens

        if (availableInputBudget <= fixedMinimum) {
            val report = ContextBudgetReport(
                contextWindow = contextWindow,
                reservedOutputTokens = reservedOutput,
                safetyMarginTokens = safetyMargin,
                systemTokens = systemTokens,
                summaryTokens = 0,
                historyTokens = 0,
                ragTokens = 0,
                toolsTokens = toolsTokens,
                currentQueryTokens = queryTokens,
                totalUsedInputTokens = fixedMinimum,
                availableInputBudget = availableInputBudget.coerceAtLeast(0),
                includedRagCount = 0,
                droppedRagCount = retrievedPassages.size,
                includedHistoryCount = 0,
                droppedHistoryCount = fullHistory.size,
                needsProgressiveSummary = false
            )
            return ContextPlanResult.BudgetExceeded(
                reason = "O limite de contexto (${contextWindow} tokens) após reservar saída (${reservedOutput}) e margem (${safetyMargin}) não comporta a instrução e a pergunta (${fixedMinimum} tokens). Aumente o Context Window ou reduza Max Output Tokens nas configurações do modelo.",
                report = report
            )
        }

        var remaining = availableInputBudget - fixedMinimum

        // 1. Allocate RAG passages up to 55% of remaining input budget (minimum priority when sources exist)
        val ragBudgetCap = (remaining * 0.58).toInt().coerceAtLeast(300)
        var ragTokensUsed = 0
        val selectedPassages = mutableListOf<RetrievedPassage>()
        val formattedPassages = mutableListOf<String>()

        for ((idx, passage) in retrievedPassages.withIndex()) {
            val refNumber = idx + 1
            val section = passage.chunk.sectionTitle.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
            val block = "[$refNumber] Fonte: ${passage.sourceName} (p. ${passage.chunk.pageNumber}$section)\n${passage.chunk.content}"
            val blockTokens = TokenEstimator.estimateTokens(block) + 4
            if (ragTokensUsed + blockTokens <= ragBudgetCap && ragTokensUsed + blockTokens <= remaining) {
                selectedPassages.add(passage)
                formattedPassages.add(block)
                ragTokensUsed += blockTokens
            }
        }
        remaining -= ragTokensUsed

        // 2. Allocate running summary (if present) up to 25% of remaining
        val summaryText = conversation.runningSummary.trim()
        var summaryTokensUsed = 0
        var includedSummary: String? = null
        if (summaryText.isNotEmpty() && remaining > 100) {
            val summaryBlock = "Resumo acumulado da conversa anterior:\n$summaryText"
            val t = TokenEstimator.estimateMessageTokens("system", summaryBlock)
            if (t <= remaining) {
                includedSummary = summaryBlock
                summaryTokensUsed = t
                remaining -= t
            }
        }

        // 3. Allocate recent turns chronologically backwards + relevant older turns on demand
        val conversationalHistory = fullHistory.filter {
            it.role == MessageRole.USER.name || it.role == MessageRole.ASSISTANT.name
        }.filter { !it.isError }

        val selectedHistory = mutableListOf<MessageEntity>()
        var historyTokensUsed = 0

        for (msg in conversationalHistory.asReversed()) {
            val roleLower = msg.role.lowercase()
            val t = TokenEstimator.estimateMessageTokens(roleLower, msg.content)
            if (historyTokensUsed + t <= remaining) {
                selectedHistory.add(0, msg)
                historyTokensUsed += t
            } else {
                break
            }
        }

        // On-demand recovery of older relevant message if budget remains and some older messages were skipped
        val skippedCount = (conversationalHistory.size - selectedHistory.size).coerceAtLeast(0)
        if (skippedCount > 0 && remaining - historyTokensUsed > 160) {
            val queryWords = userQuery.lowercase().split(Regex("\\W+")).filter { it.length >= 4 }.toSet()
            if (queryWords.isNotEmpty()) {
                val olderCandidates = conversationalHistory.dropLast(selectedHistory.size)
                val bestOlder = olderCandidates.maxByOrNull { msg ->
                    val words = msg.content.lowercase().split(Regex("\\W+")).toSet()
                    words.intersect(queryWords).size
                }
                if (bestOlder != null) {
                    val overlap = bestOlder.content.lowercase().split(Regex("\\W+")).toSet().intersect(queryWords).size
                    val t = TokenEstimator.estimateMessageTokens(bestOlder.role.lowercase(), bestOlder.content)
                    if (overlap >= 2 && historyTokensUsed + t <= remaining) {
                        selectedHistory.add(0, bestOlder)
                        historyTokensUsed += t
                    }
                }
            }
        }

        // Assemble final OpenAI messages payload
        val finalMessages = mutableListOf<ChatMessagePayload>()
        if (model.supportsSystemMessage) {
            finalMessages.add(ChatMessagePayload(role = "system", content = systemInstructionText))
            if (includedSummary != null) {
                finalMessages.add(ChatMessagePayload(role = "system", content = includedSummary))
            }
        }

        for (hist in selectedHistory) {
            finalMessages.add(
                ChatMessagePayload(
                    role = hist.role.lowercase(),
                    content = hist.content
                )
            )
        }

        val currentTurnUserContent = buildString {
            if (!model.supportsSystemMessage) {
                append("[Instruções]\n").append(systemInstructionText).append("\n\n")
                if (includedSummary != null) {
                    append(includedSummary).append("\n\n")
                }
            }
            if (formattedPassages.isNotEmpty()) {
                append("Trechos recuperados das fontes selecionadas (cite usando [1], [2], etc.):\n")
                append(formattedPassages.joinToString("\n\n"))
                append("\n\nPergunta do usuário:\n")
            }
            append(userQuery)
        }

        finalMessages.add(ChatMessagePayload(role = "user", content = currentTurnUserContent))

        val citations = selectedPassages.map { p ->
            CitationEntity(
                id = UUID.randomUUID().toString(),
                messageId = "",
                sourceId = p.chunk.sourceId,
                sourceName = p.sourceName,
                chunkId = p.chunk.id,
                pageNumber = p.chunk.pageNumber,
                sectionTitle = p.chunk.sectionTitle,
                startOffset = p.chunk.startOffset,
                endOffset = p.chunk.endOffset,
                excerpt = p.chunk.content.take(280),
                relevanceScore = p.fusedScore
            )
        }

        val totalUsedInput = fixedMinimum + ragTokensUsed + summaryTokensUsed + historyTokensUsed
        val droppedHistory = (conversationalHistory.size - selectedHistory.size).coerceAtLeast(0)

        val report = ContextBudgetReport(
            contextWindow = contextWindow,
            reservedOutputTokens = reservedOutput,
            safetyMarginTokens = safetyMargin,
            systemTokens = systemTokens,
            summaryTokens = summaryTokensUsed,
            historyTokens = historyTokensUsed,
            ragTokens = ragTokensUsed,
            toolsTokens = toolsTokens,
            currentQueryTokens = queryTokens,
            totalUsedInputTokens = totalUsedInput,
            availableInputBudget = availableInputBudget,
            includedRagCount = selectedPassages.size,
            droppedRagCount = (retrievedPassages.size - selectedPassages.size).coerceAtLeast(0),
            includedHistoryCount = selectedHistory.size,
            droppedHistoryCount = droppedHistory,
            needsProgressiveSummary = droppedHistory >= 4
        )

        return ContextPlanResult.Ready(
            messages = finalMessages,
            tools = toolPayloads,
            citations = citations,
            report = report
        )
    }

    private fun buildSystemPrompt(conversation: ConversationEntity): String {
        val goalText = when (runCatching { ConversationGoalPreset.valueOf(conversation.systemRolePreset) }.getOrNull()) {
            ConversationGoalPreset.STUDY_GUIDE ->
                "Atue como um tutor didático: explique conceitos com clareza, estruture tópicos e ajude o usuário a fixar o conteúdo das fontes."
            ConversationGoalPreset.ANALYTICAL ->
                "Atue como um analista rigoroso: compare evidências, destaque lacunas, contradições e implicações práticas baseadas nas fontes."
            ConversationGoalPreset.CUSTOM ->
                conversation.customSystemPrompt.ifBlank {
                    "Responda com base nas fontes fornecidas, mantendo precisão e clareza."
                }
            else ->
                "Você é um assistente de conhecimento pessoal. Responda de forma clara, estruturada e fiel às fontes fornecidas."
        }

        val lengthDirective = when (runCatching { ResponseLengthPreset.valueOf(conversation.responseLength) }.getOrNull()) {
            ResponseLengthPreset.SHORT -> "Seja conciso e direto ao ponto."
            ResponseLengthPreset.LONG -> "Forneça uma explicação detalhada, aprofundada e bem estruturada."
            else -> "Mantenha equilíbrio entre clareza e profundidade."
        }

        return buildString {
            append(goalText).append(" ")
            append(lengthDirective).append("\n")
            append("Regras de fundamentação:\n")
            append("- Sempre que utilizar trechos das fontes recuperadas, cite as referências no texto como [1], [2], etc.\n")
            append("- Se não houver evidência suficiente nas fontes para afirmar algo, informe explicitamente ao usuário em vez de inventar fatos.")
        }
    }
}
