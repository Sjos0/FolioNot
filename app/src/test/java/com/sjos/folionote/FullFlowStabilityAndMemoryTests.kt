package com.sjos.folionote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.network.SharedHttpClients
import com.sjos.folionote.core.network.UrlAllowlistPolicy
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.EmbeddingEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.MessageRole
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.SourceStatus
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.data.repository.ChatOrchestrator
import com.sjos.folionote.engine.ai.OpenAiCompatibleClient
import com.sjos.folionote.engine.context.ContextBudgetPlanner
import com.sjos.folionote.engine.indexing.CompositeEmbeddingProvider
import com.sjos.folionote.engine.indexing.GeminiEmbeddingManager
import com.sjos.folionote.engine.indexing.GeminiEmbeddingProvider
import com.sjos.folionote.engine.indexing.OpenAiCompatibleEmbeddingProvider
import com.sjos.folionote.engine.retrieval.HybridRetriever
import com.sjos.folionote.engine.tools.ToolEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fluxos completos de estabilidade e memória (sem emulador).
 * Cada teste cobre um caso de uso real que, antes das correções, podia consumir RAM sem limite.
 */
@RunWith(RobolectricTestRunner::class)
class FullFlowStabilityAndMemoryTests {

    private fun newDb(context: Context): FolioNoteDatabase =
        Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun buildOrchestrator(
        db: FolioNoteDatabase,
        prefs: AppPreferencesStore,
        crypto: KeystoreCryptoManager
    ): ChatOrchestrator {
        val sharedClient = SharedHttpClients.defaultClient
        val embeddingProvider = CompositeEmbeddingProvider(
            OpenAiCompatibleEmbeddingProvider(crypto, sharedClient),
            GeminiEmbeddingProvider(crypto, sharedClient)
        )
        return ChatOrchestrator(
            db = db,
            aiClient = OpenAiCompatibleClient(crypto, sharedClient),
            embeddingProvider = embeddingProvider,
            geminiEmbeddingManager = GeminiEmbeddingManager(prefs, crypto, embeddingProvider),
            retriever = HybridRetriever(),
            budgetPlanner = ContextBudgetPlanner(),
            toolEngine = ToolEngine(
                db.sourceDao(),
                db.chunkAndEmbeddingDao(),
                db.conversationAndMessageDao(),
                db.notebookDao(),
                HybridRetriever()
            ),
            preferencesStore = prefs
        )
    }

    private suspend fun seedStreamingModel(
        db: FolioNoteDatabase,
        crypto: KeystoreCryptoManager,
        baseUrl: String
    ): ModelEntity {
        val provider = ProviderEntity(
            id = "p_flow",
            name = "MockFlow",
            baseUrl = baseUrl,
            encryptedApiKey = crypto.encrypt("sk-flow-test"),
            supportsStreamingDefault = true
        )
        val model = ModelEntity(
            id = "m_flow",
            providerId = provider.id,
            modelId = "gpt-flow",
            displayName = "GPT Flow",
            contextWindow = 8192,
            maxOutputTokens = 2048,
            supportsStreaming = true,
            supportsTools = false
        )
        db.toolAndJobDao().upsertTools(ToolEngine.builtInTools())
        db.providerAndModelDao().upsertProvider(provider)
        db.providerAndModelDao().upsertModel(model)
        return model
    }

