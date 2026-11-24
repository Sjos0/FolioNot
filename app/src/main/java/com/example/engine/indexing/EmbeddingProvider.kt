package com.example.engine.indexing

import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.ModelEntity
import com.example.data.local.ProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
        texts: List<String>
    ): Result<EmbeddingBatchResult>
}

class OpenAiCompatibleEmbeddingProvider(
    private val cryptoManager: KeystoreCryptoManager,
    private val baseHttpClient: OkHttpClient = OkHttpClient()
) : EmbeddingProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun embedTexts(
        provider: ProviderEntity,
        model: ModelEntity,
        texts: List<String>
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

        val decryptedKey = cryptoManager.decrypt(provider.encryptedApiKey).trim()
        if (decryptedKey.isNotEmpty() && provider.authHeaderName.isNotBlank()) {
            val headerVal = if (provider.authPrefix.isNotBlank()) {
                "${provider.authPrefix.trim()} $decryptedKey"
            } else {
                decryptedKey
            }
            reqBuilder.addHeader(provider.authHeaderName.trim(), headerVal)
        }

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
                EmbeddingBatchResult(vectors = sorted, dimensions = dims)
            }
        }
    }
}
