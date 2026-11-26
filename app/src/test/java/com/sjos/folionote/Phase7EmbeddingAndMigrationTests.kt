package com.sjos.folionote

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.EmbeddingEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.SyncConflictEntity
import com.sjos.folionote.data.local.SyncRecordEntity
import com.sjos.folionote.data.local.SyncTombstoneEntity
import com.sjos.folionote.data.local.VectorCodec
import com.sjos.folionote.data.repository.ChatOrchestrator
import com.sjos.folionote.engine.ai.AiProviderClient
import com.sjos.folionote.engine.ai.ChatCompletionRequest
import com.sjos.folionote.engine.ai.ChatStreamEvent
import com.sjos.folionote.engine.ai.ConnectionTestResult
import com.sjos.folionote.engine.ai.DiscoveredModel
import com.sjos.folionote.engine.ai.ModelLimitsValidator
import com.sjos.folionote.engine.context.ContextBudgetPlanner
import com.sjos.folionote.engine.context.ContextPlanResult
import com.sjos.folionote.engine.indexing.CompositeEmbeddingProvider
import com.sjos.folionote.engine.indexing.EmbeddingTaskType
import com.sjos.folionote.engine.indexing.GeminiEmbeddingManager
import com.sjos.folionote.engine.indexing.GeminiEmbeddingProvider
import com.sjos.folionote.engine.indexing.OpenAiCompatibleEmbeddingProvider
import com.sjos.folionote.engine.retrieval.HybridRetriever
import com.sjos.folionote.engine.tools.ToolEngine
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase7EmbeddingAndMigrationTests {

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

    private fun buildVectorJsonArray(dimensions: Int, seed: Double = 0.125): JSONArray {
        return JSONArray().apply {
            for (i in 0 until dimensions) {
                put(seed + (i % 5) * 0.01)
            }
        }
    }

    // Test 1: Gemini Embedding 2 user-key config, validation, Keystore persistence, removal & strict isolation from chat providers
    @Test
    fun `gemini embedding 2 user key validates persists encrypted removes cleanly and stays isolated from chat providers`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val composite = CompositeEmbeddingProvider(
            OpenAiCompatibleEmbeddingProvider(crypto, OkHttpClient()),
            GeminiEmbeddingProvider(crypto, OkHttpClient())
        )
        val (server, requests) = startLocalServer { req ->
            if (req.path.endsWith(":embedContent")) {
                val resp = JSONObject().put(
                    "embedding",
                    JSONObject().put("values", buildVectorJsonArray(768))
                ).toString()
                200 to ("application/json" to resp)
            } else {
                404 to ("application/json" to """{"error":{"message":"not found"}}""")
            }
        }

        try {
            prefs.clearAllSensitiveCredentials()
            val port = server.address.port
            val baseUrl = "http://127.0.0.1:$port/v1beta"
            val manager = GeminiEmbeddingManager(prefs, crypto, composite, defaultBaseUrl = baseUrl)

            // Blank key rejection
            val blankRes = manager.saveAndValidateKey("   ", "gemini-embedding-2-preview", 768)
            assertFalse(blankRes.success)

            // Valid key -> validates over HTTP, encrypts with Keystore, persists in DataStore
            val okRes = manager.saveAndValidateKey(
                rawApiKey = "AIzaUserOwnKey987654321",
                modelId = "gemini-embedding-2-preview",
                dimensions = 768,
                enableAfterValidation = true,
                baseUrlOverride = baseUrl
            )
            assertTrue(okRes.success)
            assertEquals(1, requests.size)
            assertEquals("AIzaUserOwnKey987654321", requests.first().headers["x-goog-api-key"])

            val stored = prefs.settingsFlow.first()
            assertTrue(stored.hasGeminiEmbeddingKey)
            assertTrue(stored.isGeminiEmbeddingActive)
            assertNotNull(stored.geminiEmbeddingLastValidatedAt)
            assertFalse(stored.geminiEmbeddingEncryptedKey.contains("AIzaUserOwnKey987654321"))
            assertEquals("AIzaUserOwnKey987654321", crypto.decrypt(stored.geminiEmbeddingEncryptedKey))
            assertTrue(manager.getMaskedKey(stored).contains("AIza"))

            // Strict isolation: NO Gemini provider or model is inserted into chat ProviderEntity / ModelEntity tables
            val chatProviders = db.providerAndModelDao().observeAllProviders().first()
            val chatModels = db.providerAndModelDao().observeAllModels().first()
            assertTrue(chatProviders.isEmpty())
            assertTrue(chatModels.isEmpty())

            // Toggle disable preserves key but deactivates semantic spec
            manager.setEnabled(stored, false)
            val disabledSettings = prefs.settingsFlow.first()
            assertTrue(disabledSettings.hasGeminiEmbeddingKey)
            assertFalse(disabledSettings.isGeminiEmbeddingActive)
            assertNull(manager.resolveActiveEmbeddingSpec(disabledSettings, db.providerAndModelDao()))

            // Remove key clears encrypted key and disables flag
            manager.removeKey()
            val cleared = prefs.settingsFlow.first()
            assertFalse(cleared.hasGeminiEmbeddingKey)
            assertFalse(cleared.isGeminiEmbeddingActive)
            assertEquals("", cleared.geminiEmbeddingEncryptedKey)
        } finally {
            prefs.clearAllSensitiveCredentials()
            db.close()
            server.stop(0)
        }
    }

    // Test 2: App works completely without Gemini key using offline BM25 lexical search without falsely claiming semantic mode
    @Test
    fun `app works without any gemini key using offline bm25 retrieval and reports lexical mode honestly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val composite = CompositeEmbeddingProvider(
            OpenAiCompatibleEmbeddingProvider(crypto),
            GeminiEmbeddingProvider(crypto)
        )
        val manager = GeminiEmbeddingManager(prefs, crypto, composite)

        try {
            prefs.clearAllSensitiveCredentials()
            prefs.setActiveEmbeddingModelId(null)
            val settings = prefs.settingsFlow.first()
            assertFalse(settings.hasGeminiEmbeddingKey)
            assertNull(manager.resolveActiveEmbeddingSpec(settings, db.providerAndModelDao()))

            val nb = NotebookEntity("nb_offline", "Caderno Offline")
            val src = SourceEntity("s_off", nb.id, "Resumo.txt", "text/plain", "TXT", "/tmp/resumo.txt", 120, "h_off", processingStatus = "READY", isSelectedForChat = true)
            val chunk = DocumentChunkEntity("c_off", src.id, nb.id, 0, "Redes", 1, 0, 80, "Protocolo HTTP funciona sem modelos locais e sem chave Gemini.", 14)
            db.notebookDao().upsertNotebook(nb)
            db.sourceDao().upsertSource(src)
            db.chunkAndEmbeddingDao().insertChunks(listOf(chunk))
            db.toolAndJobDao().upsertTools(ToolEngine.builtInTools())

            val retriever = HybridRetriever()
            val offlineHits = retriever.searchLexicalOffline("protocolo HTTP", listOf(src), listOf(chunk))
            assertEquals(1, offlineHits.size)
            assertEquals(0f, offlineHits.first().semanticScore, 0.0001f)
            assertTrue(offlineHits.first().lexicalScore > 0f)

            val provider = ProviderEntity("p_chat", "Chat Compat", "http://localhost")
            val model = ModelEntity("m_chat", provider.id, "gpt-compat", "Chat Compat", supportsTools = false)
            db.providerAndModelDao().upsertProvider(provider)
            db.providerAndModelDao().upsertModel(model)

            val fakeAi = object : AiProviderClient {
                override suspend fun testConnection(provider: ProviderEntity) = ConnectionTestResult(true, 1, "OK")
                override suspend fun fetchModels(provider: ProviderEntity) = Result.success(emptyList<DiscoveredModel>())
                override fun streamChatCompletion(
                    provider: ProviderEntity,
                    model: ModelEntity,
                    request: ChatCompletionRequest
                ): Flow<ChatStreamEvent> = flow {
                    emit(ChatStreamEvent.TextDelta("Resposta baseada em BM25 offline."))
                    emit(ChatStreamEvent.Completed("stop"))
                }
            }

            val orchestrator = ChatOrchestrator(
                db = db,
                aiClient = fakeAi,
                embeddingProvider = composite,
                geminiEmbeddingManager = manager,
                retriever = retriever,
                budgetPlanner = ContextBudgetPlanner(),
                toolEngine = ToolEngine(db.sourceDao(), db.chunkAndEmbeddingDao(), db.conversationAndMessageDao(), db.notebookDao(), retriever),
                preferencesStore = prefs
            )
            val conv = orchestrator.createNewConversation(nb.id, "Offline RAG", model.id)
            orchestrator.sendMessage(nb.id, conv.id, "Como funciona o protocolo HTTP?", model.id)

            val modeLabel = orchestrator.runtimeState.value.retrievalModeLabel
            assertEquals("Lexical offline (BM25)", modeLabel)
            assertFalse(modeLabel.contains("Semântico", ignoreCase = true))
        } finally {
            prefs.clearAllSensitiveCredentials()
            db.close()
        }
    }

    // Test 3: Gemini Embedding 2 REST HTTP calls (:embedContent, :batchEmbedContents, x-goog-api-key header & 401/403/500 failure handling)
    @Test
    fun `gemini embedding 2 REST client calls embedContent batchEmbedContents and handles 401 403 500 errors`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val composite = CompositeEmbeddingProvider(
            OpenAiCompatibleEmbeddingProvider(crypto, OkHttpClient()),
            GeminiEmbeddingProvider(crypto, OkHttpClient())
        )
        var statusMode = 200
        val (server, requests) = startLocalServer { req ->
            when (statusMode) {
                401 -> 401 to ("application/json" to """{"error":{"message":"API key invalid"}}""")
                403 -> 403 to ("application/json" to """{"error":{"message":"Permission denied"}}""")
                500 -> 500 to ("application/json" to """{"error":{"message":"Internal server error"}}""")
                else -> when {
                    req.path.endsWith(":embedContent") -> {
                        val resp = JSONObject().put("embedding", JSONObject().put("values", buildVectorJsonArray(1536))).toString()
                        200 to ("application/json" to resp)
                    }
                    req.path.endsWith(":batchEmbedContents") -> {
                        val arr = JSONArray()
                            .put(JSONObject().put("values", buildVectorJsonArray(1536, 0.2)))
                            .put(JSONObject().put("values", buildVectorJsonArray(1536, 0.3)))
                        200 to ("application/json" to JSONObject().put("embeddings", arr).toString())
                    }
                    else -> 404 to ("application/json" to "{}")
                }
            }
        }

        try {
            prefs.clearAllSensitiveCredentials()
            val port = server.address.port
            val baseUrl = "http://127.0.0.1:$port/v1beta"
            val manager = GeminiEmbeddingManager(prefs, crypto, composite, defaultBaseUrl = baseUrl)
            val spec = manager.buildDedicatedSpec(
                encryptedKey = crypto.encrypt("AIzaTestRestKey"),
                modelId = "gemini-embedding-2-preview",
                dimensions = 1536,
                baseUrl = baseUrl
            )

            // Single query -> :embedContent
            val single = composite.embedTexts(spec.provider, spec.model, listOf("consulta"), EmbeddingTaskType.QUERY)
            assertTrue(single.isSuccess)
            assertEquals(1536, single.getOrThrow().dimensions)
            val r1 = requests.last()
            assertEquals("/v1beta/models/gemini-embedding-2-preview:embedContent", r1.path)
            assertNull(r1.query)
            assertEquals("AIzaTestRestKey", r1.headers["x-goog-api-key"])
            assertEquals(1536, JSONObject(r1.body).getInt("outputDimensionality"))
            assertEquals("RETRIEVAL_QUERY", JSONObject(r1.body).getString("taskType"))

            // Batch document -> :batchEmbedContents
            val batch = composite.embedTexts(spec.provider, spec.model, listOf("d1", "d2"), EmbeddingTaskType.DOCUMENT)
            assertTrue(batch.isSuccess)
            assertEquals(2, batch.getOrThrow().vectors.size)
            val r2 = requests.last()
            assertEquals("/v1beta/models/gemini-embedding-2-preview:batchEmbedContents", r2.path)
            assertNull(r2.query)
            assertEquals("AIzaTestRestKey", r2.headers["x-goog-api-key"])

            // Failure codes 401, 403, 500
            for (code in listOf(401, 403, 500)) {
                statusMode = code
                val errRes = manager.testEncryptedKey(spec.provider.encryptedApiKey, "gemini-embedding-2-preview", 1536, baseUrl)
                assertFalse(errRes.success)
                assertTrue(errRes.message.contains("$code") || errRes.message.isNotBlank())
            }
        } finally {
            prefs.clearAllSensitiveCredentials()
            server.stop(0)
        }
    }

    // Test 4: Vector dimension validation, persistence (768/1536/3072), and dimension mismatch cleanup
    @Test
    fun `vector dimension validation persistence and mismatch detection protect index integrity`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val composite = CompositeEmbeddingProvider(
            OpenAiCompatibleEmbeddingProvider(crypto, OkHttpClient()),
            GeminiEmbeddingProvider(crypto, OkHttpClient())
        )
        var serverReturnedDims = 768
        val (server, _) = startLocalServer { req ->
            if (req.path.endsWith(":embedContent")) {
                val resp = JSONObject().put("embedding", JSONObject().put("values", buildVectorJsonArray(serverReturnedDims))).toString()
                200 to ("application/json" to resp)
            } else {
                404 to ("application/json" to "{}")
            }
        }

        try {
            prefs.clearAllSensitiveCredentials()
            val port = server.address.port
            val baseUrl = "http://127.0.0.1:$port/v1beta"
            val manager = GeminiEmbeddingManager(prefs, crypto, composite, defaultBaseUrl = baseUrl)

            // Server returns 768 while user asked for 3072 -> rejected cleanly
            serverReturnedDims = 768
            val mismatchRes = manager.saveAndValidateKey("AIzaDimTestKey", "gemini-embedding-2-preview", 3072, true, baseUrl)
            assertFalse(mismatchRes.success)
            assertTrue(mismatchRes.message.contains("Dimensão incompatível"))

            // Server returns 3072 matching requested 3072 -> succeeds and persists
            serverReturnedDims = 3072
            val valid3072 = manager.saveAndValidateKey("AIzaDimTestKey", "gemini-embedding-2-preview", 3072, true, baseUrl)
            assertTrue(valid3072.success)
            assertEquals(3072, prefs.settingsFlow.first().geminiEmbeddingDimensions)

            // Update model & dimension to 1536 -> persists
            manager.updateModelAndDimensions(prefs.settingsFlow.first(), "gemini-embedding-001", 1536)
            val updated = prefs.settingsFlow.first()
            assertEquals("gemini-embedding-001", updated.geminiEmbeddingModelId)
            assertEquals(1536, updated.geminiEmbeddingDimensions)

            // DB filtering detects incompatible stored 768d embeddings vs active 1536d model
            val nb = NotebookEntity("nb_dim", "Dimensões")
            val src = SourceEntity("s_dim", nb.id, "doc.txt", "text/plain", "TXT", "/tmp/d.txt", 10, "h_dim")
            val chunk = DocumentChunkEntity("c_dim", src.id, nb.id, 0, "", 1, 0, 10, "Texto teste", 3)
            db.notebookDao().upsertNotebook(nb)
            db.sourceDao().upsertSource(src)
            db.chunkAndEmbeddingDao().insertChunks(listOf(chunk))
            db.chunkAndEmbeddingDao().insertEmbeddings(
                listOf(
                    EmbeddingEntity(
                        id = "e_old",
                        chunkId = chunk.id,
                        notebookId = nb.id,
                        sourceId = src.id,
                        embeddingProviderId = GeminiEmbeddingManager.DEDICATED_PROVIDER_ID,
                        embeddingModelId = "gemini-embedding-2-preview",
                        dimensions = 768,
                        vectorBlob = VectorCodec.floatArrayToBytes(FloatArray(768) { 0.1f })
                    )
                )
            )

            // Querying for 1536d returns empty (prevents vector dimension crash)
            val matched1536 = db.chunkAndEmbeddingDao().getEmbeddingsForSources(listOf(src.id), "gemini-embedding-001", 1536)
            assertTrue(matched1536.isEmpty())

            // Cleanup removes incompatible embeddings
            db.chunkAndEmbeddingDao().deleteIncompatibleEmbeddingsForNotebook(nb.id, "gemini-embedding-001", 1536)
            assertTrue(db.chunkAndEmbeddingDao().getAllEmbeddingsOnce().isEmpty())
        } finally {
            prefs.clearAllSensitiveCredentials()
            db.close()
            server.stop(0)
        }
    }

    // Test 20: Lossless Room MIGRATION_1_2 preserving v1 user data and adding v2 sync tables
    @Test
    fun `room migration 1 to 2 preserves all v1 user data and creates v2 sync tables`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createV1Schema(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )

        var migratedDb: FolioNoteDatabase? = null
        try {
            val v1Db = helper.writableDatabase
            v1Db.execSQL("INSERT INTO collections (id, name, description, createdAt, updatedAt) VALUES ('col_1', 'Direito', 'Estudos', 100, 100)")
            v1Db.execSQL("INSERT INTO notebooks (id, title, description, iconKey, collectionId, isPinned, createdAt, updatedAt) VALUES ('nb_1', 'Constitucional', 'Aulas', 'book', 'col_1', 1, 100, 200)")
            v1Db.execSQL("INSERT INTO sources (id, notebookId, name, mimeType, sourceType, filePath, sizeBytes, fileHashSha256, extractedText, pageCount, metadataJson, processingStatus, indexingProgress, errorMessage, isSelectedForChat, importedAt, updatedAt) VALUES ('src_1', 'nb_1', 'CF88.pdf', 'application/pdf', 'PDF', '/tmp/cf88.pdf', 1024, 'sha1', 'Artigo 5', 1, '{}', 'READY', 1.0, NULL, 1, 100, 200)")
            v1Db.execSQL("INSERT INTO document_chunks (id, sourceId, notebookId, chunkIndex, sectionTitle, pageNumber, startOffset, endOffset, content, tokenEstimate, createdAt) VALUES ('chk_1', 'src_1', 'nb_1', 0, 'Direitos', 1, 0, 8, 'Artigo 5', 3, 100)")
            v1Db.execSQL("INSERT INTO conversations (id, notebookId, title, selectedModelInternalId, systemRolePreset, customSystemPrompt, responseLength, runningSummary, summarizedUpToOrder, createdAt, updatedAt) VALUES ('conv_1', 'nb_1', 'Revisão', NULL, 'DEFAULT', '', 'DEFAULT', '', 0, 100, 200)")
            v1Db.execSQL("INSERT INTO messages (id, conversationId, role, content, modelDisplayName, toolCallsJson, toolCallId, tokenCountEstimate, contextTokensUsed, isPinned, isError, orderIndex, createdAt) VALUES ('msg_1', 'conv_1', 'USER', 'Resuma o artigo 5', NULL, NULL, NULL, 5, 0, 0, 0, 1, 100)")
            v1Db.close()
            helper.close()

            migratedDb = Room.databaseBuilder(context, FolioNoteDatabase::class.java, dbName)
                .addMigrations(FolioNoteDatabase.MIGRATION_1_2, FolioNoteDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()

            // Verify all v1 data survived intact
            val cols = migratedDb.notebookDao().getAllCollectionsOnce()
            val nbs = migratedDb.notebookDao().getAllNotebooksOnce()
            val srcs = migratedDb.sourceDao().getAllSourcesOnce()
            val chunks = migratedDb.chunkAndEmbeddingDao().getAllChunksOnce()
            val convs = migratedDb.conversationAndMessageDao().getAllConversationsOnce()
            val msgs = migratedDb.conversationAndMessageDao().getAllMessagesOnce()

            assertEquals(1, cols.size)
            assertEquals("Direito", cols.first().name)
            assertEquals(1, nbs.size)
            assertEquals("Constitucional", nbs.first().title)
            assertEquals(1, srcs.size)
            assertEquals("CF88.pdf", srcs.first().name)
            assertEquals(1, chunks.size)
            assertEquals("Artigo 5", chunks.first().content)
            assertEquals(1, convs.size)
            assertEquals(1, msgs.size)
            assertEquals("Resuma o artigo 5", msgs.first().content)

            // Verify new v2 sync tables exist and function properly
            migratedDb.syncDao().upsertSyncRecord(
                SyncRecordEntity("NOTEBOOK:nb_1", "NOTEBOOK", "nb_1", 1L, "hash1", 200L, 250L, null, false)
            )
            migratedDb.syncDao().upsertTombstone(
                SyncTombstoneEntity("NOTEBOOK:old", "NOTEBOOK", "old", 300L, 1L, false)
            )
            migratedDb.syncDao().upsertConflict(
                SyncConflictEntity("NOTEBOOK_nb_1", "NOTEBOOK", "nb_1", "Constitucional", 200L, 220L, "L", "R", "{}")
            )
            assertEquals(1, migratedDb.syncDao().getAllSyncRecordsOnce().size)
            assertEquals(1, migratedDb.syncDao().getAllTombstonesOnce().size)
            assertEquals(1, migratedDb.syncDao().getPendingConflictsOnce().size)
        } finally {
            migratedDb?.close()
            File(dbFile.path).delete()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
        }
    }

    private fun createV1Schema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `collections` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `notebooks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `collectionId` TEXT, `isPinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notebooks_collectionId` ON `notebooks` (`collectionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notebooks_updatedAt` ON `notebooks` (`updatedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sources` (`id` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `name` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `filePath` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `fileHashSha256` TEXT NOT NULL, `extractedText` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, `metadataJson` TEXT NOT NULL, `processingStatus` TEXT NOT NULL, `indexingProgress` REAL NOT NULL, `errorMessage` TEXT, `isSelectedForChat` INTEGER NOT NULL, `importedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sources_notebookId` ON `sources` (`notebookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sources_fileHashSha256` ON `sources` (`fileHashSha256`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `document_chunks` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `sectionTitle` TEXT NOT NULL, `pageNumber` INTEGER NOT NULL, `startOffset` INTEGER NOT NULL, `endOffset` INTEGER NOT NULL, `content` TEXT NOT NULL, `tokenEstimate` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_chunks_sourceId` ON `document_chunks` (`sourceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_chunks_notebookId` ON `document_chunks` (`notebookId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `embeddings` (`id` TEXT NOT NULL, `chunkId` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `embeddingProviderId` TEXT NOT NULL, `embeddingModelId` TEXT NOT NULL, `dimensions` INTEGER NOT NULL, `vectorBlob` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`chunkId`) REFERENCES `document_chunks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_embeddings_chunkId` ON `embeddings` (`chunkId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_embeddings_notebookId` ON `embeddings` (`notebookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_embeddings_sourceId` ON `embeddings` (`sourceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_embeddings_embeddingModelId` ON `embeddings` (`embeddingModelId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `chatPath` TEXT NOT NULL, `modelsPath` TEXT NOT NULL, `embeddingsPath` TEXT NOT NULL, `authHeaderName` TEXT NOT NULL, `authPrefix` TEXT NOT NULL, `encryptedApiKey` TEXT NOT NULL, `extraHeadersJson` TEXT NOT NULL, `defaultModelId` TEXT, `timeoutSeconds` INTEGER NOT NULL, `supportsStreamingDefault` INTEGER NOT NULL, `extraParamsJson` TEXT NOT NULL, `providerType` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `models` (`id` TEXT NOT NULL, `providerId` TEXT NOT NULL, `modelId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `contextWindow` INTEGER NOT NULL, `maxOutputTokens` INTEGER NOT NULL, `supportsStreaming` INTEGER NOT NULL, `supportsTools` INTEGER NOT NULL, `supportsSystemMessage` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `temperature` REAL NOT NULL, `topP` REAL NOT NULL, `frequencyPenalty` REAL NOT NULL, `presencePenalty` REAL NOT NULL, `customParamsJson` TEXT NOT NULL, `notes` TEXT NOT NULL, `isEmbeddingModel` INTEGER NOT NULL, `embeddingDimensions` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`providerId`) REFERENCES `providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_models_providerId` ON `models` (`providerId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_models_providerId_modelId` ON `models` (`providerId`, `modelId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `title` TEXT NOT NULL, `selectedModelInternalId` TEXT, `systemRolePreset` TEXT NOT NULL, `customSystemPrompt` TEXT NOT NULL, `responseLength` TEXT NOT NULL, `runningSummary` TEXT NOT NULL, `summarizedUpToOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_notebookId` ON `conversations` (`notebookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_updatedAt` ON `conversations` (`updatedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `modelDisplayName` TEXT, `toolCallsJson` TEXT, `toolCallId` TEXT, `tokenCountEstimate` INTEGER NOT NULL, `contextTokensUsed` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, `isError` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_orderIndex` ON `messages` (`orderIndex`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `citations` (`id` TEXT NOT NULL, `messageId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `chunkId` TEXT NOT NULL, `pageNumber` INTEGER NOT NULL, `sectionTitle` TEXT NOT NULL, `startOffset` INTEGER NOT NULL, `endOffset` INTEGER NOT NULL, `excerpt` TEXT NOT NULL, `relevanceScore` REAL NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_citations_messageId` ON `citations` (`messageId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_citations_sourceId` ON `citations` (`sourceId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tool_definitions` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `parametersSchemaJson` TEXT NOT NULL, `requiresUserApproval` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `isBuiltIn` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `processing_jobs` (`id` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `jobType` TEXT NOT NULL, `status` TEXT NOT NULL, `progress` REAL NOT NULL, `statusMessage` TEXT NOT NULL, `workRequestId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_processing_jobs_notebookId` ON `processing_jobs` (`notebookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_processing_jobs_sourceId` ON `processing_jobs` (`sourceId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `studio_artifacts` (`id` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `artifactType` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `sourceCount` INTEGER NOT NULL, `modelDisplayName` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_studio_artifacts_notebookId` ON `studio_artifacts` (`notebookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_studio_artifacts_updatedAt` ON `studio_artifacts` (`updatedAt`)")
    }

    @Test
    fun `room migration 2 to 3 adds isPartial column with default false and preserves messages`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_2_3_test_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createV1Schema(db)
                FolioNoteDatabase.MIGRATION_1_2.migrate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )

        var migratedDb: FolioNoteDatabase? = null
        try {
            val v2Db = helper.writableDatabase
            v2Db.execSQL("INSERT INTO notebooks (id, title, description, iconKey, collectionId, isPinned, createdAt, updatedAt) VALUES ('nb2', 'V2', '', 'book', NULL, 0, 100, 100)")
            v2Db.execSQL("INSERT INTO conversations (id, notebookId, title, selectedModelInternalId, systemRolePreset, customSystemPrompt, responseLength, runningSummary, summarizedUpToOrder, createdAt, updatedAt) VALUES ('conv2', 'nb2', 'Chat V2', NULL, 'DEFAULT', '', 'DEFAULT', '', 0, 100, 100)")
            v2Db.execSQL("INSERT INTO messages (id, conversationId, role, content, modelDisplayName, toolCallsJson, toolCallId, tokenCountEstimate, contextTokensUsed, isPinned, isError, orderIndex, createdAt) VALUES ('msg2', 'conv2', 'ASSISTANT', 'Resposta v2 intacta', 'GPT', NULL, NULL, 5, 10, 0, 0, 1, 100)")
            v2Db.close()
            helper.close()

            migratedDb = Room.databaseBuilder(context, FolioNoteDatabase::class.java, dbName)
                .addMigrations(FolioNoteDatabase.MIGRATION_1_2, FolioNoteDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()

            val msgs = migratedDb.conversationAndMessageDao().getMessagesForConversationOnce("conv2")
            assertEquals(1, msgs.size)
            assertEquals("Resposta v2 intacta", msgs.first().content)
            assertFalse(msgs.first().isPartial)
        } finally {
            migratedDb?.close()
            File(dbFile.path).delete()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
        }
    }

    @Test
    fun `model limits validator rejects empty zero negative huge inputs and maxOutput above contextWindow and room persists updates`() = runTest {
        // Invalid inputs never fall back silently
        val empty = ModelLimitsValidator.validate("", "2048")
        assertFalse(empty.isValid)
        assertNull(empty.contextWindow)
        assertNotNull(empty.contextWindowError)

        val zero = ModelLimitsValidator.validate("0", "2048")
        assertFalse(zero.isValid)
        assertNull(zero.contextWindow)
        assertNotNull(zero.contextWindowError)

        val negative = ModelLimitsValidator.validate("-500", "2048")
        assertFalse(negative.isValid)
        assertNull(negative.contextWindow)
        assertNotNull(negative.contextWindowError)

        val huge = ModelLimitsValidator.validate("99999999999", "2048")
        assertFalse(huge.isValid)
        assertNull(huge.contextWindow)
        assertNotNull(huge.contextWindowError)

        val maxOutTooBig = ModelLimitsValidator.validate("4096", "8192")
        assertFalse(maxOutTooBig.isValid)
        assertNotNull(maxOutTooBig.maxOutputTokensError)

        val valid = ModelLimitsValidator.validate("128000", "8192")
        assertTrue(valid.isValid)
        assertEquals(128000, valid.contextWindow)
        assertEquals(8192, valid.maxOutputTokens)

        // Overflow protection in ContextBudgetPlanner and Studio budgetChars
        val studioChars = ModelLimitsValidator.computeStudioBudgetChars(Int.MAX_VALUE, 8192)
        assertTrue(studioChars in 3000..ModelLimitsValidator.MAX_STUDIO_CONTEXT_CHARS)

        val planner = ContextBudgetPlanner()
        val extremeModel = ModelEntity("m_big", "p1", "big", "Big", contextWindow = Int.MAX_VALUE, maxOutputTokens = 8192)
        val conv = com.sjos.folionote.data.local.ConversationEntity("c_big", "nb_big", "Big")
        val plan = planner.buildContextPlan(extremeModel, conv, emptyList(), "Resumo", emptyList(), emptyList())
        assertTrue(plan is ContextPlanResult.Ready)
        val ready = plan as ContextPlanResult.Ready
        assertTrue(ready.report.availableInputBudget > 0)

        // Room persistence after close & reopen
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "model_limits_persist_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        var db1: FolioNoteDatabase? = null
        var db2: FolioNoteDatabase? = null
        try {
            db1 = Room.databaseBuilder(context, FolioNoteDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            db1.providerAndModelDao().upsertProvider(ProviderEntity("p_persist", "OpenAI", "https://api.openai.com/v1"))
            val initialModel = ModelEntity(
                id = "m_persist",
                providerId = "p_persist",
                modelId = "gpt-4o",
                displayName = "GPT-4o",
                contextWindow = 8192,
                maxOutputTokens = 2048
            )
            db1.providerAndModelDao().upsertModel(initialModel)
            // Simulate user editing existing model contextWindow to 262144 and maxOutput to 16384
            val validatedEdit = ModelLimitsValidator.validate("262144", "16384")
            assertTrue(validatedEdit.isValid)
            db1.providerAndModelDao().upsertModel(
                initialModel.copy(
                    contextWindow = validatedEdit.contextWindow!!,
                    maxOutputTokens = validatedEdit.maxOutputTokens!!
                )
            )
            db1.close()
            db1 = null

            db2 = Room.databaseBuilder(context, FolioNoteDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val reloaded = db2.providerAndModelDao().getModelByInternalId("m_persist")
            assertNotNull(reloaded)
            assertEquals(262144, reloaded?.contextWindow)
            assertEquals(16384, reloaded?.maxOutputTokens)
        } finally {
            db1?.close()
            db2?.close()
            File(dbFile.path).delete()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
        }
    }
}
