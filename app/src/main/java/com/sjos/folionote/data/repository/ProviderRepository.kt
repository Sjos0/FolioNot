package com.sjos.folionote.data.repository

import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.ProviderType
import com.sjos.folionote.engine.ai.AiProviderClient
import com.sjos.folionote.engine.ai.ConnectionTestResult
import com.sjos.folionote.engine.ai.DiscoveredModel
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ProviderRepository(
    db: FolioNoteDatabase,
    private val aiClient: AiProviderClient,
    private val cryptoManager: KeystoreCryptoManager
) {
    private val dao = db.providerAndModelDao()

    val allProviders: Flow<List<ProviderEntity>> = dao.observeAllProviders()
    val allModels: Flow<List<ModelEntity>> = dao.observeAllModels()
    val enabledChatModels: Flow<List<ModelEntity>> = dao.observeEnabledChatModels()
    val enabledEmbeddingModels: Flow<List<ModelEntity>> = dao.observeEnabledEmbeddingModels()

    fun observeModelsForProvider(providerId: String): Flow<List<ModelEntity>> =
        dao.observeModelsForProvider(providerId)

    suspend fun getProvider(providerId: String): ProviderEntity? = dao.getProviderById(providerId)
    suspend fun getModel(internalId: String): ModelEntity? = dao.getModelByInternalId(internalId)

    suspend fun saveProvider(
        existingId: String?,
        name: String,
        baseUrl: String,
        chatPath: String,
        modelsPath: String,
        embeddingsPath: String,
        authHeaderName: String,
        authPrefix: String,
        plainApiKeyInput: String,
        keepExistingKeyIfBlank: Boolean,
        extraHeadersJson: String,
        defaultModelId: String?,
        timeoutSeconds: Int,
        supportsStreamingDefault: Boolean,
        extraParamsJson: String,
        providerType: String,
        isEnabled: Boolean
    ): ProviderEntity {
        val existing = existingId?.let { dao.getProviderById(it) }
        val encryptedKey = when {
            plainApiKeyInput.isNotBlank() -> cryptoManager.encrypt(plainApiKeyInput.trim())
            keepExistingKeyIfBlank && existing != null -> existing.encryptedApiKey
            else -> ""
        }
        val defaultEmbPath = if (providerType == ProviderType.GEMINI_NATIVE_EMBEDDING.name) {
            "/models/{model}:batchEmbedContents"
        } else {
            "/embeddings"
        }
        val now = System.currentTimeMillis()
        val entity = ProviderEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Provedor" },
            baseUrl = baseUrl.trim(),
            chatPath = chatPath.trim().ifBlank { "/chat/completions" },
            modelsPath = modelsPath.trim().ifBlank { "/models" },
            embeddingsPath = embeddingsPath.trim().ifBlank { defaultEmbPath },
            authHeaderName = authHeaderName.trim(),
            authPrefix = authPrefix.trim(),
            encryptedApiKey = encryptedKey,
            extraHeadersJson = extraHeadersJson.trim().ifBlank { "{}" },
            defaultModelId = defaultModelId?.trim()?.takeIf { it.isNotBlank() },
            timeoutSeconds = timeoutSeconds.coerceIn(5, 300),
            supportsStreamingDefault = supportsStreamingDefault,
            extraParamsJson = extraParamsJson.trim().ifBlank { "{}" },
            providerType = providerType,
            isEnabled = isEnabled,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        dao.upsertProvider(entity)
        return entity
    }

    suspend fun duplicateProvider(provider: ProviderEntity): ProviderEntity {
        val now = System.currentTimeMillis()
        val copy = provider.copy(
            id = UUID.randomUUID().toString(),
            name = "${provider.name} (Cópia)",
            createdAt = now,
            updatedAt = now
        )
        dao.upsertProvider(copy)
        return copy
    }

    suspend fun toggleProviderEnabled(provider: ProviderEntity) {
        dao.upsertProvider(provider.copy(isEnabled = !provider.isEnabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProvider(providerId: String) {
        dao.deleteProviderById(providerId)
    }

    suspend fun clearProviderKey(providerId: String) {
        dao.clearProviderApiKey(providerId)
    }

    suspend fun clearAllKeys() {
        dao.clearAllProviderApiKeys()
    }

    fun getMaskedKey(provider: ProviderEntity): String {
        val plain = cryptoManager.decrypt(provider.encryptedApiKey)
        return cryptoManager.maskKey(plain)
    }

    suspend fun testConnection(provider: ProviderEntity): ConnectionTestResult {
        return aiClient.testConnection(provider)
    }

    suspend fun fetchModelsFromEndpoint(provider: ProviderEntity): Result<List<DiscoveredModel>> {
        return aiClient.fetchModels(provider)
    }

    /**
     * Adds selected discovered models to local library while preserving existing custom configurations
     * (contextWindow, maxOutputTokens, temperature, supportsTools, etc.) if the modelId already exists.
     */
    suspend fun addDiscoveredModels(providerId: String, discovered: List<DiscoveredModel>) {
        val existingByModelId = dao.getModelsForProviderOnce(providerId).associateBy { it.modelId }
        val toSave = mutableListOf<ModelEntity>()
        val now = System.currentTimeMillis()

        for (item in discovered.distinctBy { it.modelId }) {
            val existing = existingByModelId[item.modelId]
            if (existing != null) {
                toSave.add(
                    existing.copy(
                        displayName = existing.displayName.ifBlank { item.displayName },
                        updatedAt = now
                    )
                )
            } else {
                val isEmb = item.modelId.contains("embed", ignoreCase = true)
                val guessedContext = (item.contextLengthHint ?: 8192).coerceIn(
                    com.sjos.folionote.engine.ai.ModelLimitsValidator.MIN_CONTEXT_WINDOW,
                    com.sjos.folionote.engine.ai.ModelLimitsValidator.MAX_CONTEXT_WINDOW
                )
                toSave.add(
                    ModelEntity(
                        id = UUID.randomUUID().toString(),
                        providerId = providerId,
                        modelId = item.modelId,
                        displayName = item.displayName,
                        contextWindow = guessedContext,
                        maxOutputTokens = (guessedContext / 4).coerceIn(
                            com.sjos.folionote.engine.ai.ModelLimitsValidator.MIN_MAX_OUTPUT_TOKENS,
                            minOf(4096, guessedContext)
                        ),
                        supportsStreaming = !isEmb,
                        supportsTools = false,
                        supportsSystemMessage = true,
                        enabled = true,
                        isEmbeddingModel = isEmb,
                        embeddingDimensions = if (isEmb) 768 else null,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
        dao.upsertModels(toSave)
    }

    suspend fun saveModel(model: ModelEntity) {
        dao.upsertModel(model.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteModel(internalId: String) {
        dao.deleteModelByInternalId(internalId)
    }
}
