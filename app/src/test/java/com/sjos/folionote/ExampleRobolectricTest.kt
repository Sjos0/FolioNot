package com.sjos.folionote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.EmbeddingEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.VectorCodec
import com.sjos.folionote.data.repository.ProviderRepository
import com.sjos.folionote.engine.ai.AiProviderClient
import com.sjos.folionote.engine.ai.ChatCompletionRequest
import com.sjos.folionote.engine.ai.ChatStreamEvent
import com.sjos.folionote.engine.ai.ConnectionTestResult
import com.sjos.folionote.engine.ai.DiscoveredModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: FolioNoteDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read app name and definitive package from context`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("FolioNote", appName)
        assertEquals("com.sjos.folionote", context.packageName)
    }

    @Test
    fun `keystore crypto encrypts decrypts and masks sensitive keys`() {
        val crypto = KeystoreCryptoManager()
        val secret = "sk-proj-secret-987654321"
        val encrypted = crypto.encrypt(secret)
        assertTrue(encrypted.isNotBlank())
        assertNotEquals(secret, encrypted)
        assertEquals(secret, crypto.decrypt(encrypted))
        assertEquals("sk-p••••4321", crypto.maskKey(secret))
        assertEquals("••••••••", crypto.maskKey("short"))
    }

    @Test
    fun `room persists notebooks and preserves custom model limits on discovery refresh`() = runTest {
        db.notebookDao().upsertNotebook(
            NotebookEntity(id = "nb1", title = "Direito Constitucional", iconKey = "book")
        )
        val notebooks = db.notebookDao().observeAllNotebooks().first()
        assertEquals(1, notebooks.size)
        assertEquals("Direito Constitucional", notebooks.first().title)

        val crypto = KeystoreCryptoManager()
        val fakeClient = object : AiProviderClient {
            override suspend fun testConnection(provider: ProviderEntity) =
                ConnectionTestResult(true, 10, "OK", 1)

            override suspend fun fetchModels(provider: ProviderEntity) =
                Result.success(listOf(DiscoveredModel("gpt-4o-mini", "gpt-4o-mini")))

            override fun streamChatCompletion(
                provider: ProviderEntity,
                model: ModelEntity,
                request: ChatCompletionRequest
            ): Flow<ChatStreamEvent> = emptyFlow()
        }

        val repo = ProviderRepository(db, fakeClient, crypto)
        val provider = repo.saveProvider(
            existingId = null,
            name = "Meu Provedor",
            baseUrl = "https://api.exemplo.com/v1",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            plainApiKeyInput = "sk-secret-123456",
            keepExistingKeyIfBlank = true,
            extraHeadersJson = "{}",
            defaultModelId = null,
            timeoutSeconds = 60,
            supportsStreamingDefault = true,
            extraParamsJson = "{}",
            providerType = "OPENAI_COMPATIBLE",
            isEnabled = true
        )

        val customModel = ModelEntity(
            id = "m_custom",
            providerId = provider.id,
            modelId = "gpt-4o-mini",
            displayName = "Meu GPT Customizado",
            contextWindow = 64000,
            maxOutputTokens = 4096,
            supportsTools = true
        )
        repo.saveModel(customModel)

        repo.addDiscoveredModels(
            provider.id,
            listOf(
                DiscoveredModel("gpt-4o-mini", "gpt-4o-mini", contextLengthHint = 8192),
                DiscoveredModel("gpt-4o-mini", "gpt-4o-mini")
            )
        )

        val saved = db.providerAndModelDao().getModelsForProviderOnce(provider.id)
        assertEquals(1, saved.size)
        assertEquals(64000, saved.first().contextWindow)
        assertEquals(4096, saved.first().maxOutputTokens)
        assertEquals(true, saved.first().supportsTools)
        assertNotNull(saved.first())
    }

    @Test
    fun `room removes only incompatible embeddings for a notebook`() = runTest {
        db.notebookDao().upsertNotebook(NotebookEntity(id = "nb1", title = "Pesquisa"))
        db.sourceDao().upsertSource(
            SourceEntity("s1", "nb1", "doc.txt", "text/plain", "TXT", "/tmp/doc.txt", 100, "hash1")
        )
        db.chunkAndEmbeddingDao().insertChunks(
            listOf(
                DocumentChunkEntity("c1", "s1", "nb1", 0, "Secao", 1, 0, 20, "Texto base", 4)
            )
        )
        val vec768 = VectorCodec.floatArrayToBytes(FloatArray(4) { 0.5f })
        db.chunkAndEmbeddingDao().insertEmbeddings(
            listOf(
                EmbeddingEntity("e_old_model", "c1", "nb1", "s1", "p1", "old-embed", 4, vec768),
                EmbeddingEntity("e_old_dim", "c1", "nb1", "s1", "p1", "gemini-embedding-2-preview", 3, VectorCodec.floatArrayToBytes(FloatArray(3) { 0.5f })),
                EmbeddingEntity("e_current", "c1", "nb1", "s1", "p1", "gemini-embedding-2-preview", 4, vec768)
            )
        )

        db.chunkAndEmbeddingDao().deleteIncompatibleEmbeddingsForNotebook(
            notebookId = "nb1",
            embeddingModelId = "gemini-embedding-2-preview",
            dimensions = 4
        )

        val remaining = db.chunkAndEmbeddingDao().observeEmbeddingsForNotebook("nb1").first()
        assertEquals(1, remaining.size)
        assertEquals("e_current", remaining.first().id)
    }
}
