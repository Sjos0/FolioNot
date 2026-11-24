package com.sjos.folionote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.MessageRole
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.ProviderType
import com.sjos.folionote.data.repository.ChatOrchestrator
import com.sjos.folionote.engine.ai.AiProviderClient
import com.sjos.folionote.engine.ai.ChatCompletionRequest
import com.sjos.folionote.engine.ai.ChatMessagePayload
import com.sjos.folionote.engine.ai.ChatStreamEvent
import com.sjos.folionote.engine.ai.ConnectionTestResult
import com.sjos.folionote.engine.ai.DiscoveredModel
import com.sjos.folionote.engine.ai.OpenAiCompatibleClient
import com.sjos.folionote.engine.ai.ToolCallRequest
import com.sjos.folionote.engine.context.ContextBudgetPlanner
import com.sjos.folionote.engine.indexing.CompositeEmbeddingProvider
import com.sjos.folionote.engine.indexing.EmbeddingTaskType
import com.sjos.folionote.engine.indexing.GeminiEmbeddingProvider
import com.sjos.folionote.engine.indexing.OpenAiCompatibleEmbeddingProvider
import com.sjos.folionote.engine.retrieval.HybridRetriever
import com.sjos.folionote.engine.tools.ToolEngine
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiAndEmbeddingClientTests {

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String
    )

    private fun startLocalServer(
        handler: (RecordedRequest) -> Pair<Int, Pair<String, String>>
    ): Pair<HttpServer, MutableList<RecordedRequest>> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val recorded = CopyOnWriteArrayList<RecordedRequest>()
        server.createContext("/") { exchange ->
            val reqBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            val headerMap = mutableMapOf<String, String>()
            exchange.requestHeaders.forEach { (k, v) ->
                if (k != null && v.isNotEmpty()) headerMap[k.lowercase()] = v.joinToString(",")
            }
            val rec = RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.query,
                headers = headerMap,
                body = reqBody
            )
            recorded.add(rec)
            val (status, contentTypeAndBody) = handler(rec)
            val (contentType, respBody) = contentTypeAndBody
            val bytes = respBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to recorded
    }

    @Test
    fun `openai client performs real HTTP model discovery and SSE streaming`() = runTest {
        val crypto = KeystoreCryptoManager()
        val (server, requests) = startLocalServer { req ->
            when {
                req.path.endsWith("/models") -> {
                    200 to ("application/json" to """{"data":[{"id":"gpt-local","context_length":32768}]}""")
                }
                req.path.endsWith("/chat/completions") -> {
                    val sse = """
                        data: {"choices":[{"delta":{"content":"Folio"}}]}

                        data: {"choices":[{"delta":{"content":"Note!"}}]}

                        data: [DONE]

                    """.trimIndent()
                    200 to ("text/event-stream" to sse)
                }
                else -> 404 to ("application/json" to """{"error":{"message":"Not found"}}""")
            }
        }

        try {
            val port = server.address.port
            val provider = ProviderEntity(
                id = "p_local",
                name = "Local OpenAI",
                baseUrl = "http://127.0.0.1:$port/v1",
                encryptedApiKey = crypto.encrypt("sk-local-test-key"),
                extraHeadersJson = """{"X-Workspace":"folio"}"""
            )
            val model = ModelEntity(
                id = "m_local",
                providerId = provider.id,
                modelId = "gpt-local",
                displayName = "GPT Local"
            )

            val client = OpenAiCompatibleClient(crypto, OkHttpClient())
            val modelsRes = client.fetchModels(provider)
            assertTrue(modelsRes.isSuccess)
            assertEquals("gpt-local", modelsRes.getOrThrow().first().modelId)

            val streamEvents = client.streamChatCompletion(
                provider = provider,
                model = model,
                request = ChatCompletionRequest(messages = listOf(ChatMessagePayload("user", "Oi")))
            ).toList()

            val combinedText = streamEvents.filterIsInstance<ChatStreamEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("FolioNote!", combinedText)
            assertTrue(streamEvents.any { it is ChatStreamEvent.Completed })

            val chatReq = requests.last()
            assertEquals("Bearer sk-local-test-key", chatReq.headers["authorization"])
            assertEquals("folio", chatReq.headers["x-workspace"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `gemini native embedding sends x-goog-api-key header taskType and outputDimensionality without query key`() = runTest {
        val crypto = KeystoreCryptoManager()
        val (server, requests) = startLocalServer { req ->
            when {
                req.path.endsWith(":embedContent") -> {
                    200 to ("application/json" to """{"embedding":{"values":[0.25,0.5,0.75]}}""")
                }
                req.path.endsWith(":batchEmbedContents") -> {
                    200 to ("application/json" to """{"embeddings":[{"values":[0.1,0.2,0.3]},{"values":[0.4,0.5,0.6]}]}""")
                }
                else -> 404 to ("application/json" to """{"error":{"message":"Unexpected path ${req.path}"}}""")
            }
        }

        try {
            val port = server.address.port
            val geminiProvider = ProviderEntity(
                id = "p_gemini_emb",
                name = "Gemini Native Embedding",
                baseUrl = "http://127.0.0.1:$port/v1beta",
                embeddingsPath = "/models/{model}:batchEmbedContents",
                authHeaderName = "x-goog-api-key",
                authPrefix = "",
                encryptedApiKey = crypto.encrypt("AIzaHeaderSecret123"),
                providerType = ProviderType.GEMINI_NATIVE_EMBEDDING.name
            )
            val geminiModel = ModelEntity(
                id = "m_gem_emb",
                providerId = geminiProvider.id,
                modelId = "gemini-embedding-2-preview",
                displayName = "Gemini Embedding 2",
                isEmbeddingModel = true,
                embeddingDimensions = 768
            )

            val composite = CompositeEmbeddingProvider(
                openAiProvider = OpenAiCompatibleEmbeddingProvider(crypto, OkHttpClient()),
                geminiProvider = GeminiEmbeddingProvider(crypto, OkHttpClient())
            )

            // 1. Single query -> :embedContent + RETRIEVAL_QUERY
            val queryRes = composite.embedTexts(
                provider = geminiProvider,
                model = geminiModel,
                texts = listOf("cláusula penal"),
                taskType = EmbeddingTaskType.QUERY
            )
            assertTrue(queryRes.isSuccess)
            assertEquals(3, queryRes.getOrThrow().dimensions)
            val req1 = requests.last()
            assertEquals("/v1beta/models/gemini-embedding-2-preview:embedContent", req1.path)
            assertNull(req1.query)
            assertEquals("AIzaHeaderSecret123", req1.headers["x-goog-api-key"])
            val json1 = JSONObject(req1.body)
            assertEquals("RETRIEVAL_QUERY", json1.getString("taskType"))
            assertEquals(768, json1.getInt("outputDimensionality"))

            // 2. Batch documents -> :batchEmbedContents + RETRIEVAL_DOCUMENT
            val docRes = composite.embedTexts(
                provider = geminiProvider,
                model = geminiModel,
                texts = listOf("doc A", "doc B"),
                taskType = EmbeddingTaskType.DOCUMENT
            )
            assertTrue(docRes.isSuccess)
            assertEquals(2, docRes.getOrThrow().vectors.size)
            val req2 = requests.last()
            assertEquals("/v1beta/models/gemini-embedding-2-preview:batchEmbedContents", req2.path)
            assertNull(req2.query)
            val json2 = JSONObject(req2.body)
            assertEquals("RETRIEVAL_DOCUMENT", json2.getJSONArray("requests").getJSONObject(0).getString("taskType"))
            assertEquals(768, json2.getJSONArray("requests").getJSONObject(1).getInt("outputDimensionality"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `composite embedding provider routes openai compatible requests with dimensions`() = runTest {
        val crypto = KeystoreCryptoManager()
        val (server, requests) = startLocalServer { req ->
            when {
                req.path.endsWith("/embeddings") -> {
                    200 to ("application/json" to """{"data":[{"index":0,"embedding":[0.6,0.8]}]}""")
                }
                else -> 404 to ("application/json" to """{"error":{"message":"Not found"}}""")
            }
        }
        try {
            val port = server.address.port
            val openAiProvider = ProviderEntity(
                id = "p_oai_emb",
                name = "OpenAI Emb",
                baseUrl = "http://127.0.0.1:$port/v1",
                embeddingsPath = "/embeddings",
                encryptedApiKey = crypto.encrypt("sk-emb-123"),
                providerType = ProviderType.OPENAI_COMPATIBLE.name
            )
            val model = ModelEntity(
                id = "m_oai_emb",
                providerId = openAiProvider.id,
                modelId = "text-embedding-3-small",
                displayName = "Text Embedding 3 Small",
                isEmbeddingModel = true,
                embeddingDimensions = 2
            )
            val composite = CompositeEmbeddingProvider(
                openAiProvider = OpenAiCompatibleEmbeddingProvider(crypto, OkHttpClient()),
                geminiProvider = GeminiEmbeddingProvider(crypto, OkHttpClient())
            )
            val result = composite.embedTexts(openAiProvider, model, listOf("FolioNote"), EmbeddingTaskType.DOCUMENT)
            assertTrue(result.isSuccess)
            assertEquals(2, result.getOrThrow().dimensions)
            val req = requests.last()
            assertEquals("/v1/embeddings", req.path)
            assertEquals("Bearer sk-emb-123", req.headers["authorization"])
            assertEquals(2, JSONObject(req.body).getInt("dimensions"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chat orchestrator loops through multi-tool approval batch and preserves partial text on cancel`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = AppPreferencesStore(context)

        try {
            db.notebookDao().upsertNotebook(NotebookEntity("nb1", "Caderno Testes"))
            db.toolAndJobDao().upsertTools(ToolEngine.builtInTools())
            val provider = ProviderEntity("p1", "Mock", "http://localhost")
            val model = ModelEntity("m1", "p1", "mock-chat", "Mock Chat", supportsTools = true)
            db.providerAndModelDao().upsertProvider(provider)
            db.providerAndModelDao().upsertModel(model)

            var callStep = 0
            val partialEmitted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val fakeAi = object : AiProviderClient {
                override suspend fun testConnection(provider: ProviderEntity) = ConnectionTestResult(true, 1, "OK")
                override suspend fun fetchModels(provider: ProviderEntity) = Result.success(emptyList<DiscoveredModel>())
                override fun streamChatCompletion(
                    provider: ProviderEntity,
                    model: ModelEntity,
                    request: ChatCompletionRequest
                ): Flow<ChatStreamEvent> = flow {
                    callStep += 1
                    when (callStep) {
                        1 -> {
                            emit(
                                ChatStreamEvent.ToolCallsReady(
                                    listOf(
                                        ToolCallRequest("t1", ToolEngine.TOOL_CREATE_NOTE, """{"title":"Nota 1","content":"Conteúdo 1"}"""),
                                        ToolCallRequest("t2", ToolEngine.TOOL_CREATE_NOTE, """{"title":"Nota 2","content":"Conteúdo 2"}""")
                                    )
                                )
                            )
                            emit(ChatStreamEvent.Completed("tool_calls"))
                        }
                        2 -> {
                            emit(ChatStreamEvent.TextDelta("Ambas as notas foram salvas."))
                            emit(ChatStreamEvent.Completed("stop"))
                        }
                        else -> {
                            emit(ChatStreamEvent.TextDelta("Trecho parcial importante"))
                            partialEmitted.complete(Unit)
                            delay(30_000)
                        }
                    }
                }
            }

            val orchestrator = ChatOrchestrator(
                db = db,
                aiClient = fakeAi,
                embeddingProvider = CompositeEmbeddingProvider(
                    OpenAiCompatibleEmbeddingProvider(KeystoreCryptoManager()),
                    GeminiEmbeddingProvider(KeystoreCryptoManager())
                ),
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

            val conv = orchestrator.createNewConversation("nb1", "Conversa Tools", model.id)
            orchestrator.sendMessage("nb1", conv.id, "Crie duas notas", model.id)

            // Paused on first approval with second call still queued
            val pending1 = orchestrator.runtimeState.value.pendingToolApproval
            assertEquals("t1", pending1?.call?.id)
            assertEquals(1, pending1?.remainingPendingCalls?.size)

            // Approve first -> should pause on second without losing queue
            orchestrator.resolvePendingToolApproval(true)
            val pending2 = orchestrator.runtimeState.value.pendingToolApproval
            assertEquals("t2", pending2?.call?.id)
            assertEquals(0, pending2?.remainingPendingCalls?.size)

            // Approve second -> completes model loop
            orchestrator.resolvePendingToolApproval(true)
            assertNull(orchestrator.runtimeState.value.pendingToolApproval)

            val artifacts = db.conversationAndMessageDao().observeStudioArtifacts("nb1").first()
            assertEquals(2, artifacts.size)

            val msgsAfterBatch = db.conversationAndMessageDao().getMessagesForConversationOnce(conv.id)
            assertEquals("Ambas as notas foram salvas.", msgsAfterBatch.last().content)

            // Now test streaming interruption preserves partial output
            val streamJob = launch {
                orchestrator.sendMessage("nb1", conv.id, "Gere texto longo", model.id)
            }
            partialEmitted.await()
            assertTrue(orchestrator.runtimeState.value.streamingText.contains("Trecho parcial"))
            streamJob.cancel()
            orchestrator.cancelStreamingAndPreservePartial(conv.id, model.displayName)

            val msgsAfterCancel = db.conversationAndMessageDao().getMessagesForConversationOnce(conv.id)
            val savedPartial = msgsAfterCancel.lastOrNull { it.role == MessageRole.ASSISTANT.name }
            assertTrue(savedPartial?.content?.contains("Trecho parcial importante") == true)
            assertTrue(savedPartial?.content?.contains("interrompida pelo usuário") == true)
        } finally {
            db.close()
        }
    }
}
