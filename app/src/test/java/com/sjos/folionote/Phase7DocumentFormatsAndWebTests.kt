package com.sjos.folionote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.OcrProviderType
import com.sjos.folionote.core.preferences.TranscriptionProviderType
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.SourceStatus
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.engine.document.ConfigurableExternalOcrEngine
import com.sjos.folionote.engine.document.DocumentExtractor
import com.sjos.folionote.engine.document.ExternalAudioTranscriptionEngine
import com.sjos.folionote.engine.document.MediaMetadataInfo
import com.sjos.folionote.engine.document.MediaMetadataReader
import com.sjos.folionote.engine.document.OcrNotConfiguredException
import com.sjos.folionote.engine.document.PdfPageRenderer
import com.sjos.folionote.engine.web.WebClipperEngine
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase7DocumentFormatsAndWebTests {

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun startLocalServer(
        handler: (RecordedRequest) -> Pair<Int, Pair<String, String>>
    ): Pair<HttpServer, MutableList<RecordedRequest>> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val recorded = CopyOnWriteArrayList<RecordedRequest>()
        server.createContext("/") { exchange ->
            val reqBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val headerMap = mutableMapOf<String, String>()
            exchange.requestHeaders.forEach { (k, v) ->
                if (k != null && v.isNotEmpty()) headerMap[k.lowercase()] = v.joinToString(",")
            }
            val rec = RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                headers = headerMap,
                body = reqBody
            )
            recorded.add(rec)
            val (status, contentTypeAndBody) = handler(rec)
            val (contentType, respBody) = contentTypeAndBody
            val bytes = respBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to recorded
    }

    private fun fakePageRenderer(pageCount: Int): PdfPageRenderer = object : PdfPageRenderer {
        override fun renderPdfPagesToPngBytes(file: File): List<Pair<Int, ByteArray>> {
            return (1..pageCount).map { page -> page to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, page.toByte()) }
        }

        override fun loadImageAsPngBytes(file: File): ByteArray {
            return file.readBytes().let { if (it.isEmpty()) byteArrayOf(1, 2, 3) else it }
        }
    }

    // Test 5: Decoupled OCR engine handles unconfigured state, multi-page success, partial failure, and reprocessing
    @Test
    fun `decoupled ocr engine supports external vision api partial page failure and reprocessing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val tempDir = Files.createTempDirectory("folionote_ocr_test").toFile()
        val callCounter = AtomicInteger(0)
        var failSecondPage = true

        val (server, requests) = startLocalServer { req ->
            val idx = callCounter.incrementAndGet()
            if (req.path.endsWith("/vision") && (!failSecondPage || idx % 2 == 1)) {
                val resp = JSONObject().put(
                    "responses",
                    JSONArray().put(
                        JSONObject().put(
                            "fullTextAnnotation",
                            JSONObject().put("text", "Texto OCR página $idx")
                        )
                    )
                ).toString()
                200 to ("application/json" to resp)
            } else {
                500 to ("application/json" to """{"error":{"message":"OCR timeout na página"}}""")
            }
        }

        try {
            prefs.clearOcrKey()
            prefs.saveOcrConfig(OcrProviderType.GOOGLE_CLOUD_VISION, "", "", "")

            val fakePdf = File(tempDir, "digitalizado.pdf").apply { writeText("pdf-bytes") }
            val ocrEngine = ConfigurableExternalOcrEngine(
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                pageRenderer = fakePageRenderer(2)
            )

            // 1. Unconfigured fails with OcrNotConfiguredException
            val unconfigured = ocrEngine.extractTextWithReport(fakePdf, SourceType.PDF)
            assertTrue(unconfigured.isFailure)
            assertTrue(unconfigured.exceptionOrNull() is OcrNotConfiguredException)

            // 2. Configured Google Cloud Vision with partial page failure (page 1 ok, page 2 fails HTTP 500)
            val port = server.address.port
            prefs.saveOcrConfig(
                providerType = OcrProviderType.GOOGLE_CLOUD_VISION,
                endpointUrl = "http://127.0.0.1:$port/vision",
                modelId = "",
                encryptedKey = crypto.encrypt("vision-secret-key")
            )
            failSecondPage = true
            val partial = ocrEngine.extractTextWithReport(fakePdf, SourceType.PDF)
            assertTrue(partial.isSuccess)
            val partialSummary = partial.getOrThrow()
            assertTrue(partialSummary.isPartialFailure)
            assertEquals(1, partialSummary.failedPages.size)
            assertTrue(partialSummary.failedPages.containsKey(2))
            assertTrue(partialSummary.pages[0].text.contains("Texto OCR página"))
            assertEquals("vision-secret-key", requests.first().headers["x-goog-api-key"])

            // 3. Reprocess cleanly when all pages succeed
            failSecondPage = false
            callCounter.set(0)
            val fullSuccess = ocrEngine.extractTextWithReport(fakePdf, SourceType.PDF)
            assertTrue(fullSuccess.isSuccess)
            val fullSummary = fullSuccess.getOrThrow()
            assertFalse(fullSummary.isPartialFailure)
            assertTrue(fullSummary.failedPages.isEmpty())
            assertEquals(2, fullSummary.pages.size)
            assertTrue(fullSummary.pages[1].text.contains("Texto OCR página 2"))
        } finally {
            prefs.clearOcrKey()
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    // Test 6: PPTX ZIP/XML extraction preserves numeric slide order, titles, bullets, and speaker notes
    @Test
    fun `document extractor parses pptx slides in numeric order with bullets and speaker notes`() = runTest {
        val tempDir = Files.createTempDirectory("folionote_pptx_test").toFile()
        val pptxFile = File(tempDir, "aula.pptx")
        try {
            val slide1 = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree>
                    <p:sp><p:nvSpPr><p:nvPr><p:ph type="ctrTitle"/></p:nvPr></p:nvSpPr>
                      <p:txBody><a:p><a:r><a:t>Arquitetura FolioNote</a:t></a:r></a:p></p:txBody>
                    </p:sp>
                    <p:sp><p:txBody><a:p><a:pPr><a:buChar char="•"/></a:pPr><a:r><a:t>Primeiro tópico</a:t></a:r></a:p></p:txBody></p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
            """.trimIndent()
            val slide2 = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree>
                    <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
                      <p:txBody><a:p><a:r><a:t>Slide Dois</a:t></a:r></a:p></p:txBody>
                    </p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
            """.trimIndent()
            val slide10 = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree>
                    <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
                      <p:txBody><a:p><a:r><a:t>Slide Dez Final</a:t></a:r></a:p></p:txBody>
                    </p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
            """.trimIndent()
            val notes1 = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                         xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree><p:sp><p:txBody>
                    <a:p><a:r><a:t>Explicar isolamento de provedores na fala inicial.</a:t></a:r></a:p>
                  </p:txBody></p:sp></p:spTree></p:cSld>
                </p:notes>
            """.trimIndent()

            ZipOutputStream(FileOutputStream(pptxFile)).use { zip ->
                // Deliberately write slide10 before slide2 to verify numeric sort order
                zip.putNextEntry(ZipEntry("ppt/slides/slide10.xml"))
                zip.write(slide10.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("ppt/slides/slide2.xml"))
                zip.write(slide2.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
                zip.write(slide1.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("ppt/notesSlides/notesSlide1.xml"))
                zip.write(notes1.toByteArray())
                zip.closeEntry()
            }

            val extractor = DocumentExtractor()
            val extracted = extractor.extract(pptxFile, SourceType.PPTX)

            assertFalse(extracted.requiresOcr)
            assertEquals(3, extracted.pages.size)
            assertEquals("pptx", extracted.metadata["format"])
            assertEquals("3", extracted.metadata["slides"])
            assertTrue(extracted.pages[0].text.contains("# Arquitetura FolioNote"))
            assertTrue(extracted.pages[0].text.contains("- Primeiro tópico"))
            assertTrue(extracted.pages[0].text.contains("Notas do apresentador: Explicar isolamento"))
            assertTrue(extracted.pages[1].text.contains("# Slide Dois"))
            assertTrue(extracted.pages[2].text.contains("# Slide Dez Final"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // Test 7: EPUB ZIP/XML extraction reads OPF spine order, metadata, and converts XHTML chapters to Markdown
    @Test
    fun `document extractor parses epub opf spine metadata and chapters into markdown`() = runTest {
        val tempDir = Files.createTempDirectory("folionote_epub_test").toFile()
        val epubFile = File(tempDir, "livro.epub")
        try {
            val containerXml = """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent()
            val opfXml = """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Engenharia de Sistemas</dc:title>
                    <dc:creator>Ada Lovelace</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
            """.trimIndent()
            val ch1 = "<html><head><title>Capítulo Um</title></head><body><h1>Capítulo Um</h1><p>Fundamentos de RAG híbrido.</p></body></html>"
            val ch2 = "<html><head><title>Capítulo Dois</title></head><body><h1>Capítulo Dois</h1><ul><li>Busca lexical</li><li>Vetores</li></ul></body></html>"

            ZipOutputStream(FileOutputStream(epubFile)).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write(containerXml.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zip.write(opfXml.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("OEBPS/text/ch1.xhtml"))
                zip.write(ch1.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("OEBPS/text/ch2.xhtml"))
                zip.write(ch2.toByteArray())
                zip.closeEntry()
            }

            val extracted = DocumentExtractor().extract(epubFile, SourceType.EPUB)
            assertEquals(2, extracted.pages.size)
            assertEquals("epub", extracted.metadata["format"])
            assertEquals("Engenharia de Sistemas", extracted.metadata["epub_title"])
            assertEquals("Ada Lovelace", extracted.metadata["epub_author"])
            assertTrue(extracted.pages[0].text.contains("Capítulo Um"))
            assertTrue(extracted.pages[0].text.contains("Fundamentos de RAG híbrido."))
            assertTrue(extracted.pages[1].text.contains("Capítulo Dois"))
            assertTrue(extracted.pages[1].text.contains("- Busca lexical"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // Test 8: IMAGE extraction requires OCR when unconfigured and extracts text when OCR is configured
    @Test
    fun `image extraction marks requiresOcr without provider and extracts text when ocr is active`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val tempDir = Files.createTempDirectory("folionote_img_test").toFile()
        val imageFile = File(tempDir, "quadro.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val (server, requests) = startLocalServer { _ ->
            val resp = JSONObject().put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", "# Quadro de Física\nF = m * a")
                    )
                )
            ).toString()
            200 to ("application/json" to resp)
        }

        try {
            prefs.clearOcrKey()
            prefs.saveOcrConfig(OcrProviderType.OPENAI_VISION_COMPAT, "", "gpt-4o-mini", "")

            val ocrEngine = ConfigurableExternalOcrEngine(prefs, crypto, OkHttpClient(), fakePageRenderer(1))
            val extractor = DocumentExtractor(ocrEngine = ocrEngine)

            // Without key -> requiresOcr = true
            val pendingOcr = extractor.extract(imageFile, SourceType.IMAGE)
            assertTrue(pendingOcr.requiresOcr)
            assertEquals("true", pendingOcr.metadata["ocr_needed"])
            assertEquals("", pendingOcr.fullText)

            // With OpenAI-Compat Vision OCR configured -> extracts Markdown text
            val port = server.address.port
            prefs.saveOcrConfig(
                providerType = OcrProviderType.OPENAI_VISION_COMPAT,
                endpointUrl = "http://127.0.0.1:$port/v1/chat/completions",
                modelId = "gpt-4o-mini",
                encryptedKey = crypto.encrypt("sk-vision-key")
            )
            val ocrDone = extractor.extract(imageFile, SourceType.IMAGE)
            assertFalse(ocrDone.requiresOcr)
            assertEquals("true", ocrDone.metadata["ocr"])
            assertTrue(ocrDone.fullText.contains("F = m * a"))
            assertEquals("Bearer sk-vision-key", requests.last().headers["authorization"])
        } finally {
            prefs.clearOcrKey()
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    // Test 9: AUDIO metadata reading and external transcription multipart upload + unconfigured handling
    @Test
    fun `audio extraction reads metadata marks requiresTranscription when unconfigured and transcribes via REST`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val tempDir = Files.createTempDirectory("folionote_aud_test").toFile()
        val audioFile = File(tempDir, "palestra.mp3").apply { writeBytes("fake-mp3-audio-payload".toByteArray()) }

        val (server, requests) = startLocalServer { _ ->
            val resp = JSONObject()
                .put("language", "pt")
                .put("text", "Bem-vindos à aula.")
                .put(
                    "segments",
                    JSONArray()
                        .put(JSONObject().put("start", 0.0).put("end", 4.5).put("text", "Bem-vindos à aula."))
                        .put(JSONObject().put("start", 65.0).put("end", 70.0).put("text", "Revisão de conceitos."))
                ).toString()
            200 to ("application/json" to resp)
        }

        val fakeReader = object : MediaMetadataReader {
            override fun readMetadata(file: File) = MediaMetadataInfo(
                durationMs = 125_000L,
                mimeType = "audio/mpeg",
                title = "Aula 1"
            )

            override fun extractAudioTrack(videoFile: File, outputFile: File) = false
        }

        try {
            prefs.clearTranscriptionKey()
            prefs.saveTranscriptionConfig(TranscriptionProviderType.OPENAI_AUDIO_COMPAT, "", "whisper-1", "pt", "")

            val transEngine = ExternalAudioTranscriptionEngine(prefs, crypto, OkHttpClient())
            val extractor = DocumentExtractor(
                transcriptionEngine = transEngine,
                mediaMetadataReader = fakeReader
            )

            // Unconfigured -> requiresTranscription = true, metadata preserved
            val noKey = extractor.extract(audioFile, SourceType.AUDIO)
            assertTrue(noKey.requiresTranscription)
            assertEquals("02:05", noKey.metadata["duration_formatted"])
            assertEquals("Aula 1", noKey.metadata["media_title"])

            // Configured -> multipart upload + formatted transcript with timestamps
            val port = server.address.port
            prefs.saveTranscriptionConfig(
                providerType = TranscriptionProviderType.OPENAI_AUDIO_COMPAT,
                endpointUrl = "http://127.0.0.1:$port/v1/audio/transcriptions",
                modelId = "whisper-1",
                language = "pt",
                encryptedKey = crypto.encrypt("sk-whisper-secret")
            )

            val done = extractor.extract(audioFile, SourceType.AUDIO)
            assertFalse(done.requiresTranscription)
            assertEquals("true", done.metadata["transcribed"])
            assertTrue(done.fullText.contains("[00:00] Bem-vindos à aula."))
            assertTrue(done.fullText.contains("[01:05] Revisão de conceitos."))
            assertEquals("Bearer sk-whisper-secret", requests.last().headers["authorization"])
            assertTrue(requests.last().body.contains("palestra.mp3"))
        } finally {
            prefs.clearTranscriptionKey()
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    // Test 10: VIDEO audio track extraction and timestamped transcription
    @Test
    fun `video extraction extracts audio track uploads to transcription api and preserves timestamps`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val tempDir = Files.createTempDirectory("folionote_vid_test").toFile()
        val videoFile = File(tempDir, "seminario.mp4").apply { writeBytes("fake-mp4-container".toByteArray()) }

        var trackExtractedToFile: Boolean = false
        val (server, requests) = startLocalServer { _ ->
            val resp = JSONObject()
                .put("language", "pt")
                .put(
                    "segments",
                    JSONArray()
                        .put(JSONObject().put("start", 12.0).put("end", 18.0).put("text", "Introdução ao experimento."))
                        .put(JSONObject().put("start", 3662.0).put("end", 3670.0).put("text", "Conclusão final."))
                ).toString()
            200 to ("application/json" to resp)
        }

        val fakeVideoReader = object : MediaMetadataReader {
            override fun readMetadata(file: File) = MediaMetadataInfo(
                durationMs = 3_700_000L,
                width = 1920,
                height = 1080,
                mimeType = "video/mp4"
            )

            override fun extractAudioTrack(videoFile: File, outputFile: File): Boolean {
                outputFile.writeBytes("extracted-aac-track-bytes".toByteArray())
                trackExtractedToFile = true
                return true
            }
        }

        try {
            val port = server.address.port
            prefs.saveTranscriptionConfig(
                providerType = TranscriptionProviderType.OPENAI_AUDIO_COMPAT,
                endpointUrl = "http://127.0.0.1:$port/v1/audio/transcriptions",
                modelId = "whisper-1",
                language = "pt",
                encryptedKey = crypto.encrypt("sk-video-trans")
            )

            val extractor = DocumentExtractor(
                transcriptionEngine = ExternalAudioTranscriptionEngine(prefs, crypto, OkHttpClient()),
                mediaMetadataReader = fakeVideoReader
            )

            val res = extractor.extract(videoFile, SourceType.VIDEO)
            assertTrue(trackExtractedToFile)
            assertFalse(res.requiresTranscription)
            assertEquals("true", res.metadata["audio_track_extracted"])
            assertEquals("1920x1080", res.metadata["resolution"])
            assertTrue(res.fullText.contains("[00:12] Introdução ao experimento."))
            assertTrue(res.fullText.contains("[01:01:02] Conclusão final."))
            assertTrue(requests.last().body.contains("extracted-aac-track-bytes"))
        } finally {
            prefs.clearTranscriptionKey()
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    // Test 11: Web Clipper URL validation, Share Intent URL parsing, HTML->Markdown extraction, and 403/404 handling
    @Test
    fun `web clipper extracts clean markdown parses share intent urls and handles paywall 403 and 404`() = runTest {
        val (server, _) = startLocalServer { req ->
            when (req.path) {
                "/artigo" -> {
                    val html = """
                        <!doctype html>
                        <html>
                        <head>
                          <title>Título Ignorado</title>
                          <meta property="og:title" content="Guia de Arquitetura Android" />
                          <meta name="author" content="Ana Silva" />
                          <link rel="canonical" href="http://127.0.0.1/canonico" />
                        </head>
                        <body>
                          <nav><a href="/home">Menu Principal</a></nav>
                          <script>console.log('tracking');</script>
                          <article>
                            <h1>Guia de Arquitetura Android</h1>
                            <p>Separar UI de regras de negócio garante testabilidade.</p>
                            <ul><li>ViewModel</li><li>StateFlow</li></ul>
                          </article>
                          <footer>Rodapé do site</footer>
                        </body>
                        </html>
                    """.trimIndent()
                    200 to ("text/html; charset=utf-8" to html)
                }
                "/paywall" -> 403 to ("text/html" to "<html><body>Assine para ler</body></html>")
                else -> 404 to ("text/html" to "<html><body>Not found</body></html>")
            }
        }

        try {
            val port = server.address.port
            val clipper = WebClipperEngine(OkHttpClient())

            // Share intent extraction from noisy text
            val shared = "Olha esse artigo incrível: https://example.com/post?id=42! Muito bom."
            assertEquals("https://example.com/post?id=42", clipper.extractFirstUrl(shared))
            assertTrue(clipper.validateUrl("javascript:alert(1)").isFailure)

            // Valid HTML page extraction -> removes nav/script/footer and builds clean Markdown
            val clipped = clipper.clipUrl("http://127.0.0.1:$port/artigo").getOrThrow()
            assertEquals("Guia de Arquitetura Android", clipped.title)
            assertEquals("Ana Silva", clipped.author)
            assertEquals("http://127.0.0.1/canonico", clipped.canonicalUrl)
            assertTrue(clipped.markdownContent.contains("Separar UI de regras de negócio garante testabilidade."))
            assertTrue(clipped.markdownContent.contains("- ViewModel"))
            assertFalse(clipped.markdownContent.contains("console.log"))
            assertFalse(clipped.markdownContent.contains("Menu Principal"))

            // Paywall 403 & missing 404 handling
            val paywallRes = clipper.clipUrl("http://127.0.0.1:$port/paywall")
            assertTrue(paywallRes.isFailure)
            assertTrue(paywallRes.exceptionOrNull()?.message?.contains("403") == true)

            val missingRes = clipper.clipUrl("http://127.0.0.1:$port/inexistente")
            assertTrue(missingRes.isFailure)
            assertTrue(missingRes.exceptionOrNull()?.message?.contains("404") == true)
        } finally {
            server.stop(0)
        }
    }
}
