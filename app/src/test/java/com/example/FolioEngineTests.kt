package com.example

import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.ConversationEntity
import com.example.data.local.DocumentChunkEntity
import com.example.data.local.EmbeddingEntity
import com.example.data.local.MessageEntity
import com.example.data.local.ModelEntity
import com.example.data.local.SourceEntity
import com.example.data.local.VectorCodec
import com.example.engine.ai.ChatStreamEvent
import com.example.engine.ai.OpenAiCompatibleClient
import com.example.engine.context.ContextBudgetPlanner
import com.example.engine.context.ContextPlanResult
import com.example.engine.document.ExtractedPage
import com.example.engine.indexing.StructuralChunker
import com.example.engine.retrieval.HybridRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolioEngineTests {

    @Test
    fun `structural chunker preserves headings and page numbers`() {
        val chunker = StructuralChunker()
        val pages = listOf(
            ExtractedPage(1, "# Introdução\nEste projeto trata de arquitetura local-first.\n\n- Item A\n- Item B"),
            ExtractedPage(2, "## Detalhes Técnicos\nO sistema utiliza busca híbrida lexical e semântica.")
        )
        val chunks = chunker.chunkPages(
            sourceId = "s1",
            notebookId = "nb1",
            pages = pages,
            maxChunkChars = 300,
            overlapChars = 40
        )

        assertTrue(chunks.size >= 2)
        assertEquals(1, chunks.first().pageNumber)
        assertEquals("Introdução", chunks.first().sectionTitle)
        assertEquals(2, chunks.last().pageNumber)
        assertEquals("Detalhes Técnicos", chunks.last().sectionTitle)
    }

    @Test
    fun `hybrid retriever filters by selected sources and ranks lexical plus semantic`() {
        val retriever = HybridRetriever()
        val sources = listOf(
            SourceEntity("s1", "nb1", "Contrato.pdf", "application/pdf", "PDF", "", 100, "h1", isSelectedForChat = true),
            SourceEntity("s2", "nb1", "Rascunho.txt", "text/plain", "TXT", "", 50, "h2", isSelectedForChat = false)
        )
        val chunks = listOf(
            DocumentChunkEntity("c1", "s1", "nb1", 0, "Cláusula", 1, 0, 50, "Prazo de pagamento é de trinta dias úteis.", 12),
            DocumentChunkEntity("c2", "s2", "nb1", 0, "Nota", 1, 0, 40, "Prazo de pagamento ignorado pois fonte desmarcada.", 12)
        )
        val emb = listOf(
            EmbeddingEntity(
                id = "e1",
                chunkId = "c1",
                notebookId = "nb1",
                sourceId = "s1",
                embeddingProviderId = "p1",
                embeddingModelId = "text-embedding",
                dimensions = 3,
                vectorBlob = VectorCodec.floatArrayToBytes(floatArrayOf(0.9f, 0.1f, 0.0f))
            )
        )

        val hits = retriever.retrieve(
            query = "prazo de pagamento",
            sources = sources,
            chunks = chunks,
            embeddings = emb,
            queryEmbedding = floatArrayOf(1.0f, 0.0f, 0.0f),
            topK = 5
        )

        assertEquals(1, hits.size)
        assertEquals("c1", hits.first().chunk.id)
        assertTrue(hits.first().lexicalScore > 0f)
        assertTrue(hits.first().semanticScore > 0.9f)
    }

    @Test
    fun `context budget planner enforces model contextWindow and output reservation`() {
        val planner = ContextBudgetPlanner()
        val tinyModel = ModelEntity(
            id = "m1",
            providerId = "p1",
            modelId = "tiny",
            displayName = "Tiny",
            contextWindow = 512,
            maxOutputTokens = 450
        )
        val conv = ConversationEntity("conv1", "nb1", "Teste")

        val tooSmall = planner.buildContextPlan(
            model = tinyModel,
            conversation = conv,
            fullHistory = emptyList(),
            userQuery = "Explique todo o documento em detalhes.",
            retrievedPassages = emptyList(),
            enabledTools = emptyList()
        )
        assertTrue(tooSmall is ContextPlanResult.BudgetExceeded)

        val roomyModel = tinyModel.copy(contextWindow = 4096, maxOutputTokens = 1024)
        val history = (1..10).map { i ->
            MessageEntity("msg$i", "conv1", if (i % 2 == 1) "USER" else "ASSISTANT", "Mensagem histórica $i com contexto longo ".repeat(8), orderIndex = i)
        }
        val ready = planner.buildContextPlan(
            model = roomyModel,
            conversation = conv,
            fullHistory = history,
            userQuery = "Qual é o resumo?",
            retrievedPassages = emptyList(),
            enabledTools = emptyList()
        )
        assertTrue(ready is ContextPlanResult.Ready)
        val plan = ready as ContextPlanResult.Ready
        assertTrue(plan.report.totalUsedInputTokens <= plan.report.availableInputBudget)
    }

    @Test
    fun `openai client parses flexible models responses and SSE tool_calls stream`() {
        val client = OpenAiCompatibleClient(KeystoreCryptoManager())
        val models = client.parseModelsResponse(
            """{"data":[{"id":"llama-3.3-70b","context_length":131072},{"id":"llama-3.3-70b"},{"id":"qwen-2.5"}]}"""
        )
        assertEquals(2, models.size)
        assertEquals(131072, models.first().contextLengthHint)

        val sse = """
            data: {"choices":[{"delta":{"content":"Olá "}}]}

            data: {"choices":[{"delta":{"content":"mundo!"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"search_notebook_sources","arguments":"{\"query\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"teste\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()

        val events = mutableListOf<ChatStreamEvent>()
        client.parseSseStream(BufferedReader(StringReader(sse))) { events.add(it) }

        val text = events.filterIsInstance<ChatStreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Olá mundo!", text)
        val tools = events.filterIsInstance<ChatStreamEvent.ToolCallsReady>().flatMap { it.calls }
        assertEquals(1, tools.size)
        assertEquals("search_notebook_sources", tools.first().name)
        assertEquals("""{"query":"teste"}""", tools.first().argumentsJson)
    }
}
