package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.FolioNoteDatabase
import com.example.data.local.ModelEntity
import com.example.data.local.NotebookEntity
import com.example.data.local.ProviderEntity
import com.example.data.repository.ProviderRepository
import com.example.engine.ai.AiProviderClient
import com.example.engine.ai.ChatCompletionRequest
import com.example.engine.ai.ChatStreamEvent
import com.example.engine.ai.ConnectionTestResult
import com.example.engine.ai.DiscoveredModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `read app name from context`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("FolioNote", appName)
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

        // Refresh discovery with same modelId -> must preserve 64000 contextWindow & supportsTools
        repo.addDiscoveredModels(
            provider.id,
            listOf(
                DiscoveredModel("gpt-4o-mini", "gpt-4o-mini", contextLengthHint = 8192),
                DiscoveredModel("gpt-4o-mini", "gpt-4o-mini") // duplicate should be deduplicated
            )
        )

        val saved = db.providerAndModelDao().getModelsForProviderOnce(provider.id)
        assertEquals(1, saved.size)
        assertEquals(64000, saved.first().contextWindow)
        assertEquals(4096, saved.first().maxOutputTokens)
        assertEquals(true, saved.first().supportsTools)
        assertNotNull(saved.first())
    }
}
