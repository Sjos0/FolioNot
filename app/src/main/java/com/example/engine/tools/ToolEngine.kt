package com.example.engine.tools

import com.example.data.local.ChunkAndEmbeddingDao
import com.example.data.local.NotebookDao
import com.example.data.local.SourceDao
import com.example.data.local.StudioArtifactEntity
import com.example.data.local.ToolDefinitionEntity
import com.example.data.local.ConversationAndMessageDao
import com.example.engine.ai.ToolCallRequest
import com.example.engine.retrieval.HybridRetriever
import org.json.JSONObject
import java.util.UUID

sealed interface ToolExecutionOutcome {
    data class Executed(val outputJson: String) : ToolExecutionOutcome
    data class RequiresUserApproval(
        val call: ToolCallRequest,
        val humanDescription: String
    ) : ToolExecutionOutcome
    data class Rejected(val reason: String) : ToolExecutionOutcome
}

class ToolEngine(
    private val sourceDao: SourceDao,
    private val chunkDao: ChunkAndEmbeddingDao,
    private val conversationDao: ConversationAndMessageDao,
    private val notebookDao: NotebookDao,
    private val retriever: HybridRetriever
) {

    companion object {
        const val TOOL_SEARCH_SOURCES = "search_notebook_sources"
        const val TOOL_READ_SOURCE_PAGE = "read_source_page"
        const val TOOL_LIST_SOURCES = "list_notebook_sources"
        const val TOOL_CREATE_NOTE = "create_notebook_note"

        fun builtInTools(): List<ToolDefinitionEntity> = listOf(
            ToolDefinitionEntity(
                id = "builtin_$TOOL_SEARCH_SOURCES",
                name = TOOL_SEARCH_SOURCES,
                description = "Busca lexicalmente trechos relevantes nas fontes do notebook atual.",
                parametersSchemaJson = """{"type":"object","properties":{"query":{"type":"string","description":"Termos de busca"},"limit":{"type":"integer","description":"Quantidade máxima de resultados (1-10)"}},"required":["query"]}""",
                requiresUserApproval = false,
                enabled = true,
                isBuiltIn = true
            ),
            ToolDefinitionEntity(
                id = "builtin_$TOOL_READ_SOURCE_PAGE",
                name = TOOL_READ_SOURCE_PAGE,
                description = "Lê o texto de uma página específica de uma fonte do notebook.",
                parametersSchemaJson = """{"type":"object","properties":{"source_name":{"type":"string","description":"Nome exato ou parcial da fonte"},"page":{"type":"integer","description":"Número da página (1+)"}},"required":["source_name","page"]}""",
                requiresUserApproval = false,
                enabled = true,
                isBuiltIn = true
            ),
            ToolDefinitionEntity(
                id = "builtin_$TOOL_LIST_SOURCES",
                name = TOOL_LIST_SOURCES,
                description = "Lista os nomes, tipos, páginas e status das fontes no notebook.",
                parametersSchemaJson = """{"type":"object","properties":{}}""",
                requiresUserApproval = false,
                enabled = true,
                isBuiltIn = true
            ),
            ToolDefinitionEntity(
                id = "builtin_$TOOL_CREATE_NOTE",
                name = TOOL_CREATE_NOTE,
                description = "Cria e salva uma nova nota estruturada no Estúdio do notebook.",
                parametersSchemaJson = """{"type":"object","properties":{"title":{"type":"string","description":"Título da nota"},"content":{"type":"string","description":"Conteúdo em Markdown"}},"required":["title","content"]}""",
                requiresUserApproval = true,
                enabled = true,
                isBuiltIn = true
            )
        )
    }

    suspend fun evaluateAndExecute(
        notebookId: String,
        call: ToolCallRequest,
        enabledDefinitions: List<ToolDefinitionEntity>,
        userApproved: Boolean
    ): ToolExecutionOutcome {
        val def = enabledDefinitions.firstOrNull { it.name == call.name && it.enabled }
            ?: return ToolExecutionOutcome.Rejected("Ferramenta '${call.name}' não registrada ou desabilitada.")

        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return ToolExecutionOutcome.Rejected("Argumentos JSON inválidos para '${call.name}'.")

        if (def.requiresUserApproval && !userApproved) {
            val summary = describeActionForUser(call.name, args)
            return ToolExecutionOutcome.RequiresUserApproval(call, summary)
        }

        return when (call.name) {
            TOOL_SEARCH_SOURCES -> executeSearchSources(notebookId, args)
            TOOL_READ_SOURCE_PAGE -> executeReadSourcePage(notebookId, args)
            TOOL_LIST_SOURCES -> executeListSources(notebookId)
            TOOL_CREATE_NOTE -> executeCreateNote(notebookId, args)
            else -> ToolExecutionOutcome.Rejected("Ferramenta desconhecida: ${call.name}")
        }
    }

    private fun describeActionForUser(name: String, args: JSONObject): String {
        return when (name) {
            TOOL_CREATE_NOTE -> {
                val title = args.optString("title", "Sem título").take(80)
                "Salvar nota no Estúdio: \"$title\""
            }
            else -> "Executar ferramenta: $name"
        }
    }

    private suspend fun executeSearchSources(notebookId: String, args: JSONObject): ToolExecutionOutcome {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) return ToolExecutionOutcome.Rejected("Parâmetro 'query' obrigatório.")
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val sources = sourceDao.getSourcesForNotebookOnce(notebookId)
        val chunks = chunkDao.getChunksForNotebookOnce(notebookId)
        val matches = retriever.searchLexicalOffline(query, sources, chunks, limit)

        val arr = org.json.JSONArray()
        matches.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("source", m.sourceName)
                    put("page", m.chunk.pageNumber)
                    put("section", m.chunk.sectionTitle)
                    put("score", m.lexicalScore)
                    put("excerpt", m.chunk.content)
                }
            )
        }
        val out = JSONObject().put("results", arr).put("count", matches.size)
        return ToolExecutionOutcome.Executed(out.toString())
    }

    private suspend fun executeReadSourcePage(notebookId: String, args: JSONObject): ToolExecutionOutcome {
        val sourceQuery = args.optString("source_name", "").trim()
        val page = args.optInt("page", 1).coerceAtLeast(1)
        if (sourceQuery.isBlank()) return ToolExecutionOutcome.Rejected("Parâmetro 'source_name' obrigatório.")

        val sources = sourceDao.getSourcesForNotebookOnce(notebookId)
        val source = sources.firstOrNull { it.name.equals(sourceQuery, ignoreCase = true) }
            ?: sources.firstOrNull { it.name.contains(sourceQuery, ignoreCase = true) }
            ?: return ToolExecutionOutcome.Rejected("Fonte '$sourceQuery' não encontrada no notebook.")

        val chunks = chunkDao.getChunksForSources(listOf(source.id)).filter { it.pageNumber == page }
        val text = if (chunks.isNotEmpty()) {
            chunks.joinToString("\n\n") { it.content }
        } else {
            "Nenhum trecho encontrado na página $page da fonte '${source.name}'."
        }
        val out = JSONObject()
            .put("source", source.name)
            .put("page", page)
            .put("content", text.take(4000))
        return ToolExecutionOutcome.Executed(out.toString())
    }

    private suspend fun executeListSources(notebookId: String): ToolExecutionOutcome {
        val sources = sourceDao.getSourcesForNotebookOnce(notebookId)
        val arr = org.json.JSONArray()
        sources.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("name", s.name)
                    put("type", s.sourceType)
                    put("pages", s.pageCount)
                    put("status", s.processingStatus)
                    put("selected_for_chat", s.isSelectedForChat)
                }
            )
        }
        return ToolExecutionOutcome.Executed(JSONObject().put("sources", arr).toString())
    }

    private suspend fun executeCreateNote(notebookId: String, args: JSONObject): ToolExecutionOutcome {
        val title = args.optString("title", "").trim()
        val content = args.optString("content", "").trim()
        if (title.isBlank() || content.isBlank()) {
            return ToolExecutionOutcome.Rejected("Parâmetros 'title' e 'content' são obrigatórios.")
        }
        val sources = sourceDao.getSourcesForNotebookOnce(notebookId)
        conversationDao.upsertStudioArtifact(
            StudioArtifactEntity(
                id = UUID.randomUUID().toString(),
                notebookId = notebookId,
                artifactType = "NOTE",
                title = title.take(120),
                content = content,
                sourceCount = sources.size
            )
        )
        notebookDao.getNotebookById(notebookId)?.let { nb ->
            notebookDao.upsertNotebook(nb.copy(updatedAt = System.currentTimeMillis()))
        }
        return ToolExecutionOutcome.Executed(
            JSONObject().put("status", "saved").put("title", title).toString()
        )
    }
}
