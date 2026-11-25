package com.sjos.folionote

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.preferences.OcrProviderType
import com.sjos.folionote.core.preferences.TranscriptionProviderType
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.SourceStatus
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.engine.document.AndroidMediaExtractorSegmenter
import com.sjos.folionote.engine.document.AudioChunkSpec
import com.sjos.folionote.engine.document.CompletedChunkTranscript
import com.sjos.folionote.engine.document.ConfigurableExternalOcrEngine
import com.sjos.folionote.engine.document.DocumentExtractor
import com.sjos.folionote.engine.document.ExternalAudioTranscriptionEngine
import com.sjos.folionote.engine.document.LocalTextRecognizer
import com.sjos.folionote.engine.document.MediaMetadataInfo
import com.sjos.folionote.engine.document.MediaMetadataReader
import com.sjos.folionote.engine.document.MediaSegmenter
import com.sjos.folionote.engine.document.MlKitLocalOcrEngine
import com.sjos.folionote.engine.document.PartialTranscriptionException
import com.sjos.folionote.engine.document.PdfPageRenderer
import com.sjos.folionote.engine.document.RoutingOcrEngine
import com.sjos.folionote.engine.document.SegmentationPlanResult
import com.sjos.folionote.engine.document.TranscriptMerger
import com.sjos.folionote.engine.document.TranscriptSegment
import com.sjos.folionote.engine.document.TranscriptionHttpException
import com.sjos.folionote.engine.document.TranscriptionLimits
import com.sjos.folionote.engine.document.TranscriptionResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8OcrAndGroqWhisperTests {

    private data class RecordedHttpCall(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val bodyText: String
    )

    private fun startServer(
        handler: (RecordedHttpCall) -> Triple<Int, Map<String, String>, String>
    ): Pair<HttpServer, MutableList<RecordedHttpCall>> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = CopyOnWriteArrayList<RecordedHttpCall>()
        server.createContext("/") { ex ->
            val bodyBytes = ex.requestBody.readBytes()
            val hdrs = mutableMapOf<String, String>()
            ex.requestHeaders.forEach { (k, v) ->
                if (k != null && v.isNotEmpty()) hdrs[k.lowercase()] = v.joinToString(",")
            }
            val call = RecordedHttpCall(
                method = ex.requestMethod,
                path = ex.requestURI.path,
                headers = hdrs,
                bodyText = bodyBytes.toString(Charsets.UTF_8)
            )
            calls.add(call)
            val (code, respHeaders, respBody) = handler(call)
            val outBytes = respBody.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            respHeaders.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(code, outBytes.size.toLong())
            ex.responseBody.use { it.write(outBytes) }
        }
        server.start()
        return server to calls
    }

    private fun createSyntheticPcmWav(file: File, sampleRate: Int = 8000, durationSeconds: Int = 24): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val dataSize = byteRate * durationSeconds
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val payload = ByteArray(dataSize) { idx -> (idx % 127).toByte() }
        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(payload)
        }
        return header + payload
    }

    @Test
    fun `fresh install defaults prioritize offline ML Kit OCR and Groq Whisper turbo without embedded keys`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()

        val defaults = prefs.settingsFlow.first()
        assertEquals(OcrProviderType.ML_KIT_LOCAL, defaults.ocrProviderType)
        assertTrue(defaults.hasOcrConfigured)
        assertEquals(TranscriptionProviderType.GROQ_WHISPER, defaults.transcriptionProviderType)
        assertEquals(TranscriptionLimits.GROQ_ENDPOINT, defaults.transcriptionEndpointUrl)
        assertEquals("whisper-large-v3-turbo", defaults.transcriptionModelId)
        assertEquals("pt", defaults.transcriptionLanguage)
        assertFalse(defaults.hasTranscriptionConfigured)
        assertEquals("", defaults.transcriptionEncryptedApiKey)

        val rawModel = AppSettings()
        assertEquals(OcrProviderType.ML_KIT_LOCAL, rawModel.ocrProviderType)
        assertEquals(TranscriptionProviderType.GROQ_WHISPER, rawModel.transcriptionProviderType)
    }

    @Test
    fun `upgrade migration preserves legacy external OCR and OpenAI transcription keys without wiping config`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        prefs.clearAllPrefsForTest()

        val legacyVisionKey = crypto.encrypt("vision-legacy-secret")
        val legacyOpenAiKey = crypto.encrypt("sk-legacy-whisper-key")
        prefs.seedLegacyStringPrefForTest("ocr_enc_key", legacyVisionKey)
        prefs.seedLegacyStringPrefForTest("ocr_endpoint_url", "https://vision.googleapis.com/v1/images:annotate")
        prefs.seedLegacyStringPrefForTest("trans_enc_key", legacyOpenAiKey)
        prefs.seedLegacyStringPrefForTest("trans_endpoint_url", "https://api.openai.com/v1/audio/transcriptions")

        val migrated = prefs.settingsFlow.first()
        assertEquals(OcrProviderType.GOOGLE_CLOUD_VISION, migrated.ocrProviderType)
        assertEquals("vision-legacy-secret", crypto.decrypt(migrated.ocrEncryptedApiKey))
        assertEquals(TranscriptionProviderType.OPENAI_AUDIO_COMPAT, migrated.transcriptionProviderType)
        assertEquals("whisper-1", migrated.transcriptionModelId)
        assertEquals("sk-legacy-whisper-key", crypto.decrypt(migrated.transcriptionEncryptedApiKey))
    }

    @Test
    fun `local ML Kit OCR extracts Portuguese image text offline with zero network calls and closes recognizer`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val tempDir = Files.createTempDirectory("folionote_mlkit_img").toFile()
        val imgFile = File(tempDir, "pagina.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val networkHits = AtomicInteger(0)
        val (server, _) = startServer {
            networkHits.incrementAndGet()
            Triple(200, emptyMap(), """{"text":"cloud"}""")
        }

        val recognizerClosed = AtomicBoolean(false)
        var bitmapRecycledInFinally = false
        var observedRotation = -1

        val fakeRenderer = object : PdfPageRenderer {
            override fun renderPdfPagesToPngBytes(file: File): List<Pair<Int, ByteArray>> = emptyList()
            override fun loadImageAsPngBytes(file: File): ByteArray = byteArrayOf(1)
            override suspend fun <T> withDecodedImageBitmap(
                file: File,
                block: suspend (bitmap: Bitmap, rotationDegrees: Int) -> T
            ): T {
                val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                return try {
                    block(bmp, 90)
                } finally {
                    bmp.recycle()
                    bitmapRecycledInFinally = bmp.isRecycled
                }
            }
        }

        val localEngine = MlKitLocalOcrEngine(
            pageRenderer = fakeRenderer,
            recognizerFactory = {
                object : LocalTextRecognizer {
                    override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
                        observedRotation = rotationDegrees
                        return "Ação educacional em São Paulo — § 1º"
                    }

                    override fun close() {
                        recognizerClosed.set(true)
                    }
                }
            }
        )

        try {
            prefs.clearAllPrefsForTest()
            val externalEngine = ConfigurableExternalOcrEngine(prefs, KeystoreCryptoManager(), OkHttpClient(), fakeRenderer)
            val router = RoutingOcrEngine(prefs, localEngine, externalEngine)
            val extractor = DocumentExtractor(ocrEngine = router)

            val extracted = extractor.extract(imgFile, SourceType.IMAGE)
            assertFalse(extracted.requiresOcr)
            assertEquals("true", extracted.metadata["ocr"])
            assertTrue(extracted.fullText.contains("Ação educacional em São Paulo — § 1º"))
            assertEquals(90, observedRotation)
            assertTrue(bitmapRecycledInFinally)
            assertTrue(recognizerClosed.get())
            assertEquals(0, networkHits.get())
        } finally {
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `local ML Kit OCR streams multi-page scanned PDFs handles blank pages and never falls back to cloud on failure`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val tempDir = Files.createTempDirectory("folionote_mlkit_pdf").toFile()
        val blankPdf = File(tempDir, "em_branco.pdf")
        val partialPdf = File(tempDir, "parcial.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            doc.save(blankPdf)
        }
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            doc.save(partialPdf)
        }

        val cloudCalls = AtomicInteger(0)
        val (server, _) = startServer {
            cloudCalls.incrementAndGet()
            Triple(200, emptyMap(), """{"responses":[{"fullTextAnnotation":{"text":"cloud fallback"}}]}""")
        }

        val seenPageBitmaps = mutableListOf<Bitmap>()
        val streamingRenderer = object : PdfPageRenderer {
            override fun renderPdfPagesToPngBytes(file: File): List<Pair<Int, ByteArray>> = emptyList()
            override fun loadImageAsPngBytes(file: File): ByteArray = ByteArray(0)
            override suspend fun forEachPdfPageBitmap(
                file: File,
                onPage: suspend (pageNumber: Int, totalPages: Int, bitmap: Bitmap) -> Unit
            ) {
                val pages = if (file == blankPdf) 1 else 2
                for (p in 1..pages) {
                    val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    seenPageBitmaps.add(bmp)
                    try {
                        onPage(p, pages, bmp)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }

        try {
            // Even if user previously stored a cloud key, when ML_KIT_LOCAL is selected it NEVER calls cloud
            prefs.clearAllPrefsForTest()
            prefs.saveOcrConfig(
                providerType = OcrProviderType.GOOGLE_CLOUD_VISION,
                endpointUrl = "http://127.0.0.1:${server.address.port}/vision",
                modelId = "",
                encryptedKey = crypto.encrypt("cloud-secret")
            )
            prefs.saveOcrConfig(OcrProviderType.ML_KIT_LOCAL, "", "", null)

            // 1. Blank page returns PARTIAL with informative notice
            val blankLocal = MlKitLocalOcrEngine(streamingRenderer) {
                object : LocalTextRecognizer {
                    override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int) = ""
                    override fun close() = Unit
                }
            }
            val external = ConfigurableExternalOcrEngine(prefs, crypto, OkHttpClient(), streamingRenderer)
            val blankDoc = DocumentExtractor(ocrEngine = RoutingOcrEngine(prefs, blankLocal, external))
                .extract(blankPdf, SourceType.PDF)
            assertEquals(SourceStatus.PARTIAL, blankDoc.statusOverride)
            assertTrue(blankDoc.partialError?.contains("Nenhum texto legível") == true)
            assertEquals(0, cloudCalls.get())

            // 2. Partial page failure preserves page 1 and reports page 2 failure
            var idx = 0
            val partialLocal = MlKitLocalOcrEngine(streamingRenderer) {
                object : LocalTextRecognizer {
                    override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
                        idx++
                        if (idx == 2) throw IllegalStateException("Falha local na página 2")
                        return "Texto da página 1 extraído offline"
                    }

                    override fun close() = Unit
                }
            }
            val partialDoc = DocumentExtractor(ocrEngine = RoutingOcrEngine(prefs, partialLocal, external))
                .extract(partialPdf, SourceType.PDF)
            assertEquals(SourceStatus.PARTIAL, partialDoc.statusOverride)
            assertTrue(partialDoc.fullText.contains("Texto da página 1"))
            assertEquals("2", partialDoc.metadata["ocr_failed_pages"])
            assertTrue(seenPageBitmaps.all { it.isRecycled })
            assertEquals(0, cloudCalls.get())

            // 3. Complete local failure does NOT silently fall back to cloud
            val failingLocal = MlKitLocalOcrEngine(streamingRenderer) {
                object : LocalTextRecognizer {
                    override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
                        throw IllegalStateException("Falha local simulada")
                    }

                    override fun close() = Unit
                }
            }
            val failedDoc = DocumentExtractor(ocrEngine = RoutingOcrEngine(prefs, failingLocal, external))
                .extract(partialPdf, SourceType.PDF)
            assertTrue(failedDoc.requiresOcr)
            assertEquals(0, cloudCalls.get())
        } finally {
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `local ML Kit OCR cancellation aborts remaining pages and closes recognizer`() = runTest {
        val tempDir = Files.createTempDirectory("folionote_mlkit_cancel").toFile()
        val pdfFile = File(tempDir, "multi.pdf").apply { writeText("stub") }
        val recognizedPages = AtomicInteger(0)
        val closed = AtomicBoolean(false)
        val startedPageOne = CountDownLatch(1)

        val renderer = object : PdfPageRenderer {
            override fun renderPdfPagesToPngBytes(file: File): List<Pair<Int, ByteArray>> = emptyList()
            override fun loadImageAsPngBytes(file: File): ByteArray = ByteArray(0)
            override suspend fun forEachPdfPageBitmap(
                file: File,
                onPage: suspend (pageNumber: Int, totalPages: Int, bitmap: Bitmap) -> Unit
            ) {
                for (p in 1..5) {
                    val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    try {
                        onPage(p, 5, bmp)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }

        val engine = MlKitLocalOcrEngine(renderer) {
            object : LocalTextRecognizer {
                override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
                    recognizedPages.incrementAndGet()
                    startedPageOne.countDown()
                    delay(120)
                    return "Página"
                }

                override fun close() {
                    closed.set(true)
                }
            }
        }

        try {
            val job = async(Dispatchers.IO) { engine.extractTextWithReport(pdfFile, SourceType.PDF) }
            withContext(Dispatchers.IO) {
                startedPageOne.await(5, TimeUnit.SECONDS)
            }
            job.cancel()
            val cancelled = runCatching { job.await() }.exceptionOrNull()
            assertTrue(cancelled is CancellationException)
            assertTrue(recognizedPages.get() in 1..4)
            assertTrue(closed.get())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `native digital PDF skips OCR completely`() = runTest {
        val tempDir = Files.createTempDirectory("folionote_native_pdf").toFile()
        val nativePdf = File(tempDir, "nativo.pdf")
        val ocrCalled = AtomicBoolean(false)
        try {
            com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
                val page = com.tom_roush.pdfbox.pdmodel.PDPage()
                doc.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("Native digital PDF document with plenty of embedded text content.")
                    cs.endText()
                }
                doc.save(nativePdf)
            }

            val spyOcr = object : com.sjos.folionote.engine.document.OcrEngine {
                override suspend fun extractText(file: File): Result<List<com.sjos.folionote.engine.document.ExtractedPage>> {
                    ocrCalled.set(true)
                    return Result.success(emptyList())
                }
            }

            val extracted = DocumentExtractor(ocrEngine = spyOcr).extract(nativePdf, SourceType.PDF)
            assertFalse(ocrCalled.get())
            assertFalse(extracted.requiresOcr)
            assertTrue(extracted.fullText.contains("Native digital PDF"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `groq whisper sends multipart fields for both models and maps HTTP 401 413 422 429 and 500 cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val tempDir = Files.createTempDirectory("folionote_groq_http").toFile()
        val audioFile = File(tempDir, "reuniao.m4a").apply { writeBytes("aac-payload-data".toByteArray()) }

        val nextStatus = AtomicInteger(200)
        val nextRetryAfter = AtomicReference<String?>(null)
        val (server, calls) = startServer {
            val retryAfter = nextRetryAfter.get()
            val status = nextStatus.get()
            val hdrs = if (retryAfter != null) mapOf("Retry-After" to retryAfter) else emptyMap()
            val body = if (status == 200) {
                JSONObject()
                    .put("language", "pt")
                    .put("text", "Ata concluída com sucesso.")
                    .put(
                        "segments",
                        JSONArray().put(
                            JSONObject().put("start", 0.0).put("end", 3.2).put("text", "Ata concluída com sucesso.")
                        )
                    ).toString()
            } else {
                """{"error":{"message":"rate or payload error"}}"""
            }
            Triple(status, hdrs, body)
        }

        val fakeMeta = object : MediaMetadataReader {
            override fun readMetadata(file: File) = MediaMetadataInfo(durationMs = 45_000L, mimeType = "audio/mp4")
            override fun extractAudioTrack(videoFile: File, outputFile: File) = false
        }

        try {
            prefs.clearAllPrefsForTest()
            val endpoint = "http://127.0.0.1:${server.address.port}/openai/v1/audio/transcriptions"
            for (modelId in TranscriptionLimits.GROQ_SUPPORTED_MODELS) {
                prefs.saveTranscriptionConfig(
                    providerType = TranscriptionProviderType.GROQ_WHISPER,
                    endpointUrl = endpoint,
                    modelId = modelId,
                    language = "pt",
                    encryptedKey = crypto.encrypt("gsk_test_groq_key_12345")
                )
                val engine = ExternalAudioTranscriptionEngine(prefs, crypto, OkHttpClient(), fakeMeta)
                val res = engine.transcribe(audioFile, "audio/mp4")
                assertTrue("Groq model $modelId failed: ${res.exceptionOrNull()?.message}", res.isSuccess)
                val call = calls.last()
                assertEquals("Bearer gsk_test_groq_key_12345", call.headers["authorization"])
                assertTrue(
                    "Missing model multipart field for $modelId; body=${call.bodyText.take(1800)}",
                    call.bodyText.contains("name=\"model\"") && call.bodyText.contains(modelId)
                )
                assertTrue(
                    "Missing language multipart field",
                    call.bodyText.contains("name=\"language\"") && call.bodyText.contains("pt")
                )
                assertTrue(
                    "Missing verbose_json response_format",
                    call.bodyText.contains("name=\"response_format\"") && call.bodyText.contains("verbose_json")
                )
                assertTrue(
                    "Missing temperature=0",
                    call.bodyText.contains("name=\"temperature\"") && Regex("\\b0(?:\\.0)?\\b").containsMatchIn(call.bodyText)
                )
                assertTrue("Missing audio filename", call.bodyText.contains("reuniao.m4a"))
            }

            // Export non-secret JSON never exposes key
            val exported = prefs.exportNonSecretPreferencesJson().toString()
            assertFalse(exported.contains("gsk_test_groq_key_12345"))

            val engine = ExternalAudioTranscriptionEngine(prefs, crypto, OkHttpClient(), fakeMeta)
            for (code in listOf(401, 413, 422, 429, 500)) {
                nextStatus.set(code)
                nextRetryAfter.set(if (code == 429) "18" else null)
                val fail = engine.transcribe(audioFile, "audio/mp4")
                assertTrue(fail.isFailure)
                val exc = fail.exceptionOrNull() as TranscriptionHttpException
                assertEquals(code, exc.statusCode)
                if (code == 429) {
                    assertEquals(18L, exc.retryAfterSeconds)
                    assertTrue(exc.message.orEmpty().contains("18s"))
                }
            }
        } finally {
            prefs.clearAllPrefsForTest()
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `wav RIFF PCM segmenter splits oversized long audio cleanly without altering original file`() {
        val tempDir = Files.createTempDirectory("folionote_wav_split").toFile()
        val wavFile = File(tempDir, "long_recording.wav")
        val workDir = File(tempDir, "work")
        try {
            val originalBytes = createSyntheticPcmWav(wavFile, sampleRate = 8000, durationSeconds = 40)
            val segmenter = AndroidMediaExtractorSegmenter()
            val plan = segmenter.trySplitWavPcm(
                wavFile = wavFile,
                preferredDurationMs = 15_000L,
                overlapMs = 3_000L,
                maxBytesPerChunk = 250_000L,
                workDir = workDir
            )!!

            assertTrue(plan.chunks.size >= 3)
            assertEquals(0L, plan.chunks[0].offsetMs)
            assertTrue(plan.chunks[1].offsetMs > 0L)
            assertTrue(plan.chunks[1].offsetMs < plan.chunks[0].durationMs) // overlap verified!
            assertTrue(plan.chunks.all { it.file.exists() && it.file.length() > 44L && it.file.length() <= 250_000L })
            assertEquals("RIFF", String(plan.chunks[0].file.readBytes().copyOfRange(0, 4), Charsets.US_ASCII))
            assertTrue(originalBytes.contentEquals(wavFile.readBytes()))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `long audio segmentation merges shifted timestamps deduplicates overlap and resumes from checkpoint`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val tempDir = Files.createTempDirectory("folionote_long_audio").toFile()
        val masterAudio = File(tempDir, "aula_longa.m4a").apply { writeBytes("master-audio-placeholder".toByteArray()) }

        val chunk1 = File(tempDir, "chunk_1.m4a").apply { writeBytes("chunk-one-bytes".toByteArray()) }
        val chunk2 = File(tempDir, "chunk_2.m4a").apply { writeBytes("chunk-two-bytes".toByteArray()) }
        val chunk3 = File(tempDir, "chunk_3.m4a").apply { writeBytes("chunk-three-bytes".toByteArray()) }

        var failThirdChunk = true
        val uploadedChunkBodies = mutableListOf<String>()
        val (server, _) = startServer { req ->
            uploadedChunkBodies.add(req.bodyText)
            when {
                req.bodyText.contains("chunk-one-bytes") -> Triple(
                    200,
                    emptyMap(),
                    JSONObject()
                        .put("language", "pt")
                        .put(
                            "segments",
                            JSONArray()
                                .put(JSONObject().put("start", 0.0).put("end", 5.0).put("text", "Abertura da aula"))
                                .put(JSONObject().put("start", 555.0).put("end", 568.0).put("text", "Conclusão parcial sobre grafos"))
                        ).toString()
                )
                req.bodyText.contains("chunk-two-bytes") -> Triple(
                    200,
                    emptyMap(),
                    JSONObject()
                        .put("language", "pt")
                        .put(
                            "segments",
                            JSONArray()
                                .put(JSONObject().put("start", 0.0).put("end", 10.0).put("text", "Conclusão parcial sobre grafos"))
                                .put(JSONObject().put("start", 12.0).put("end", 20.0).put("text", "Agora vamos para árvores binárias"))
                        ).toString()
                )
                req.bodyText.contains("chunk-three-bytes") && !failThirdChunk -> Triple(
                    200,
                    emptyMap(),
                    JSONObject()
                        .put("language", "pt")
                        .put(
                            "segments",
                            JSONArray()
                                .put(JSONObject().put("start", 5.0).put("end", 15.0).put("text", "Encerramento do módulo"))
                        ).toString()
                )
                else -> Triple(429, mapOf("Retry-After" to "5"), """{"error":{"message":"rate limit"}}""")
            }
        }

        val fakeSegmenter = object : MediaSegmenter {
            override fun segmentIfNeeded(
                mediaFile: File,
                mimeType: String,
                metadata: MediaMetadataInfo,
                maxPartDurationMs: Long,
                preferredPartDurationMs: Long,
                overlapMs: Long,
                maxPartBytes: Long,
                workDir: File
            ): SegmentationPlanResult {
                val c1 = File(workDir, "c1.m4a").apply { parentFile?.mkdirs(); writeBytes(chunk1.readBytes()) }
                val c2 = File(workDir, "c2.m4a").apply { writeBytes(chunk2.readBytes()) }
                val c3 = File(workDir, "c3.m4a").apply { writeBytes(chunk3.readBytes()) }
                return SegmentationPlanResult(
                    chunks = listOf(
                        AudioChunkSpec(0, c1, "audio/mp4", 0L, 570_000L, true),
                        AudioChunkSpec(1, c2, "audio/mp4", 558_000L, 570_000L, true),
                        AudioChunkSpec(2, c3, "audio/mp4", 1_116_000L, 200_000L, true)
                    ),
                    cleanupFiles = listOf(c1, c2, c3)
                )
            }
        }
        val fakeMeta = object : MediaMetadataReader {
            override fun readMetadata(file: File) = MediaMetadataInfo(durationMs = 1_300_000L, mimeType = "audio/mp4")
            override fun extractAudioTrack(videoFile: File, outputFile: File) = false
        }

        try {
            prefs.clearAllPrefsForTest()
            prefs.saveTranscriptionConfig(
                providerType = TranscriptionProviderType.GROQ_WHISPER,
                endpointUrl = "http://127.0.0.1:${server.address.port}/openai/v1/audio/transcriptions",
                modelId = "whisper-large-v3-turbo",
                language = "pt",
                encryptedKey = crypto.encrypt("gsk_resume_test")
            )
            val engine = ExternalAudioTranscriptionEngine(
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                mediaMetadataReader = fakeMeta,
                mediaSegmenter = fakeSegmenter
            )

            // Run 1 fails at part 3 -> saves partial result & checkpoint, cleans temp chunks
            val firstAttempt = engine.transcribe(masterAudio, "audio/mp4")
            assertTrue(firstAttempt.isFailure)
            val partialExc = firstAttempt.exceptionOrNull() as PartialTranscriptionException
            assertEquals(2, partialExc.failedChunkIndex)
            assertEquals(3, partialExc.totalChunks)
            assertEquals(2, partialExc.completedChunks.size)
            assertFalse(File(tempDir, ".folionote_transcribe_tmp/c1.m4a").exists())

            // Run 2 succeeds -> MUST NOT re-upload chunks 1 and 2!
            uploadedChunkBodies.clear()
            failThirdChunk = false
            val resumed = engine.transcribe(masterAudio, "audio/mp4").getOrThrow()
            assertEquals(1, uploadedChunkBodies.size)
            assertTrue(uploadedChunkBodies.single().contains("chunk-three-bytes"))

            // Verify shifted timestamps & overlap deduplication
            assertTrue(resumed.fullTranscript.contains("[00:00] Abertura da aula"))
            assertTrue(resumed.fullTranscript.contains("[09:30] Agora vamos para árvores binárias"))
            assertTrue(resumed.fullTranscript.contains("[18:41] Encerramento do módulo"))
            val overlapOccurrences = Regex("Conclusão parcial sobre grafos").findAll(resumed.fullTranscript).count()
            assertEquals(1, overlapOccurrences)
        } finally {
            prefs.clearAllPrefsForTest()
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `video without audio track completes as PARTIAL with zero transcription HTTP requests`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val tempDir = Files.createTempDirectory("folionote_muted_video").toFile()
        val videoFile = File(tempDir, "timelapse.mp4").apply { writeBytes("muted-video-bytes".toByteArray()) }

        val httpCalls = AtomicInteger(0)
        val (server, _) = startServer {
            httpCalls.incrementAndGet()
            Triple(200, emptyMap(), """{"text":"should never run"}""")
        }

        val noAudioReader = object : MediaMetadataReader {
            override fun readMetadata(file: File) = MediaMetadataInfo(
                durationMs = 30_000L,
                width = 1280,
                height = 720,
                mimeType = "video/mp4",
                hasAudioTrack = false
            )

            override fun extractAudioTrack(videoFile: File, outputFile: File) = false
        }

        try {
            prefs.clearAllPrefsForTest()
            prefs.saveTranscriptionConfig(
                providerType = TranscriptionProviderType.GROQ_WHISPER,
                endpointUrl = "http://127.0.0.1:${server.address.port}/openai/v1/audio/transcriptions",
                modelId = "whisper-large-v3-turbo",
                language = "pt",
                encryptedKey = crypto.encrypt("gsk_video_test")
            )
            val extractor = DocumentExtractor(
                transcriptionEngine = ExternalAudioTranscriptionEngine(prefs, crypto, OkHttpClient(), noAudioReader),
                mediaMetadataReader = noAudioReader
            )
            val doc = extractor.extract(videoFile, SourceType.VIDEO)
            assertEquals(SourceStatus.PARTIAL, doc.statusOverride)
            assertFalse(doc.requiresTranscription)
            assertTrue(doc.partialError?.contains("não possui faixa de áudio") == true)
            assertEquals(0, httpCalls.get())
        } finally {
            prefs.clearAllPrefsForTest()
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }
}
