package com.sjos.folionote.engine.indexing

import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class EmbeddingTaskType {
    DOCUMENT,
    QUERY
}

data class EmbeddingBatchResult(
    val vectors: List<FloatArray>,
    val dimensions: Int
)

/**
 * Decoupled embedding provider contract.
 * Completely independent from generative chat models.
 */
interface EmbeddingProvider {
    suspend fun embedTexts(
        provider: ProviderEntity,
        model: ModelEntity,
        texts: List<String>,
        taskType: EmbeddingTaskType = EmbeddingTaskType.DOCUMENT
    ): Result<EmbeddingBatchResult>
}

internal fun applyProviderHeaders(
    builder: Request.Builder,
    provider: ProviderEntity,
    cryptoManager: KeystoreCryptoManager
) {
    val decryptedKey = cryptoManager.decrypt(provider.encryptedApiKey).trim()
    if (decryptedKey.isNotEmpty() && provider.authHeaderName.isNotBlank()) {
        val headerVal = if (provider.authPrefix.isNotBlank()) {
            "${provider.authPrefix.trim()} $decryptedKey"
        } else {
            decryptedKey
        }
        builder.addHeader(provider.authHeaderName.trim(), headerVal)
    }
    if (provider.extraHeadersJson.isNotBlank()) {
        runCatching {
            val headersObj = JSONObject(provider.extraHeadersJson)
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headersObj.optString(key, "")
                if (key.isNotBlank() && value.isNotBlank()) {
                    builder.header(key, value)
                }
            }
        }
    }
}

class OpenAiCompatibleEmbeddingProvider(
    private val cryptoManager: KeystoreCryptoManager,
    private val baseHttpClient: OkHttpClient = OkHttpClient()
) : EmbeddingProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun embedTexts(
        provider: ProviderEntity,
        model: ModelEntity,
        texts: List<String>,
        taskType: EmbeddingTaskType
    ): Result<EmbeddingBatchResult> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) {
            return@withContext Result.success(EmbeddingBatchResult(emptyList(), model.embeddingDimensions ?: 0))
        }

        val cleanBase = provider.baseUrl.trim().trimEnd('/')
        val cleanPath = provider.embeddingsPath.trim().let { if (it.startsWith("/")) it else "/$it" }
        val url = "$cleanBase$cleanPath"

        val bodyJson = JSONObject().apply {
            put("model", model.modelId)
            put("input", JSONArray(texts))
            model.embeddingDimensions?.takeIf { it > 0 }?.let { put("dimensions", it) }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
        applyProviderHeaders(reqBuilder, provider, cryptoManager)

        runCatching {
            val timeout = provider.timeoutSeconds.coerceIn(10, 180).toLong()
            val client = baseHttpClient.newBuilder()
                .readTimeout(timeout, TimeUnit.SECONDS)
                .build()

            client.newCall(reqBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Erro de embeddings HTTP ${response.code}: ${raw.take(200)}")
                }
                parseOpenAiEmbeddingsResponse(raw)
            }
        }
    }

    internal fun parseOpenAiEmbeddingsResponse(raw: String): EmbeddingBatchResult {
        val root = JSONObject(raw)
        val dataArr = root.optJSONArray("data")
            ?: throw IllegalStateException("Resposta de embeddings sem campo 'data'.")

        val indexed = mutableListOf<Pair<Int, FloatArray>>()
        for (i in 0 until dataArr.length()) {
            val item = dataArr.optJSONObject(i) ?: continue
            val idx = item.optInt("index", i)
            val embArr = item.optJSONArray("embedding") ?: continue
            val vec = FloatArray(embArr.length()) { j -> embArr.optDouble(j, 0.0).toFloat() }
            indexed.add(idx to vec)
        }

        val sorted = indexed.sortedBy { it.first }.map { it.second }
        val dims = sorted.firstOrNull()?.size ?: 0
        return EmbeddingBatchResult(vectors = sorted, dimensions = dims)
    }
}

/**
 * Dedicated adapter for native Google Gemini Embedding REST endpoints
 * (:embedContent and :batchEmbedContents), supporting taskType and outputDimensionality,
 * and sending credentials strictly in headers (never in URL query parameters).
 */
