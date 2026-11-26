package com.sjos.folionote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.network.SharedHttpClients
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
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
import com.sjos.folionote.data.local.SyncRecordEntity
import com.sjos.folionote.data.local.SyncTombstoneEntity
import com.sjos.folionote.data.local.VectorCodec
import com.sjos.folionote.data.repository.ChatOrchestrator
import com.sjos.folionote.engine.ai.ModelLimitsValidator
import com.sjos.folionote.engine.ai.OpenAiCompatibleClient
import com.sjos.folionote.engine.context.ContextBudgetPlanner
import com.sjos.folionote.engine.context.ContextPlanResult
import com.sjos.folionote.engine.drive.DriveSyncEngine
import com.sjos.folionote.engine.drive.DriveSyncWorker
import com.sjos.folionote.engine.indexing.CompositeEmbeddingProvider
import com.sjos.folionote.engine.indexing.GeminiEmbeddingManager
import com.sjos.folionote.engine.indexing.GeminiEmbeddingProvider
import com.sjos.folionote.engine.indexing.OpenAiCompatibleEmbeddingProvider
import com.sjos.folionote.engine.retrieval.HybridRetriever
import com.sjos.folionote.engine.tools.ToolEngine
import com.sjos.folionote.engine.updater.AppUpdateManager
import com.sjos.folionote.engine.updater.GitHubReleaseService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MissionValidationAndMemoryTests {

    @Test
    fun `model limits validator and budget planners prevent overflow and silent coercion`() {
        val blank = ModelLimitsValidator.validate("", "2048")
        assertFalse(blank.isValid)
        assertNull(blank.contextWindow)

        val zero = ModelLimitsValidator.validate("0", "2048")
        assertFalse(zero.isValid)
        assertNull(zero.contextWindow)

        val negative = ModelLimitsValidator.validate("-500", "2048")
        assertFalse(negative.isValid)
        assertNull(negative.contextWindow)

        val huge = ModelLimitsValidator.validate("99999999999", "2048")
        assertFalse(huge.isValid)
        assertNull(huge.contextWindow)

        val outExceedsCtx = ModelLimitsValidator.validate("4096", "8192")
        assertFalse(outExceedsCtx.isValid)
        assertNull(outExceedsCtx.maxOutputTokens)

        val valid = ModelLimitsValidator.validate("128000", "8192")
        assertTrue(valid.isValid)
        assertEquals(128000, valid.contextWindow)
        assertEquals(8192, valid.maxOutputTokens)

        val equalExtremeBudget = ModelLimitsValidator.computeStudioBudgetChars(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(3000, equalExtremeBudget)

        val extremeBudget = ModelLimitsValidator.computeStudioBudgetChars(Int.MAX_VALUE, 4096)
        assertTrue(extremeBudget > 0)
        assertEquals(ModelLimitsValidator.MAX_STUDIO_CONTEXT_CHARS, extremeBudget)

        val equalExtremeModel = ModelEntity(
            id = "m_huge_eq",
            providerId = "p1",
            modelId = "huge-eq",
            displayName = "Huge Equal",
            contextWindow = Int.MAX_VALUE,
            maxOutputTokens = Int.MAX_VALUE
        )
        val conv = com.sjos.folionote.data.local.ConversationEntity(
            id = "c_huge",
            notebookId = "nb_huge",
            title = "Teste Overflow"
        )
        val eqPlan = ContextBudgetPlanner().buildContextPlan(
            model = equalExtremeModel,
            conversation = conv,
            fullHistory = emptyList(),
            userQuery = "Pergunta de teste sobre limites",
            retrievedPassages = emptyList(),
            enabledTools = emptyList()
        )
        assertTrue(eqPlan is ContextPlanResult.BudgetExceeded)
        val eqExceeded = eqPlan as ContextPlanResult.BudgetExceeded
        assertEquals(ModelLimitsValidator.MAX_CONTEXT_WINDOW, eqExceeded.report.contextWindow)
        assertEquals(0, eqExceeded.report.availableInputBudget)

        val hugeModel = equalExtremeModel.copy(id = "m_huge", maxOutputTokens = 4096)
        val plan = ContextBudgetPlanner().buildContextPlan(
            model = hugeModel,
            conversation = conv,
            fullHistory = emptyList(),
            userQuery = "Pergunta de teste sobre limites",
            retrievedPassages = emptyList(),
            enabledTools = emptyList()
        )
        assertTrue(plan is ContextPlanResult.Ready)
        val ready = plan as ContextPlanResult.Ready
        assertEquals(ModelLimitsValidator.MAX_CONTEXT_WINDOW, ready.report.contextWindow)
        assertTrue(ready.report.availableInputBudget > 0)
        assertTrue(ready.report.totalUsedInputTokens > 0)
    }

    @Test
    fun `file backed room database persists model context window and max output across close and reopen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = Files.createTempFile("folionote_model_persist_", ".db").toFile()

        try {
            val db1 = Room.databaseBuilder(context, FolioNoteDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()
            try {
                db1.providerAndModelDao().upsertProvider(
                    ProviderEntity(id = "p_persist", name = "Local Mock", baseUrl = "https://example.invalid/v1")
                )
                db1.providerAndModelDao().upsertModel(
                    ModelEntity(
                        id = "m_persist",
                        providerId = "p_persist",
                        modelId = "gpt-test",
                        displayName = "GPT Test",
                        contextWindow = 8192,
                        maxOutputTokens = 2048
                    )
                )
                val stored = db1.providerAndModelDao().getModelByInternalId("m_persist")!!
                db1.providerAndModelDao().upsertModel(
                    stored.copy(contextWindow = 128000, maxOutputTokens = 8192)
                )
            } finally {
                db1.close()
            }

            val db2 = Room.databaseBuilder(context, FolioNoteDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()
            try {
                val reopened = db2.providerAndModelDao().getModelByInternalId("m_persist")
                assertEquals(128000, reopened?.contextWindow)
                assertEquals(8192, reopened?.maxOutputTokens)
            } finally {
                db2.close()
            }
        } finally {
            dbFile.delete()
            dbFile.resolveSibling("${dbFile.name}-wal").delete()
            dbFile.resolveSibling("${dbFile.name}-shm").delete()
        }
    }

    @Test
    fun `chat orchestrator bounds history window paginates rag chunks and stops studio pagination at budget`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()
        val server = MockWebServer()
        server.start()

        try {
            val crypto = KeystoreCryptoManager()
            val sharedClient = SharedHttpClients.defaultClient
            val provider = ProviderEntity(
                id = "p_mem",
                name = "MockMem",
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                encryptedApiKey = crypto.encrypt("sk-mem-test"),
                supportsStreamingDefault = false
            )
            val model = ModelEntity(
                id = "m_mem",
                providerId = provider.id,
                modelId = "gpt-mem",
                displayName = "GPT Mem",
                contextWindow = 4096,
                maxOutputTokens = 1024,
                supportsStreaming = false,
                supportsTools = false
            )
            db.notebookDao().upsertNotebook(NotebookEntity("nb_mem", "Caderno Memória"))
            db.toolAndJobDao().upsertTools(ToolEngine.builtInTools())
            db.providerAndModelDao().upsertProvider(provider)
            db.providerAndModelDao().upsertModel(model)

            val source = SourceEntity(
                id = "src_mem",
                notebookId = "nb_mem",
                name = "Tratado Extenso.txt",
                mimeType = "text/plain",
                sourceType = SourceType.TXT.name,
                filePath = "",
                sizeBytes = 50_000L,
                fileHashSha256 = "hash_mem",
                processingStatus = SourceStatus.READY.name,
                isSelectedForChat = true
            )
            db.sourceDao().upsertSource(source)

            // 150 chunks spanning 3 pages of 64; relevant keyword placed in final page
            val chunks = (0 until 150).map { idx ->
                val text = if (idx == 145) {
                    "Protocolo Andromeda exige calibração térmica em 420 kelvin antes da ignição. ".repeat(4)
                } else {
                    "Capítulo genérico número $idx sobre administração documental e arquivos diversos. ".repeat(6)
                }
                DocumentChunkEntity(
                    id = "chunk_$idx",
                    sourceId = source.id,
                    notebookId = "nb_mem",
                    chunkIndex = idx,
                    pageNumber = (idx / 5) + 1,
                    content = text,
                    tokenEstimate = 80
                )
            }
            db.chunkAndEmbeddingDao().insertChunks(chunks)

            val embeddingProvider = CompositeEmbeddingProvider(
                OpenAiCompatibleEmbeddingProvider(crypto, sharedClient),
                GeminiEmbeddingProvider(crypto, sharedClient)
            )
            val orchestrator = ChatOrchestrator(
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

            val conv = orchestrator.createNewConversation("nb_mem", "Conversa Longa", model.id)
            // Seed 65 prior messages so bounded window (40) drops oldest 25 messages
            for (i in 1..65) {
                db.conversationAndMessageDao().upsertMessage(
                    MessageEntity(
                        id = "hist_$i",
                        conversationId = conv.id,
                        role = if (i % 2 == 1) MessageRole.USER.name else MessageRole.ASSISTANT.name,
                        content = "Turno histórico #$i apenas para compor contexto extenso.",
                        orderIndex = i
                    )
                )
            }

            // 1st HTTP call: updateProgressiveSummary (triggered because >4 messages are outside kept window)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"Resumo acumulado dos turnos anteriores."},"finish_reason":"stop"}]}""")
            )
            // 2nd HTTP call: main chat turn
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"Resposta com [1]."},"finish_reason":"stop"}]}""")
            )

            orchestrator.sendMessage(
                notebookId = "nb_mem",
                conversationId = conv.id,
                userText = "Qual temperatura o Protocolo Andromeda exige na ignição?",
                modelInternalId = model.id
            )

            val summaryReq = server.takeRequest()
            val summaryJson = JSONObject(summaryReq.body.readUtf8())
            assertTrue(
                summaryJson.getJSONArray("messages").getJSONObject(0).getString("content")
                    .contains("Atualize o resumo objetivo")
            )

            val chatReq = server.takeRequest()
            val chatJson = JSONObject(chatReq.body.readUtf8())
            val sentMessages = chatJson.getJSONArray("messages")
            val priorHistorySent = (0 until sentMessages.length())
                .map { sentMessages.getJSONObject(it).getString("content") }
                .count { it.startsWith("Turno histórico #") }

            assertTrue("Expected bounded history <= 40, was $priorHistorySent", priorHistorySent <= 40)
            val userPayload = sentMessages.getJSONObject(sentMessages.length() - 1).getString("content")
            assertTrue(
                "Expected RAG passage from page 3 to be retrieved on demand",
                userPayload.contains("420 kelvin")
            )

            // Also verify Studio artifact generation respects computeStudioBudgetChars
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"# Resumo\nConteúdo gerado."},"finish_reason":"stop"}]}""")
            )
            val studioResult = orchestrator.generateStudioArtifact(
                notebookId = "nb_mem",
                artifactType = "SUMMARY",
                promptTitle = "Resumo Executivo",
                instructionPrompt = "Resuma o material.",
                modelInternalId = model.id
            )
            assertTrue(studioResult.isSuccess)
            val studioReq = server.takeRequest()
            val studioJson = JSONObject(studioReq.body.readUtf8())
            val studioPrompt = studioJson.getJSONArray("messages")
                .let { it.getJSONObject(it.length() - 1).getString("content") }
            val expectedBudgetChars = ModelLimitsValidator.computeStudioBudgetChars(
                model.contextWindow,
                model.maxOutputTokens
            )
            assertTrue(
                "Expected Studio prompt to stop reading chunks around budgetChars ($expectedBudgetChars), length=${studioPrompt.length}",
                studioPrompt.length <= expectedBudgetChars + 512
            )
        } finally {
            prefs.clearAllPrefsForTest()
            runCatching { server.shutdown() }
            db.close()
        }
    }

    @Test
    fun `retrieveOnDemand hybrid scoring bounds loaded chunks via PriorityQueue and matches eager retrieve`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val source = SourceEntity(
                id = "src_rag",
                notebookId = "nb_rag",
                name = "Física.txt",
                mimeType = "text/plain",
                sourceType = SourceType.TXT.name,
                filePath = "",
                sizeBytes = 1024L,
                fileHashSha256 = "hash_rag",
                processingStatus = SourceStatus.READY.name,
                isSelectedForChat = true
            )
            db.notebookDao().upsertNotebook(NotebookEntity("nb_rag", "Física"))
            db.sourceDao().upsertSource(source)

            val chunks = (0 until 20).map { idx ->
                DocumentChunkEntity(
                    id = "c_$idx",
                    sourceId = source.id,
                    notebookId = "nb_rag",
                    chunkIndex = idx,
                    pageNumber = idx + 1,
                    content = if (idx == 17) "Supercondutividade de alta temperatura e efeito Meissner." else "Tópico diverso $idx sem relação direta.",
                    tokenEstimate = 20
                )
            }
            val embeddings = (0 until 20).map { idx ->
                val vec = if (idx == 17) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
                EmbeddingEntity(
                    id = "e_$idx",
                    chunkId = "c_$idx",
                    notebookId = "nb_rag",
                    sourceId = source.id,
                    embeddingProviderId = "p1",
                    embeddingModelId = "emb-1",
                    dimensions = 3,
                    vectorBlob = VectorCodec.floatArrayToBytes(vec)
                )
            }
            db.chunkAndEmbeddingDao().insertChunks(chunks)
            db.chunkAndEmbeddingDao().insertEmbeddings(embeddings)

            var loadedByIdCount = 0
            val retriever = HybridRetriever()
            val passages = retriever.retrieveOnDemand(
                query = "efeito Meissner supercondutividade",
                sources = listOf(source),
                queryEmbedding = floatArrayOf(1f, 0f, 0f),
                topK = 3,
                pageSize = 6,
                loadChunkPage = { limit, offset ->
                    db.chunkAndEmbeddingDao().getChunksPageForSources(listOf(source.id), limit, offset)
                },
                loadEmbeddingPage = { limit, offset ->
                    db.chunkAndEmbeddingDao().getEmbeddingsPageForSources(listOf(source.id), "emb-1", 3, limit, offset)
                },
                loadChunksByIds = { ids ->
                    loadedByIdCount = ids.size
                    db.chunkAndEmbeddingDao().getChunksByIds(ids)
                }
            )

            assertTrue(passages.isNotEmpty())
            assertEquals("c_17", passages.first().chunk.id)
            assertTrue("Should only materialize candidate chunks by ID, got $loadedByIdCount", loadedByIdCount < chunks.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `updater defaults reuse SharedHttpClients singleton and user toggle disables automatic update check`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()

        try {
            val defaultService = GitHubReleaseService(preferencesStore = prefs)
            val defaultManager = AppUpdateManager(context = context, releaseService = defaultService)

            val serviceClientField = GitHubReleaseService::class.java.getDeclaredField("baseHttpClient").apply {
                isAccessible = true
            }
            val managerClientField = AppUpdateManager::class.java.getDeclaredField("baseHttpClient").apply {
                isAccessible = true
            }
            assertSame(SharedHttpClients.defaultClient, serviceClientField.get(defaultService) as OkHttpClient)
            assertSame(SharedHttpClients.defaultClient, managerClientField.get(defaultManager) as OkHttpClient)
            assertEquals(16, SharedHttpClients.defaultClient.dispatcher.maxRequests)
            assertEquals(5, SharedHttpClients.defaultClient.dispatcher.maxRequestsPerHost)

            // Default is enabled -> due when never checked
            assertTrue(defaultService.isAutomaticCheckDue())

            // User turns off automatic update checks -> never auto-runs
            prefs.setAutoCheckUpdatesEnabled(false)
            assertFalse(prefs.settingsFlow.first().autoCheckUpdatesEnabled)
            assertFalse(defaultService.isAutomaticCheckDue())

            // Export & re-import non-secret preferences preserves the toggle
            val exported = prefs.exportNonSecretPreferencesJson()
            prefs.setAutoCheckUpdatesEnabled(true)
            assertTrue(defaultService.isAutomaticCheckDue())
            prefs.importNonSecretPreferencesJson(exported)
            assertFalse(prefs.settingsFlow.first().autoCheckUpdatesEnabled)
            assertFalse(defaultService.isAutomaticCheckDue())
        } finally {
            prefs.clearAllPrefsForTest()
        }
    }

    @Test
    fun `drive sync worker skips periodic sync when disconnected disabled or without pending local changes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val syncDao = db.syncDao()
            val disconnectedSettings = AppSettings(driveAccountEmail = null, driveEncryptedAccessToken = "", driveSyncEnabled = true)
            val syncDisabledSettings = AppSettings(driveAccountEmail = "u@example.com", driveEncryptedAccessToken = "enc", driveSyncEnabled = false)
            val connectedSettings = AppSettings(driveAccountEmail = "u@example.com", driveEncryptedAccessToken = "enc", driveSyncEnabled = true)

            // 1. Disconnected or disabled -> skipped immediately
            assertTrue(
                DriveSyncWorker.shouldSkipSyncWork(
                    settings = disconnectedSettings,
                    mode = DriveSyncWorker.MODE_SYNC,
                    isManualRetry = false,
                    hasPendingLocalChanges = { true }
                )
            )
            assertTrue(
                DriveSyncWorker.shouldSkipSyncWork(
                    settings = syncDisabledSettings,
                    mode = DriveSyncWorker.MODE_SYNC,
                    isManualRetry = false,
                    hasPendingLocalChanges = { true }
                )
            )

            // 2. Empty clean DB -> no pending local changes -> skipped
            assertFalse(syncDao.hasPendingLocalChanges())
            assertTrue(
                DriveSyncWorker.shouldSkipSyncWork(
                    settings = connectedSettings,
                    mode = DriveSyncWorker.MODE_SYNC,
                    isManualRetry = false,
                    hasPendingLocalChanges = { syncDao.hasPendingLocalChanges() }
                )
            )

            // 3. New notebook without SyncRecord -> hasPendingLocalChanges = true -> runs
            val t1 = 1_700_000_000_000L
            db.notebookDao().upsertNotebook(NotebookEntity(id = "nb_sync", title = "Caderno", createdAt = t1, updatedAt = t1))
            assertTrue(syncDao.hasPendingLocalChanges())
            assertFalse(
                DriveSyncWorker.shouldSkipSyncWork(
                    settings = connectedSettings,
                    mode = DriveSyncWorker.MODE_SYNC,
                    isManualRetry = false,
                    hasPendingLocalChanges = { syncDao.hasPendingLocalChanges() }
                )
            )

            // 4. Clean matching SyncRecord -> hasPendingLocalChanges = false -> skipped again
            syncDao.upsertSyncRecord(
                SyncRecordEntity(
                    recordKey = DriveSyncEngine.recordKey(DriveSyncEngine.ENTITY_NOTEBOOK, "nb_sync"),
                    entityType = DriveSyncEngine.ENTITY_NOTEBOOK,
                    entityId = "nb_sync",
                    revision = 1L,
                    contentHash = "abc",
                    localUpdatedAt = t1,
                    lastSyncedAt = t1,
                    remoteDriveFileId = "remote_1",
                    isDirty = false
                )
            )
            assertFalse(syncDao.hasPendingLocalChanges())
            assertTrue(
                DriveSyncWorker.shouldSkipSyncWork(
                    settings = connectedSettings,
                    mode = DriveSyncWorker.MODE_SYNC,
                    isManualRetry = false,
                    hasPendingLocalChanges = { syncDao.hasPendingLocalChanges() }
                )
            )

            // 5. Pending tombstone triggers sync
            syncDao.upsertTombstone(
                SyncTombstoneEntity(
                    id = "tomb_1",
                    entityType = DriveSyncEngine.ENTITY_NOTEBOOK,
                    entityId = "nb_deleted",
                    deletedAt = t1 + 1000L,
                    revision = 2L,
                    syncedToRemote = false
                )
            )
            assertTrue(syncDao.hasPendingLocalChanges())
            assertFalse(
                DriveSyncWorker.shouldSkipSyncWork(
                    settings = connectedSettings,
                    mode = DriveSyncWorker.MODE_SYNC,
                    isManualRetry = false,
                    hasPendingLocalChanges = { syncDao.hasPendingLocalChanges() }
                )
            )
        } finally {
            db.close()
        }
    }
}