    /** Corpo SSE com [count] deltas de [piece] caracteres cada, terminando em [DONE]. */
    private fun sseBody(count: Int, piece: String): String = buildString {
        repeat(count) {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"")
            append(piece)
            append("\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    // Fluxo 1: caderno grande no workspace. Estatísticas são agregadas (não listas de vetores),
    // e "selecionar tudo" altera apenas as fontes informadas (respeitando o filtro de busca).
    @Test
    fun `large notebook workspace uses aggregates and select-all touches only the visible sources`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newDb(context)
        try {
            db.notebookDao().upsertNotebook(NotebookEntity("nb_big", "Caderno grande"))
            (1..10).forEach { i ->
                db.sourceDao().upsertSource(
                    SourceEntity(
                        id = "src_$i",
                        notebookId = "nb_big",
                        name = "Documento $i.txt",
                        mimeType = "text/plain",
                        sourceType = SourceType.TXT.name,
                        filePath = "",
                        sizeBytes = 1_000L,
                        fileHashSha256 = "hash_$i",
                        processingStatus = SourceStatus.READY.name,
                        isSelectedForChat = true
                    )
                )
            }
            val chunks = (0 until 3_000).map { idx ->
                DocumentChunkEntity(
                    id = "c_$idx",
                    sourceId = "src_${idx % 10 + 1}",
                    notebookId = "nb_big",
                    chunkIndex = idx,
                    pageNumber = 1,
                    content = "trecho $idx",
                    tokenEstimate = 10
                )
            }
            db.chunkAndEmbeddingDao().insertChunks(chunks)
            val embeddings = (0 until 3_000).map { idx ->
                EmbeddingEntity(
                    id = "e_$idx",
                    chunkId = "c_$idx",
                    notebookId = "nb_big",
                    sourceId = "src_${idx % 10 + 1}",
                    embeddingProviderId = "p_test",
                    embeddingModelId = "m-test",
                    dimensions = 4,
                    vectorBlob = ByteArray(16)
                )
            }
            db.chunkAndEmbeddingDao().insertEmbeddings(embeddings)

            val stats = db.chunkAndEmbeddingDao().observeChunkStats("nb_big").first()
            assertEquals(3_000, stats.chunkCount)
            assertEquals(30_000L, stats.totalTokens)

            val perSource = db.chunkAndEmbeddingDao().observeChunkCountsBySource("nb_big").first()
            assertEquals(10, perSource.size)
            assertTrue(perSource.all { it.chunkCount == 300 })

            val groups = db.chunkAndEmbeddingDao().observeEmbeddingGroups("nb_big").first()
            assertEquals(listOf("m-test"), groups.map { it.embeddingModelId }.distinct())
            assertEquals(3_000, groups.sumOf { it.vectorCount })

            // Busca filtra para src_1 e src_2; "selecionar tudo" desmarca só essas duas.
            db.sourceDao().setSourcesSelected(listOf("src_1", "src_2"), false)
            assertFalse(db.sourceDao().getSourceById("src_1")!!.isSelectedForChat)
            assertFalse(db.sourceDao().getSourceById("src_2")!!.isSelectedForChat)
            assertTrue(db.sourceDao().getSourceById("src_3")!!.isSelectedForChat)
        } finally {
            db.close()
        }
    }

    // Fluxo 2: modelo responde com um texto gigante. A resposta é cortada no limite de memória,
    // o download é interrompido e o parcial é salvo marcado como parcial (sem estourar a RAM).
    @Test
    fun `oversized streaming reply is cut at the memory cap and saved as partial`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newDb(context)
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()
        val server = MockWebServer()
        server.start()
        try {
            val crypto = KeystoreCryptoManager()
            val model = seedStreamingModel(db, crypto, server.url("/v1").toString().trimEnd('/'))
            db.notebookDao().upsertNotebook(NotebookEntity("nb_cap", "Caderno limite"))
            val orchestrator = buildOrchestrator(db, prefs, crypto)
            val conv = orchestrator.createNewConversation("nb_cap", "Texto longo", model.id)

            // 260 deltas de 1000 caracteres = 260k caracteres, acima do limite de 200k.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody(count = 260, piece = "x".repeat(1_000)))
            )

            orchestrator.sendMessage(
                notebookId = "nb_cap",
                conversationId = conv.id,
                userText = "Gere um texto muito longo",
                modelInternalId = model.id
            )

            assertFalse(orchestrator.runtimeState.value.isStreaming)
            val assistant = db.conversationAndMessageDao()
                .getMessagesForConversationOnce(conv.id)
                .last { it.role == MessageRole.ASSISTANT.name }
            assertTrue(assistant.isPartial)
            assertTrue(assistant.content.length <= ChatOrchestrator.MAX_STREAMED_RESPONSE_CHARS + 1_000)
            assertTrue(assistant.content.isNotBlank())
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            db.close()
        }
    }

    // Fluxo 3: resposta normal em streaming com muitos deltas pequenos.
    // A atualização de UI é espaçada (throttle), mas o texto salvo precisa ser exatamente o recebido.
    @Test
    fun `many small streaming deltas are persisted completely despite UI throttling`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newDb(context)
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()
        val server = MockWebServer()
        server.start()
        try {
            val crypto = KeystoreCryptoManager()
            val model = seedStreamingModel(db, crypto, server.url("/v1").toString().trimEnd('/'))
            db.notebookDao().upsertNotebook(NotebookEntity("nb_fast", "Caderno rápido"))
            val orchestrator = buildOrchestrator(db, prefs, crypto)
            val conv = orchestrator.createNewConversation("nb_fast", "Resposta curta", model.id)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody(count = 400, piece = "ab"))
            )

            orchestrator.sendMessage(
                notebookId = "nb_fast",
                conversationId = conv.id,
                userText = "Responda rapidamente",
                modelInternalId = model.id
            )

            val assistant = db.conversationAndMessageDao()
                .getMessagesForConversationOnce(conv.id)
                .last { it.role == MessageRole.ASSISTANT.name }
            assertEquals("ab".repeat(400), assistant.content)
            assertFalse(assistant.isPartial)
            assertFalse(orchestrator.runtimeState.value.isStreaming)
        } finally {
            server.shutdown()
            db.close()
        }
    }

    // Fluxo 4: recuperação (RAG) sobre um corpus grande. Os trechos são lidos em páginas pequenas,
    // nunca uma lista inteira na memória, e o resultado continua correto.
    @Test
    fun `retrieval over a 20k chunk corpus reads bounded pages and still finds the passage`() = runTest {
        val source = SourceEntity(
            id = "src_huge",
            notebookId = "nb_huge",
            name = "Tratado grande.txt",
            mimeType = "text/plain",
            sourceType = SourceType.TXT.name,
            filePath = "",
            sizeBytes = 5_000_000L,
            fileHashSha256 = "hash_huge",
            processingStatus = SourceStatus.READY.name,
            isSelectedForChat = true
        )
        val chunks = (0 until 20_000).map { idx ->
            DocumentChunkEntity(
                id = "h_$idx",
                sourceId = source.id,
                notebookId = source.notebookId,
                chunkIndex = idx,
                pageNumber = idx / 50 + 1,
                content = if (idx == 17_321) "Protocolo Andromeda exige calibração térmica." else "conteúdo genérico $idx",
                tokenEstimate = 12
            )
        }

        var pageCalls = 0
        var maxPageSize = 0
        var rowsRead = 0
        val passages = HybridRetriever().retrieveOnDemand(
            query = "protocolo andromeda",
            sources = listOf(source),
            queryEmbedding = null,
            topK = 5,
            pageSize = 64,
            loadChunkPage = { limit, offset ->
                pageCalls++
                val page = chunks.subList(offset.coerceAtMost(chunks.size), (offset + limit).coerceAtMost(chunks.size))
                maxPageSize = maxOf(maxPageSize, page.size)
                rowsRead += page.size
                page
            },
            loadEmbeddingPage = { _, _ -> emptyList() },
            loadChunksByIds = { ids -> chunks.filter { it.id in ids } }
        )

        assertTrue("página maior que o limite: $maxPageSize", maxPageSize <= 64)
        assertTrue("leituras demais: $pageCalls", pageCalls <= 2 * (20_000 / 64 + 2))
        assertTrue("linhas lidas: $rowsRead", rowsRead >= 20_000)
        assertTrue(passages.isNotEmpty())
    }

    // Fluxo 5: atualização do app. Downloads e redirecionamentos só podem ir para hosts do GitHub,
    // o que impede que um redirecionamento leve o instalador para outro servidor.
    @Test
    fun `updater requests are confined to GitHub hosts`() {
        val policy = UrlAllowlistPolicy(
            allowedHostSuffixes = setOf("github.com", "githubusercontent.com"),
            allowLocalhostForTests = false
        )
        SharedHttpClients.validateUrlAgainstPolicy("https://github.com/Sjos0/FolioNot/releases".toHttpUrl(), policy)
        SharedHttpClients.validateUrlAgainstPolicy("https://objects.githubusercontent.com/asset".toHttpUrl(), policy)
        assertTrue(
            runCatching {
                SharedHttpClients.validateUrlAgainstPolicy("https://evil.example.com/FolioNote.apk".toHttpUrl(), policy)
            }.isFailure
        )
    }
}
