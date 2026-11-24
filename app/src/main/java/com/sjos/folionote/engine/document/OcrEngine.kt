package com.sjos.folionote.engine.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.preferences.OcrProviderType
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    suspend fun forEachPdfPagePng(
        file: File,
        onPage: suspend (pageNumber: Int, totalPages: Int, pngBytes: ByteArray) -> Unit
    ) {
        val rendered = renderPdfPagesToPngBytes(file)
        val total = rendered.size
        for ((pageNum, bytes) in rendered) {
            currentCoroutineContext().ensureActive()
            onPage(pageNum, total, bytes)
        }
    }

    suspend fun forEachPdfPageBitmap(
        file: File,
        onPage: suspend (pageNumber: Int, totalPages: Int, bitmap: Bitmap) -> Unit
    ) {
        forEachPdfPagePng(file) { pageNum, total, bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Não foi possível decodificar a página $pageNum para OCR.")
            try {
                onPage(pageNum, total, bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    suspend fun <T> withDecodedImageBitmap(
        file: File,
        block: suspend (bitmap: Bitmap, rotationDegrees: Int) -> T
    ): T {
        val bytes = loadImageAsPngBytes(file)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Não foi possível decodificar o arquivo de imagem para OCR.")
        return try {
            block(bitmap, 0)
        } finally {
            bitmap.recycle()
        }
    }
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
                        val bitmap = renderPageToBitmap(page)
                        try {
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

    override suspend fun forEachPdfPagePng(
        file: File,
        onPage: suspend (pageNumber: Int, totalPages: Int, pngBytes: ByteArray) -> Unit
    ) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val count = renderer.pageCount
                for (index in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    val pngBytes = renderer.openPage(index).use { page ->
                        val bitmap = renderPageToBitmap(page)
                        try {
                            bitmapToPngBytes(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    onPage(index + 1, count, pngBytes)
                }
            }
        }
    }

    override suspend fun forEachPdfPageBitmap(
        file: File,
        onPage: suspend (pageNumber: Int, totalPages: Int, bitmap: Bitmap) -> Unit
    ) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val count = renderer.pageCount
                for (index in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(index).use { page ->
                        val bitmap = renderPageToBitmap(page)
                        try {
                            onPage(index + 1, count, bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    override fun loadImageAsPngBytes(file: File): ByteArray {
        val bitmap = decodeDownsampledImage(file)
        return try {
            bitmapToPngBytes(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun <T> withDecodedImageBitmap(
        file: File,
        block: suspend (bitmap: Bitmap, rotationDegrees: Int) -> T
    ): T {
        val rotation = readExifRotationDegrees(file)
        val bitmap = decodeDownsampledImage(file)
        return try {
            block(bitmap, rotation)
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPageToBitmap(page: PdfRenderer.Page): Bitmap {
        val scale = (maxDimensionPx.toFloat() / max(page.width, page.height).coerceAtLeast(1))
            .coerceIn(0.5f, 2.0f)
        val targetWidth = (page.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun decodeDownsampledImage(file: File): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxDimensionPx || boundsOpts.outHeight / sampleSize > maxDimensionPx) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: throw IllegalStateException("Não foi possível decodificar o arquivo de imagem para OCR.")
    }

    private fun readExifRotationDegrees(file: File): Int {
        return runCatching {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        return out.toByteArray()
    }
}

interface LocalTextRecognizer : AutoCloseable {
    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): String
}

class MlKitLatinTextRecognizer : LocalTextRecognizer {
    private val client by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val visionText = client.process(image).awaitMlKitTask()
        return visionText.textBlocks
            .joinToString("\n\n") { block ->
                block.lines.joinToString("\n") { line -> line.text.trim() }
            }
            .trim()
    }

    override fun close() {
        runCatching { client.close() }
    }

    private suspend fun <T> Task<T>.awaitMlKitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { res ->
            if (cont.isActive) cont.resume(res)
        }
        addOnFailureListener { exc ->
            if (cont.isActive) cont.resumeWithException(exc)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }
}

/**
 * Bundled, completely offline on-device OCR engine backed by ML Kit Text Recognition v2 (Latin).
 * Processes PDFs one page at a time to keep memory bounded on Android 7.0+ devices, never transmits
 * documents over the network, and closes recognizer resources cleanly.
 */
class MlKitLocalOcrEngine(
    private val pageRenderer: PdfPageRenderer = AndroidPdfPageRenderer(),
    private val recognizerFactory: () -> LocalTextRecognizer = { MlKitLatinTextRecognizer() }
) : OcrEngine {

    override suspend fun extractText(file: File): Result<List<ExtractedPage>> {
        val guessedType = if (file.name.lowercase().endsWith(".pdf")) SourceType.PDF else SourceType.IMAGE
        return extractTextWithReport(file, guessedType).map { it.pages }
    }

    override suspend fun extractTextWithReport(
        file: File,
        sourceType: SourceType
    ): Result<OcrExtractionSummary> = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(
                IllegalStateException("Arquivo não encontrado para reconhecimento local de texto.")
            )
        }

        val recognizer = try {
            recognizerFactory()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext Result.failure(
                IllegalStateException("Não foi possível iniciar o OCR local no dispositivo: ${t.localizedMessage ?: "erro interno"}.", t)
            )
        }

        try {
            when (sourceType) {
                SourceType.IMAGE -> {
                    currentCoroutineContext().ensureActive()
                    val text = try {
                        pageRenderer.withDecodedImageBitmap(file) { bitmap, rotationDegrees ->
                            currentCoroutineContext().ensureActive()
                            recognizer.recognize(bitmap, rotationDegrees).trim()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        return@withContext Result.failure(
                            IllegalStateException("Falha ao ler imagem no OCR local: ${t.localizedMessage ?: "arquivo inválido"}.", t)
                        )
                    }
                    Result.success(
                        OcrExtractionSummary(
                            pages = listOf(ExtractedPage(pageNumber = 1, text = text)),
                            failedPages = emptyMap(),
                            providerUsed = OcrProviderType.ML_KIT_LOCAL.label
                        )
                    )
                }

                SourceType.PDF -> {
                    val extractedPages = mutableListOf<ExtractedPage>()
                    val failedPages = linkedMapOf<Int, String>()
                    var renderedPageCount = 0

                    try {
                        pageRenderer.forEachPdfPageBitmap(file) { pageNum, _, bitmap ->
                            currentCoroutineContext().ensureActive()
                            renderedPageCount++
                            try {
                                val text = recognizer.recognize(bitmap, 0).trim()
                                extractedPages.add(ExtractedPage(pageNumber = pageNum, text = text))
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                failedPages[pageNum] = t.localizedMessage ?: "Erro na página $pageNum"
                                extractedPages.add(ExtractedPage(pageNumber = pageNum, text = ""))
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        if (extractedPages.isEmpty()) {
                            return@withContext Result.failure(
                                IllegalStateException(
                                    "Não foi possível renderizar o PDF no OCR local. O arquivo pode estar corrompido ou protegido (${t.localizedMessage ?: "erro ao abrir PDF"}).",
                                    t
                                )
                            )
                        } else {
                            failedPages[renderedPageCount + 1] = t.localizedMessage ?: "Erro ao continuar PDF"
                        }
                    }

                    if (extractedPages.isEmpty()) {
                        return@withContext Result.failure(
                            IllegalStateException("Nenhuma página encontrada no PDF para OCR local.")
                        )
                    }

                    if (failedPages.size == extractedPages.size) {
                        val firstError = failedPages.values.firstOrNull() ?: "Falha no OCR local."
                        return@withContext Result.failure(
                            IllegalStateException("Falha no OCR local em todas as páginas: $firstError")
                        )
                    }

                    Result.success(
                        OcrExtractionSummary(
                            pages = extractedPages,
                            failedPages = failedPages,
                            providerUsed = OcrProviderType.ML_KIT_LOCAL.label
                        )
                    )
                }

                else -> Result.failure(
                    UnsupportedOperationException("OCR aplicável apenas a PDFs e imagens.")
                )
            }
        } finally {
            runCatching { recognizer.close() }
        }
    }
}

/**
 * Decoupled external OCR engine supporting:
 * - Google Cloud Vision (DOCUMENT_TEXT_DETECTION)
 * - OpenAI-compatible Vision endpoints (multimodal chat completions)
 * - Custom REST OCR endpoints
 * Streams PDF pages one by one to keep memory consumption predictable.
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
        if (settings.ocrProviderType == OcrProviderType.ML_KIT_LOCAL) {
            return@withContext Result.failure(
                OcrNotConfiguredException("OCR configurado para execução local no dispositivo.")
            )
        }
        if (!settings.hasOcrConfigured) {
            return@withContext Result.failure(
                OcrNotConfiguredException(
                    "Provedor de OCR externo não configurado. Informe a chave/endpoint nas Configurações ou ative o OCR Local."
                )
            )
        }

        val apiKey = cryptoManager.decrypt(settings.ocrEncryptedApiKey).trim()
        val extractedPages = mutableListOf<ExtractedPage>()
        val failedPages = linkedMapOf<Int, String>()

        try {
            when (sourceType) {
                SourceType.PDF -> {
                    pageRenderer.forEachPdfPagePng(file) { pageNum, _, pngBytes ->
                        currentCoroutineContext().ensureActive()
                        val res = runSinglePageOcr(
                            settings = settings,
                            plainApiKey = apiKey,
                            pageNumber = pageNum,
                            pngBytes = pngBytes
                        )
                        res.onSuccess { text ->
                            extractedPages.add(ExtractedPage(pageNumber = pageNum, text = text.trim()))
                        }.onFailure { err ->
                            if (err is CancellationException) throw err
                            failedPages[pageNum] = err.message ?: "Erro desconhecido na página $pageNum"
                            extractedPages.add(ExtractedPage(pageNumber = pageNum, text = ""))
                        }
                    }
                }
                SourceType.IMAGE -> {
                    currentCoroutineContext().ensureActive()
                    val pngBytes = pageRenderer.loadImageAsPngBytes(file)
                    val res = runSinglePageOcr(
                        settings = settings,
                        plainApiKey = apiKey,
                        pageNumber = 1,
                        pngBytes = pngBytes
                    )
                    res.onSuccess { text ->
                        extractedPages.add(ExtractedPage(pageNumber = 1, text = text.trim()))
                    }.onFailure { err ->
                        if (err is CancellationException) throw err
                        failedPages[1] = err.message ?: "Erro desconhecido na imagem"
                        extractedPages.add(ExtractedPage(pageNumber = 1, text = ""))
                    }
                }
                else -> return@withContext Result.failure(
                    UnsupportedOperationException("OCR aplicável apenas a PDFs e imagens.")
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            return@withContext Result.failure(err)
        }

        if (extractedPages.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Nenhuma página renderizada para OCR."))
        }

        if (failedPages.size == extractedPages.size) {
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
            OcrProviderType.ML_KIT_LOCAL -> {
                throw IllegalStateException("OCR local não utiliza chamadas HTTP.")
            }
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
            OcrProviderType.ML_KIT_LOCAL -> ""
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

/**
 * Routes OCR requests cleanly according to the user's selected [OcrProviderType].
 * Never auto-uploads private documents to external cloud OCR if local OCR fails.
 */
class RoutingOcrEngine(
    private val preferencesStore: AppPreferencesStore,
    private val localOcrEngine: OcrEngine,
    private val externalOcrEngine: OcrEngine
) : OcrEngine {

    override suspend fun extractText(file: File): Result<List<ExtractedPage>> {
        val guessedType = if (file.name.lowercase().endsWith(".pdf")) SourceType.PDF else SourceType.IMAGE
        return extractTextWithReport(file, guessedType).map { it.pages }
    }

    override suspend fun extractTextWithReport(
        file: File,
        sourceType: SourceType
    ): Result<OcrExtractionSummary> {
        val settings = preferencesStore.settingsFlow.first()
        return if (settings.ocrProviderType == OcrProviderType.ML_KIT_LOCAL) {
            localOcrEngine.extractTextWithReport(file, sourceType)
        } else {
            externalOcrEngine.extractTextWithReport(file, sourceType)
        }
    }
}
