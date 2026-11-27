package com.sjos.folionote.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.network.SharedHttpClients
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.EmbeddingEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.MessageEntity
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Casos de medição de memória (heap da JVM sob Robolectric).
 *
 * Cada método `case_*` é um caso do workflow `memory-profile.yml` (um job de matriz por caso).
 * O método grava `build/memory-reports/<caso>.json` com pico e retenção do heap.
 * Os orçamentos ficam em `memory-budgets.json` e são checados por `scripts/memory_budget_check.py`.
 *
 * Limitação: é o heap da JVM, não a PSS do Android. Serve para detectar regressões de alocação
 * (listas inteiras, cópias por token, clientes duplicados); a RAM real do aparelho exige teste em dispositivo.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryCaseTests {

    private fun newDb(context: Context): FolioNoteDatabase =
        Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java).allowMainThreadQueries().build()

    private fun newOrchestrator(db: FolioNoteDatabase, prefs: AppPreferencesStore, crypto: KeystoreCryptoManager): ChatOrchestrator {
        val client = SharedHttpClients.defaultClient
        val embeddingProvider = CompositeEmbeddingProvider(
            OpenAiCompatibleEmbeddingProvider(crypto, client),
            GeminiEmbeddingProvider(crypto, client)
        )
        return ChatOrchestrator(
            db = db,
            aiClient = OpenAiCompatibleClient(crypto, client),
            embeddingProvider = embeddingProvider,
            geminiEmbeddingManager = GeminiEmbeddingManager(prefs, crypto, embeddingProvider),
            retriever = HybridRetriever(),
            budgetPlanner = ContextBudgetPlanner(),
            toolEngine = ToolEngine(db.sourceDao(), db.chunkAndEmbeddingDao(), db.conversationAndMessageDao(), db.notebookDao(), HybridRetriever()),
            preferencesStore = prefs
        )
    }

    private suspend fun seedModel(db: FolioNoteDatabase, crypto: KeystoreCryptoManager, baseUrl: String, streaming: Boolean): ModelEntity {
        val provider = ProviderEntity(
            id = "p_mem_case", name = "MemCase", baseUrl = baseUrl,
            encryptedApiKey = crypto.encrypt("sk-mem-case"), supportsStreamingDefault = streaming
        )
        val model = ModelEntity(
            id = "m_mem_case", providerId = provider.id, modelId = "gpt-mem-case", displayName = "GPT Mem",
            contextWindow = 8192, maxOutputTokens = 2048, supportsStreaming = streaming, supportsTools = false
        )
        db.toolAndJobDao().upsertTools(ToolEngine.builtInTools())
        db.providerAndModelDao().upsertProvider(provider)
        db.providerAndModelDao().upsertModel(model)
        return model
    }

    private fun sse(count: Int, piece: String): String = buildString {
        repeat(count) {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"").append(piece).append("\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private fun source(notebookId: String, id: String, selected: Boolean = true) = SourceEntity(
        id = id, notebookId = notebookId, name = "$id.txt", mimeType = "text/plain",
        sourceType = SourceType.TXT.name, filePath = "", sizeBytes = 1_000L,
        fileHashSha256 = "hash_$id", processingStatus = SourceStatus.READY.name, isSelectedForChat = selected
    )

    @Test
    fun case_idle_startup() = runTest {
        val probe = HeapProbe("case_idle_startup")
        probe.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newDb(context)
        try {
            AppPreferencesStore(context).settingsFlow.first()
            newOrchestrator(db, AppPreferencesStore(context), KeystoreCryptoManager())
        } finally {
            db.close()
        }
        probe.finish(extra = mapOf("workload" to "banco e preferências vazios"))
    }

    @Test
    fun case_workspace_10k_chunks() = runTest {
        val probe = HeapProbe("case_workspace_10k_chunks")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newDb(context)
        probe.start()
        try {
            db.notebookDao().upsertNotebook(NotebookEntity("nb_m10k", "Caderno 10k"))
            (1..10).forEach { db.sourceDao().upsertSource(source("nb_m10k", "s_m$it")) }
            (0 until 10_000).chunked(1_000).forEach { batch ->
                db.chunkAndEmbeddingDao().insertChunks(batch.map { idx ->
                    DocumentChunkEntity(
                        id = "mc_$idx", sourceId = "s_m${idx % 10 + 1}", notebookId = "nb_m10k",
                        chunkIndex = idx, pageNumber = 1, content = "trecho $idx", tokenEstimate = 12
                    )
                })
            }
            (0 until 10_000).chunked(1_000).forEach { batch ->
                db.chunkAndEmbeddingDao().insertEmbeddings(batch.map { idx ->
                    EmbeddingEntity(
                        id = "me_$idx", chunkId = "mc_$idx", notebookId = "nb_m10k",
                        sourceId = "s_m${idx % 10 + 1}", embeddingProviderId = "p", embeddingModelId = "m-case",
                        dimensions = 64, vectorBlob = ByteArray(64 * 4)
                    )
                })
            }
            // Caminho do workspace: somente agregados, nunca listas de vetores.
            db.chunkAndEmbeddingDao().observeChunkStats("nb_m10k").first()
            db.chunkAndEmbeddingDao().observeChunkCountsBySource("nb_m10k").first()
            db.chunkAndEmbeddingDao().observeEmbeddingGroups("nb_m10k").first()
        } finally {
            db.close()
        }
        probe.finish(extra = mapOf("chunks" to 10_000, "embeddingDims" to 64))
    }

    @Test
    fun case_rag_20k_chunks_bm25() = runTest {
        val probe = HeapProbe("case_rag_20k_chunks_bm25")
        probe.start()
        val source = source("nb_rag", "s_rag")
        val chunks = (0 until 20_000).map { idx ->
            DocumentChunkEntity(
                id = "rc_$idx", sourceId = source.id, notebookId = "nb_rag", chunkIndex = idx, pageNumber = idx / 50 + 1,
                content = if (idx == 17_321) "Protocolo Andromeda exige calibração térmica." else "conteúdo genérico $idx",
                tokenEstimate = 12
            )
        }
        val passages = HybridRetriever().retrieveOnDemand(
            query = "protocolo andromeda", sources = listOf(source), queryEmbedding = null, topK = 5, pageSize = 64,
            loadChunkPage = { limit, offset ->
                chunks.subList(offset.coerceAtMost(chunks.size), (offset + limit).coerceAtMost(chunks.size))
            },
            loadEmbeddingPage = { _, _ -> emptyList() },
            loadChunksByIds = { ids -> chunks.filter { it.id in ids } }
        )
        check(passages.isNotEmpty()) { "recuperação não encontrou o trecho esperado" }
        probe.finish(extra = mapOf("chunks" to 20_000, "pageSize" to 64))
    }

    @Test
    fun case_stream_reply_capped_at_limit() = runTest {
        val probe = HeapProbe("case_stream_reply_capped_at_limit")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newDb(context)
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()
        val server = MockWebServer()
        server.start()
        try {
            val crypto = KeystoreCryptoManager()
            val model = seedModel(db, crypto, server.url("/v1").toString().trimEnd('/'), streaming = true)
            db.notebookDao().upsertNotebook(NotebookEntity("nb_cap_case", "Cap"))
            val orchestrator = newOrchestrator(db, prefs, crypto)
            val conv = orchestrator.createNewConversation("nb_cap_case", "Texto longo", model.id)
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody(sse(count = 260, piece = "x".repeat(1_000))))
            probe.start()
            orchestrator.sendMessage("nb_cap_case", conv.id, "Gere um texto muito longo", model.id)
        } finally {
            server.shutdown()
            db.close()
        }
        probe.finish(extra = mapOf("replyCharsLimit" to ChatOrchestrator.MAX_STREAMED_RESPONSE_CHARS))
    }

    @Test
    fun case_stream_3k_small_deltas() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newDb(context)
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()
        val server = MockWebServer()
        server.start()
        val probe = HeapProbe("case_stream_3k_small_deltas")
        try {
            val crypto = KeystoreCryptoManager()
            val model = seedModel(db, crypto, server.url("/v1").toString().trimEnd('/'), streaming = true)
            db.notebookDao().upsertNotebook(NotebookEntity("nb_fast_case", "Rápido"))
            val orchestrator = newOrchestrator(db, prefs, crypto)
            val conv = orchestrator.createNewConversation("nb_fast_case", "Muitos deltas", model.id)
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody(sse(count = 3_000, piece = "ab")))
            probe.start()
            orchestrator.sendMessage("nb_fast_case", conv.id, "Responda em muitos pedaços", model.id)
        } finally {
            server.shutdown()
            db.close()
        }
        probe.finish(extra = mapOf("deltas" to 3_000))
    }

    @Test
    fun case_history_2000_messages_bounded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newDb(context)
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()
        val server = MockWebServer()
        server.start()
        val probe = HeapProbe("case_history_2000_messages_bounded")
        try {
            val crypto = KeystoreCryptoManager()
            val model = seedModel(db, crypto, server.url("/v1").toString().trimEnd('/'), streaming = false)
            db.notebookDao().upsertNotebook(NotebookEntity("nb_hist_case", "Histórico"))
            val orchestrator = newOrchestrator(db, prefs, crypto)
            val conv = orchestrator.createNewConversation("nb_hist_case", "Conversa longa", model.id)
            (1..2_000).chunked(500).forEach { batch ->
                batch.forEach { i ->
                    db.conversationAndMessageDao().upsertMessage(MessageEntity(
                        id = "hm_$i", conversationId = conv.id,
                        role = if (i % 2 == 1) MessageRole.USER.name else MessageRole.ASSISTANT.name,
                        content = "Turno histórico #$i para compor contexto.", orderIndex = i
                    ))
                }
            }
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"Resumo."},"finish_reason":"stop"}]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"Resposta."},"finish_reason":"stop"}]}"""))
            probe.start()
            orchestrator.sendMessage("nb_hist_case", conv.id, "Pergunta final", model.id)
        } finally {
            server.shutdown()
            db.close()
        }
        probe.finish(extra = mapOf("historyMessages" to 2_000, "windowMessages" to ChatOrchestrator.MAX_HISTORY_WINDOW_MESSAGES))
    }
}

/** Mede heap da JVM: pico durante o caso (amostragem a cada 5 ms) e retenção após GC. */
internal class HeapProbe(private val caseId: String) {
    private val runtime = Runtime.getRuntime()
    @Volatile private var peak = 0L
    private var baseline = 0L
    private var startedAt = 0L
    private val sampler = Thread {
        try {
            while (true) {
                peak = maxOf(peak, used())
                Thread.sleep(5)
            }
        } catch (e: InterruptedException) {
            // fim da amostragem
        }
    }.apply { isDaemon = true }

    private fun used(): Long = runtime.totalMemory() - runtime.freeMemory()

    private fun gc() {
        System.gc()
        Thread.sleep(50)
    }

    fun start() {
        gc()
        baseline = used()
        peak = baseline
        startedAt = System.nanoTime()
        if (!sampler.isAlive) sampler.start()
    }

    fun finish(extra: Map<String, Any> = emptyMap()) {
        sampler.interrupt()
        sampler.join(1_000)
        peak = maxOf(peak, used())
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        val peakBytes = peak
        gc()
        val retainedBytes = used()
        val report = JSONObject()
            .put("case", caseId)
            .put("baselineMB", mb(baseline))
            .put("peakMB", mb(peakBytes))
            .put("peakDeltaMB", mb(peakBytes - baseline))
            .put("retainedMB", mb(retainedBytes))
            .put("durationMs", durationMs)
            .put("jvmMaxMB", mb(runtime.maxMemory()))
        extra.forEach { (k, v) -> report.put(k, v) }
        val dir = File("build/memory-reports").apply { mkdirs() }
        File(dir, "$caseId.json").writeText(report.toString(2))
    }

    private fun mb(bytes: Long): Double = Math.round(bytes / 1_048_576.0 * 10.0) / 10.0
}