class GeminiEmbeddingProvider(
    private val cryptoManager: KeystoreCryptoManager,
    private val baseHttpClient: OkHttpClient = OkHttpClient()
) : EmbeddingProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun embedTexts(
        provider: ProviderEntity,
        model: ModelEntity,
        texts: List<String>,
        taskType: EmbeddingTaskType
    ): Result<EmbeddingBatchResult> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) {
            return@withContext Result.success(EmbeddingBatchResult(emptyList(), model.embeddingDimensions ?: 0))
        }

        val isSingle = texts.size == 1
        val url = resolveGeminiEndpointUrl(provider, model.modelId, isSingle)
        val bodyJson = if (isSingle) {
            buildSingleEmbedJson(model, texts.first(), taskType)
        } else {
            buildBatchEmbedJson(model, texts, taskType)
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))

        val effectiveProvider = if (provider.authHeaderName.isBlank()) {
            provider.copy(authHeaderName = "x-goog-api-key", authPrefix = "")
        } else {
            provider
        }
        applyProviderHeaders(reqBuilder, effectiveProvider, cryptoManager)

        runCatching {
            val timeout = provider.timeoutSeconds.coerceIn(10, 180).toLong()
            val client = baseHttpClient.newBuilder()
                .readTimeout(timeout, TimeUnit.SECONDS)
                .build()

            client.newCall(reqBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Erro Gemini Embedding HTTP ${response.code}: ${raw.take(220)}")
                }
                parseGeminiEmbeddingsResponse(raw, isSingle)
            }
        }
    }

    internal fun resolveGeminiEndpointUrl(
        provider: ProviderEntity,
        modelId: String,
        isSingle: Boolean
    ): String {
        val cleanModel = modelId.removePrefix("models/").trim()
        val cleanBase = provider.baseUrl.trim().trimEnd('/').removeSuffix("/openai")
        val action = if (isSingle) "embedContent" else "batchEmbedContents"
        val configuredPath = provider.embeddingsPath.trim()
        return if (configuredPath.contains("{model}")) {
            val replaced = configuredPath
                .replace("{model}", cleanModel)
                .replace(":batchEmbedContents", ":$action")
                .replace(":embedContent", ":$action")
            val norm = if (replaced.startsWith("/")) replaced else "/$replaced"
            "$cleanBase$norm"
        } else if (configuredPath.endsWith(":embedContent") || configuredPath.endsWith(":batchEmbedContents")) {
            val norm = if (configuredPath.startsWith("/")) configuredPath else "/$configuredPath"
            "$cleanBase$norm"
        } else {
            "$cleanBase/models/$cleanModel:$action"
        }
    }

    internal fun buildSingleEmbedJson(
        model: ModelEntity,
        text: String,
        taskType: EmbeddingTaskType
    ): JSONObject {
        val cleanModel = model.modelId.removePrefix("models/").trim()
        return JSONObject().apply {
            put("model", "models/$cleanModel")
            put("content", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
            put("taskType", mapTaskType(taskType))
            model.embeddingDimensions?.takeIf { it > 0 }?.let {
                put("outputDimensionality", it)
            }
        }
    }

    internal fun buildBatchEmbedJson(
        model: ModelEntity,
        texts: List<String>,
        taskType: EmbeddingTaskType
    ): JSONObject {
        val reqArr = JSONArray()
        for (txt in texts) {
            reqArr.put(buildSingleEmbedJson(model, txt, taskType))
        }
        return JSONObject().put("requests", reqArr)
    }

    internal fun parseGeminiEmbeddingsResponse(raw: String, isSingle: Boolean): EmbeddingBatchResult {
        val root = JSONObject(raw)
        val vectors = if (isSingle) {
            val embObj = root.optJSONObject("embedding")
                ?: throw IllegalStateException("Resposta Gemini Embedding sem campo 'embedding'.")
            val values = embObj.optJSONArray("values")
                ?: throw IllegalStateException("Resposta Gemini Embedding sem 'values'.")
            listOf(FloatArray(values.length()) { idx -> values.optDouble(idx, 0.0).toFloat() })
        } else {
            val arr = root.optJSONArray("embeddings")
                ?: throw IllegalStateException("Resposta batch Gemini Embedding sem 'embeddings'.")
            val list = mutableListOf<FloatArray>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val values = item.optJSONArray("values") ?: continue
                list.add(FloatArray(values.length()) { idx -> values.optDouble(idx, 0.0).toFloat() })
            }
            list
        }
        val dims = vectors.firstOrNull()?.size ?: 0
        return EmbeddingBatchResult(vectors = vectors, dimensions = dims)
    }

    private fun mapTaskType(taskType: EmbeddingTaskType): String = when (taskType) {
        EmbeddingTaskType.DOCUMENT -> "RETRIEVAL_DOCUMENT"
        EmbeddingTaskType.QUERY -> "RETRIEVAL_QUERY"
    }
}

class CompositeEmbeddingProvider(
    private val openAiProvider: OpenAiCompatibleEmbeddingProvider,
    private val geminiProvider: GeminiEmbeddingProvider
) : EmbeddingProvider {

    override suspend fun embedTexts(
        provider: ProviderEntity,
        model: ModelEntity,
        texts: List<String>,
        taskType: EmbeddingTaskType
    ): Result<EmbeddingBatchResult> {
        return selectDelegate(provider).embedTexts(provider, model, texts, taskType)
    }

    internal fun selectDelegate(provider: ProviderEntity): EmbeddingProvider {
        val isNativeGemini = provider.providerType == ProviderType.GEMINI_NATIVE_EMBEDDING.name ||
            provider.embeddingsPath.contains(":embedContent") ||
            provider.embeddingsPath.contains(":batchEmbedContents")
        return if (isNativeGemini) geminiProvider else openAiProvider
    }
}
