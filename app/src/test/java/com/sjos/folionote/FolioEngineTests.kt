package com.sjos.folionote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.ConversationEntity
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.EmbeddingEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.data.local.VectorCodec
import com.sjos.folionote.engine.ai.ChatStreamEvent
import com.sjos.folionote.engine.ai.OpenAiCompatibleClient
import com.sjos.folionote.engine.ai.ToolCallRequest
import com.sjos.folionote.engine.context.ContextBudgetPlanner
import com.sjos.folionote.engine.context.ContextPlanResult
import com.sjos.folionote.engine.document.DocumentExtractor
import com.sjos.folionote.engine.document.ExtractedPage
import com.sjos.folionote.engine.indexing.StructuralChunker
import com.sjos.folionote.engine.retrieval.HybridRetriever
import com.sjos.folionote.engine.tools.ToolEngine
import com.sjos.folionote.engine.tools.ToolExecutionOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    fun `context budget planner enforces contextWindow output reservation and recovers older turns`() {
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

        // Tighter roomy window so older messages get skipped chronologically, then relevant ones recovered
        val constrainedModel = tinyModel.copy(
            contextWindow = 1200,
            maxOutputTokens = 128,
            supportsSystemMessage = false
        )
        val history = listOf(
            MessageEntity("old_rel_1", "conv1", "USER", "Qual foi a cláusula de indenização tributária definida no contrato?", orderIndex = 1),
            MessageEntity("old_rel_2", "conv1", "ASSISTANT", "A indenização tributária prevê teto de dez por cento.", orderIndex = 2)
        ) + (3..12).map { i ->
            MessageEntity(
                "msg_$i",
                "conv1",
                if (i % 2 == 1) "USER" else "ASSISTANT",
                "Mensagem intermediária genérica número $i sem termos fiscais. ".repeat(16),
                orderIndex = i
            )
        }

        val ready = planner.buildContextPlan(
            model = constrainedModel,
            conversation = conv,
            fullHistory = history,
            userQuery = "Compare a indenização tributária anterior com o cenário atual.",
            retrievedPassages = emptyList(),
            enabledTools = emptyList()
        )
        assertTrue(ready is ContextPlanResult.Ready)
        val plan = ready as ContextPlanResult.Ready
        assertTrue(plan.report.totalUsedInputTokens <= plan.report.availableInputBudget)
        assertTrue(plan.messages.none { it.role == "system" })
        assertTrue(plan.messages.last().content!!.contains("[Instruções]"))
        val joinedHistory = plan.messages.dropLast(1).joinToString("\n") { it.content.orEmpty() }
        assertTrue(joinedHistory.contains("indenização tributária"))
    }

    @Test
    fun `document extractor reads txt formfeed pages and docx headings tables and page breaks`() = runTest {
        val extractor = DocumentExtractor()
        val tempDir = java.nio.file.Files.createTempDirectory("folionote_doc_test").toFile()
        try {
            val txtFile = File(tempDir, "notes.txt")
            txtFile.writeText("Primeira página\u000CSegunda página")
            val txtExtracted = extractor.extract(txtFile, SourceType.TXT)
            assertFalse(txtExtracted.requiresOcr)
            assertEquals(2, txtExtracted.pages.size)
            assertEquals("Primeira página", txtExtracted.pages[0].text)
            assertEquals("Segunda página", txtExtracted.pages[1].text)

            val docxFile = File(tempDir, "contrato.docx")
            val documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                      <w:r><w:t>Título Principal</w:t></w:r>
                    </w:p>
                    <w:tbl>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Valor</w:t></w:r></w:p></w:tc>
                      </w:tr>
                    </w:tbl>
                    <w:p><w:r><w:br w:type="page"/></w:r></w:p>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                      <w:r><w:t>Capítulo Dois</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
            """.trimIndent()

            ZipOutputStream(FileOutputStream(docxFile)).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val docxExtracted = extractor.extract(docxFile, SourceType.DOCX)
            assertFalse(docxExtracted.requiresOcr)
            assertEquals(2, docxExtracted.pages.size)
            assertTrue(docxExtracted.pages[0].text.contains("# Título Principal"))
            assertTrue(docxExtracted.pages[0].text.contains("| Item | Valor |"))
            assertTrue(docxExtracted.pages[1].text.contains("## Capítulo Dois"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `tool engine validates args and gates create_notebook_note behind user approval`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.notebookDao().upsertNotebook(NotebookEntity("nb1", "Caderno"))
            db.sourceDao().upsertSource(
                SourceEntity("s1", "nb1", "Resumo.md", "text/markdown", "MARKDOWN", "/tmp/r.md", 50, "h1")
            )
            db.chunkAndEmbeddingDao().insertChunks(
                listOf(DocumentChunkEntity("c1", "s1", "nb1", 0, "Topo", 1, 0, 30, "Conceito de arquitetura limpa.", 6))
            )

            val engine = ToolEngine(
                sourceDao = db.sourceDao(),
                chunkDao = db.chunkAndEmbeddingDao(),
                conversationDao = db.conversationAndMessageDao(),
                notebookDao = db.notebookDao(),
                retriever = HybridRetriever()
            )
            val tools = ToolEngine.builtInTools()

            val badArgs = engine.evaluateAndExecute(
                notebookId = "nb1",
                call = ToolCallRequest("c0", ToolEngine.TOOL_SEARCH_SOURCES, """{"query":""}"""),
                enabledDefinitions = tools,
                userApproved = false
            )
            assertTrue(badArgs is ToolExecutionOutcome.Rejected)

            val searchOk = engine.evaluateAndExecute(
                notebookId = "nb1",
                call = ToolCallRequest("c1", ToolEngine.TOOL_SEARCH_SOURCES, """{"query":"arquitetura"}"""),
                enabledDefinitions = tools,
                userApproved = false
            )
            assertTrue(searchOk is ToolExecutionOutcome.Executed)
            assertTrue((searchOk as ToolExecutionOutcome.Executed).outputJson.contains("arquitetura limpa"))

            val needsApproval = engine.evaluateAndExecute(
                notebookId = "nb1",
                call = ToolCallRequest("c2", ToolEngine.TOOL_CREATE_NOTE, """{"title":"Síntese","content":"Pontos-chave"}"""),
                enabledDefinitions = tools,
                userApproved = false
            )
            assertTrue(needsApproval is ToolExecutionOutcome.RequiresUserApproval)

            val executed = engine.evaluateAndExecute(
                notebookId = "nb1",
                call = ToolCallRequest("c2", ToolEngine.TOOL_CREATE_NOTE, """{"title":"Síntese","content":"Pontos-chave"}"""),
                enabledDefinitions = tools,
                userApproved = true
            )
            assertTrue(executed is ToolExecutionOutcome.Executed)
            val artifacts = db.conversationAndMessageDao().observeStudioArtifacts("nb1").first()
            assertEquals(1, artifacts.size)
            assertEquals("Síntese", artifacts.first().title)
        } finally {
            db.close()
        }
    }

    @Test
    fun `openai client parses flexible models responses and SSE tool_calls stream`() {
        val client = OpenAiCompatibleClient(KeystoreCryptoManager())
        val models = client.parseModelsResponse(
            """{"data":[{"id":"llama-3.3-70b","context_length":131072},{"id":"llama-3.3-70b"},{"id":"qwen-2.5","inputTokenLimit":32768}]}"""
        )
        assertEquals(2, models.size)
        assertEquals(131072, models.first().contextLengthHint)
        assertEquals(32768, models[1].contextLengthHint)

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
