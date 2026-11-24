package com.sjos.folionote.engine.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.preferences.OcrProviderType
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class OcrExtractionSummary(
    val pages: List<ExtractedPage>,
    val failedPages: Map<Int, String> = emptyMap(),
    val providerUsed: String = ""
) {
    val isPartialFailure: Boolean
        get() = failedPages.isNotEmpty() && pages.any { it.text.isNotBlank() }
}

class OcrNotConfiguredException(message: String) : IllegalStateException(message)

interface OcrEngine {
    suspend fun extractText(file: File): Result<List<ExtractedPage>>
    suspend fun extractTextWithReport(file: File, sourceType: SourceType): Result<OcrExtractionSummary> {
        return extractText(file).map { pages ->
            OcrExtractionSummary(pages = pages, failedPages = emptyMap(), providerUsed = "OCR")
        }
    }
}

interface PdfPageRenderer {
    fun renderPdfPagesToPngBytes(file: File): List<Pair<Int, ByteArray>>
    fun loadImageAsPngBytes(file: File): ByteArray
}

class AndroidPdfPageRenderer(
    private val maxDimensionPx: Int = 1800
) : PdfPageRenderer {

    override fun renderPdfPagesToPngBytes(file: File): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val count = renderer.pageCount
                for (index in 0 until count) {
                    renderer.openPage(index).use { page ->
                        val scale = (maxDimensionPx.toFloat() / max(page.width, page.height).coerceAtLeast(1))
                            .coerceIn(0.5f, 2.0f)
                        val targetWidth = (page.width * scale).toInt().coerceAtLeast(1)
                        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result.add((index + 1) to bitmapToPngBytes(bitmap))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        return result
    }

    override fun loadImageAsPngBytes(file: File): ByteArray {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxDimensionPx || boundsOpts.outHeight / sampleSize > maxDimensionPx) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: throw IllegalStateException("Não foi possível decodificar o arquivo de imagem para OCR.")
        return try {
            bitmapToPngBytes(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        return out.toByteArray()
    }
}

/**
 * Decoupled external OCR engine supporting:
 * - Google Cloud Vision (DOCUMENT_TEXT_DETECTION)
 * - OpenAI-compatible Vision endpoints (multimodal chat completions)
 * - Custom REST OCR endpoints
 * Uses zero local AI models or bundled model weights.
 */
class ConfigurableExternalOcrEngine(
    private val preferencesStore: AppPreferencesStore,
    private val cryptoManager: KeystoreCryptoManager,
    private val baseHttpClient: OkHttpClient = OkHttpClient(),
    private val pageRenderer: PdfPageRenderer = AndroidPdfPageRenderer()
) : OcrEngine {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun extractText(file: File): Result<List<ExtractedPage>> {
        val guessedType = if (file.name.lowercase().endsWith(".pdf")) SourceType.PDF else SourceType.IMAGE
        return extractTextWithReport(file, guessedType).map { it.pages }
    }

    override suspend fun extractTextWithReport(
        file: File,
        sourceType: SourceType
    ): Result<OcrExtractionSummary> = withContext(Dispatchers.IO) {
        val settings = preferencesStore.settingsFlow.first()
        if (!settings.hasOcrConfigured) {
            return@withContext Result.failure(
                OcrNotConfiguredException(
                    "Provedor de OCR externo não configurado. Informe a chave/endpoint de OCR nas Configurações para processar documentos digitalizados ou imagens."
                )
            )
        }

        val apiKey = cryptoManager.decrypt(settings.ocrEncryptedApiKey).trim()
        val pageImages: List<Pair<Int, ByteArray>> = runCatching {
            when (sourceType) {
                SourceType.PDF -> pageRenderer.renderPdfPagesToPngBytes(file)
                SourceType.IMAGE -> listOf(1 to pageRenderer.loadImageAsPngBytes(file))
                else -> return@withContext Result.failure(
                    UnsupportedOperationException("OCR aplicável apenas a PDFs e imagens.")
                )
            }
        }.getOrElse { err ->
            return@withContext Result.failure(err)
        }

        if (pageImages.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Nenhuma página renderizada para OCR."))
        }

        val extractedPages = mutableListOf<ExtractedPage>()
        val failedPages = linkedMapOf<Int, String>()

        for ((pageNum, pngBytes) in pageImages) {
            val res = runSinglePageOcr(
                settings = settings,
                plainApiKey = apiKey,
                pageNumber = pageNum,
                pngBytes = pngBytes
            )
            res.onSuccess { text ->
                extractedPages.add(ExtractedPage(pageNumber = pageNum, text = text.trim()))
            }.onFailure { err ->
                failedPages[pageNum] = err.message ?: "Erro desconhecido na página $pageNum"
                extractedPages.add(ExtractedPage(pageNumber = pageNum, text = ""))
            }
        }

        if (failedPages.size == pageImages.size) {
            val firstError = failedPages.values.firstOrNull() ?: "Falha no OCR externo."
            return@withContext Result.failure(IllegalStateException("Falha no OCR em todas as páginas: $firstError"))
        }

        Result.success(
            OcrExtractionSummary(
                pages = extractedPages,
                failedPages = failedPages,
                providerUsed = settings.ocrProviderType.label
            )
        )
    }

    internal fun runSinglePageOcr(
        settings: AppSettings,
        plainApiKey: String,
        pageNumber: Int,
        pngBytes: ByteArray
    ): Result<String> = runCatching {
        val b64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
        val endpoint = settings.ocrEndpointUrl.trim()
        require(endpoint.isNotBlank()) { "Endpoint OCR em branco." }

        val client = baseHttpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val reqBuilder = Request.Builder().url(endpoint)
        val bodyJson: JSONObject = when (settings.ocrProviderType) {
            OcrProviderType.GOOGLE_CLOUD_VISION -> {
                if (plainApiKey.isNotBlank()) {
                    reqBuilder.addHeader("X-Goog-Api-Key", plainApiKey)
                }
                JSONObject().put(
                    "requests",
                    JSONArray().put(
                        JSONObject()
                            .put("image", JSONObject().put("content", b64))
                            .put(
                                "features",
                                JSONArray().put(JSONObject().put("type", "DOCUMENT_TEXT_DETECTION"))
                            )
                    )
                )
            }
            OcrProviderType.OPENAI_VISION_COMPAT -> {
                if (plainApiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $plainApiKey")
                }
                val modelId = settings.ocrModelId.ifBlank { "gpt-4o-mini" }
                JSONObject()
                    .put("model", modelId)
                    .put("temperature", 0.0)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put(
                                    "content",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put("type", "text")
                                                .put("text", "Transcreva fielmente todo o texto visível nesta imagem mantendo a estrutura em Markdown. Não adicione comentários.")
                                        )
                                        .put(
                                            JSONObject()
                                                .put("type", "image_url")
                                                .put("image_url", JSONObject().put("url", "data:image/png;base64,$b64"))
                                        )
                                )
                        )
                    )
            }
            OcrProviderType.CUSTOM_OCR_ENDPOINT -> {
                if (plainApiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $plainApiKey")
                }
                JSONObject()
                    .put("page", pageNumber)
                    .put("mime_type", "image/png")
                    .put("image_base64", b64)
                    .apply {
                        if (settings.ocrModelId.isNotBlank()) put("model", settings.ocrModelId)
                    }
            }
        }

        val request = reqBuilder
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("OCR HTTP ${response.code}: ${raw.take(220)}")
            }
            parseOcrResponse(settings.ocrProviderType, raw)
        }
    }

    internal fun parseOcrResponse(providerType: OcrProviderType, raw: String): String {
        val root = JSONObject(raw)
        return when (providerType) {
            OcrProviderType.GOOGLE_CLOUD_VISION -> {
                val responses = root.optJSONArray("responses")
                val first = responses?.optJSONObject(0)
                    ?: throw IllegalStateException("Resposta Google Vision sem 'responses'.")
                first.optJSONObject("error")?.let { err ->
                    throw IllegalStateException(err.optString("message", "Erro Google Vision"))
                }
                val full = first.optJSONObject("fullTextAnnotation")?.optString("text").orEmpty()
                if (full.isNotBlank()) full else {
                    first.optJSONArray("textAnnotations")?.optJSONObject(0)?.optString("description").orEmpty()
                }
            }
            OcrProviderType.OPENAI_VISION_COMPAT -> {
                val choices = root.optJSONArray("choices")
                val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                msg?.optString("content").orEmpty()
            }
            OcrProviderType.CUSTOM_OCR_ENDPOINT -> {
                root.optString("text").ifBlank {
                    root.optString("content").ifBlank {
                        root.optString("markdown")
                    }
                }
            }
        }
    }
}
