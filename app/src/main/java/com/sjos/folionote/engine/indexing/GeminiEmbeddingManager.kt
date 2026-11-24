package com.sjos.folionote.engine.indexing

import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.ProviderAndModelDao
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.ProviderType
import com.sjos.folionote.engine.ai.ConnectionTestResult
import okhttp3.OkHttpClient

data class ActiveEmbeddingSpec(
    val provider: ProviderEntity,
    val model: ModelEntity,
    val isDedicatedGemini: Boolean
)

/**
 * Dedicated controller for Gemini Embedding 2 user-key management, validation,
 * dimension verification, and strict isolation from generative chat providers.
 */
class GeminiEmbeddingManager(
    private val preferencesStore: AppPreferencesStore,
    private val cryptoManager: KeystoreCryptoManager,
    private val embeddingProvider: EmbeddingProvider,
    private val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
) {
    companion object {
        const val DEDICATED_PROVIDER_ID = "dedicated_gemini_embedding_2"
        const val DEFAULT_MODEL_ID = "gemini-embedding-2-preview"
        val SUPPORTED_MODELS = listOf(
            "gemini-embedding-2-preview",
            "gemini-embedding-001"
        )
        val SUPPORTED_DIMENSIONS = listOf(768, 1536, 3072)
    }

    fun getMaskedKey(settings: AppSettings): String {
        val plain = cryptoManager.decrypt(settings.geminiEmbeddingEncryptedKey)
        return cryptoManager.maskKey(plain)
    }

    suspend fun saveAndValidateKey(
        rawApiKey: String,
        modelId: String = DEFAULT_MODEL_ID,
        dimensions: Int = 768,
        enableAfterValidation: Boolean = true,
        baseUrlOverride: String = defaultBaseUrl
    ): ConnectionTestResult {
        val trimmedKey = rawApiKey.trim()
        if (trimmedKey.isBlank()) {
            return ConnectionTestResult(false, 0L, "Informe uma chave da API do Google AI Studio.")
        }
        val cleanModel = modelId.trim().ifBlank { DEFAULT_MODEL_ID }
        val cleanDims = dimensions.coerceIn(128, 3072)
        val encrypted = cryptoManager.encrypt(trimmedKey)

        val testResult = testEncryptedKey(
            encryptedKey = encrypted,
            modelId = cleanModel,
            dimensions = cleanDims,
            baseUrlOverride = baseUrlOverride
        )
        if (testResult.success) {
            preferencesStore.saveGeminiEmbeddingConfig(
                enabled = enableAfterValidation,
                modelId = cleanModel,
                dimensions = cleanDims,
                encryptedKey = encrypted,
                validatedAt = System.currentTimeMillis()
            )
        }
        return testResult
    }

    suspend fun saveKeyWithoutRemoteTest(
        rawApiKey: String,
        modelId: String,
        dimensions: Int,
        enabled: Boolean
    ) {
        val trimmed = rawApiKey.trim()
        val encrypted = if (trimmed.isNotEmpty()) cryptoManager.encrypt(trimmed) else null
        preferencesStore.saveGeminiEmbeddingConfig(
            enabled = enabled,
            modelId = modelId.trim().ifBlank { DEFAULT_MODEL_ID },
            dimensions = dimensions.coerceIn(128, 3072),
            encryptedKey = encrypted,
            validatedAt = null
        )
    }

    suspend fun testStoredKey(
        settings: AppSettings,
        baseUrlOverride: String = defaultBaseUrl
    ): ConnectionTestResult {
        if (settings.geminiEmbeddingEncryptedKey.isBlank()) {
            return ConnectionTestResult(false, 0L, "Nenhuma chave do Gemini Embedding 2 configurada.")
        }
        val res = testEncryptedKey(
            encryptedKey = settings.geminiEmbeddingEncryptedKey,
            modelId = settings.geminiEmbeddingModelId,
            dimensions = settings.geminiEmbeddingDimensions,
            baseUrlOverride = baseUrlOverride
        )
        if (res.success) {
            preferencesStore.saveGeminiEmbeddingConfig(
                enabled = settings.geminiEmbeddingEnabled,
                modelId = settings.geminiEmbeddingModelId,
                dimensions = settings.geminiEmbeddingDimensions,
                encryptedKey = null,
                validatedAt = System.currentTimeMillis()
            )
        }
        return res
    }

    suspend fun testEncryptedKey(
        encryptedKey: String,
        modelId: String,
        dimensions: Int,
        baseUrlOverride: String = defaultBaseUrl
    ): ConnectionTestResult {
        val spec = buildDedicatedSpec(
            encryptedKey = encryptedKey,
            modelId = modelId,
            dimensions = dimensions,
            baseUrl = baseUrlOverride
        )
        val start = System.currentTimeMillis()
        val result = embeddingProvider.embedTexts(
            provider = spec.provider,
            model = spec.model,
            texts = listOf("FolioNote connectivity check"),
            taskType = EmbeddingTaskType.QUERY
        )
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        return result.fold(
            onSuccess = { batch ->
                val vec = batch.vectors.firstOrNull()
                when {
                    vec == null || vec.isEmpty() -> {
                        ConnectionTestResult(false, elapsed, "Resposta sem vetor válido retornado pelo endpoint.")
                    }
                    batch.dimensions != dimensions -> {
                        ConnectionTestResult(
                            false,
                            elapsed,
                            "Dimensão incompatível: esperado $dimensions, recebido ${batch.dimensions}."
                        )
                    }
                    else -> {
                        ConnectionTestResult(
                            true,
                            elapsed,
                            "Gemini Embedding 2 validado (${batch.dimensions}d) em ${elapsed}ms."
                        )
                    }
                }
            },
            onFailure = { err ->
                ConnectionTestResult(
                    false,
                    elapsed,
                    err.message ?: "Falha ao conectar com Gemini Embedding 2."
                )
            }
        )
    }

    suspend fun removeKey() {
        preferencesStore.clearGeminiEmbeddingKey()
    }

    suspend fun setEnabled(settings: AppSettings, enabled: Boolean) {
        preferencesStore.saveGeminiEmbeddingConfig(
            enabled = enabled && settings.hasGeminiEmbeddingKey,
            modelId = settings.geminiEmbeddingModelId,
            dimensions = settings.geminiEmbeddingDimensions,
            encryptedKey = null,
            validatedAt = settings.geminiEmbeddingLastValidatedAt
        )
    }

    suspend fun updateModelAndDimensions(settings: AppSettings, modelId: String, dimensions: Int) {
        preferencesStore.saveGeminiEmbeddingConfig(
            enabled = settings.geminiEmbeddingEnabled,
            modelId = modelId,
            dimensions = dimensions,
            encryptedKey = null,
            validatedAt = settings.geminiEmbeddingLastValidatedAt
        )
    }

    fun buildDedicatedSpec(
        encryptedKey: String,
        modelId: String = DEFAULT_MODEL_ID,
        dimensions: Int = 768,
        baseUrl: String = defaultBaseUrl
    ): ActiveEmbeddingSpec {
        val cleanModel = modelId.trim().ifBlank { DEFAULT_MODEL_ID }
        val cleanDims = dimensions.coerceIn(128, 3072)
        val provider = ProviderEntity(
            id = DEDICATED_PROVIDER_ID,
            name = "Gemini Embedding 2",
            baseUrl = baseUrl.trim().trimEnd('/'),
            chatPath = "",
            modelsPath = "/models",
            embeddingsPath = "/models/{model}:batchEmbedContents",
            authHeaderName = "x-goog-api-key",
            authPrefix = "",
            encryptedApiKey = encryptedKey,
            timeoutSeconds = 60,
            supportsStreamingDefault = false,
            providerType = ProviderType.GEMINI_NATIVE_EMBEDDING.name,
            isEnabled = true
        )
        val model = ModelEntity(
            id = "dedicated_$cleanModel",
            providerId = DEDICATED_PROVIDER_ID,
            modelId = cleanModel,
            displayName = "Gemini Embedding 2 ($cleanModel)",
            contextWindow = 8192,
            maxOutputTokens = 1,
            supportsStreaming = false,
            supportsTools = false,
            supportsSystemMessage = false,
            enabled = true,
            isEmbeddingModel = true,
            embeddingDimensions = cleanDims
        )
        return ActiveEmbeddingSpec(provider = provider, model = model, isDedicatedGemini = true)
    }

    suspend fun resolveActiveEmbeddingSpec(
        settings: AppSettings,
        providerDao: ProviderAndModelDao,
        baseUrlOverride: String = defaultBaseUrl
    ): ActiveEmbeddingSpec? {
        if (settings.isGeminiEmbeddingActive) {
            return buildDedicatedSpec(
                encryptedKey = settings.geminiEmbeddingEncryptedKey,
                modelId = settings.geminiEmbeddingModelId,
                dimensions = settings.geminiEmbeddingDimensions,
                baseUrl = baseUrlOverride
            )
        }
        val customModelId = settings.activeEmbeddingModelId ?: return null
        val model = providerDao.getModelByInternalId(customModelId) ?: return null
        val provider = providerDao.getProviderById(model.providerId) ?: return null
        if (!model.enabled || !provider.isEnabled || !model.isEmbeddingModel) return null
        return ActiveEmbeddingSpec(provider = provider, model = model, isDedicatedGemini = false)
    }
}
