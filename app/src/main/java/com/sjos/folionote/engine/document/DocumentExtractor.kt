package com.sjos.folionote.engine.document

import android.util.Xml
import com.sjos.folionote.data.local.SourceStatus
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.engine.web.WebClipperEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

data class ExtractedPage(
    val pageNumber: Int,
    val text: String
)

data class ExtractedDocument(
    val pages: List<ExtractedPage>,
    val fullText: String,
    val requiresOcr: Boolean,
    val metadata: Map<String, String> = emptyMap(),
    val requiresTranscription: Boolean = false,
    val partialError: String? = null,
    val statusOverride: SourceStatus? = null
)

class NoOpOcrEngine : OcrEngine {
    override suspend fun extractText(file: File): Result<List<ExtractedPage>> {
        return Result.failure(OcrNotConfiguredException("OCR externo não configurado."))
    }
}

class NoOpTranscriptionEngine : AudioTranscriptionEngine {
    override suspend fun transcribe(mediaFile: File, mimeType: String): Result<TranscriptionResult> {
        return Result.failure(TranscriptionNotConfiguredException("Transcrição externa não configurada."))
    }
}

class DocumentExtractor(
    private val ocrEngine: OcrEngine = NoOpOcrEngine(),
    private val transcriptionEngine: AudioTranscriptionEngine = NoOpTranscriptionEngine(),
    private val mediaMetadataReader: MediaMetadataReader = AndroidMediaMetadataReader(),
    private val webClipperEngine: WebClipperEngine = WebClipperEngine()
) {

    suspend fun extract(
        file: File,
        sourceType: SourceType,
        existingMetadata: Map<String, String> = emptyMap()
    ): ExtractedDocument {
        return when (sourceType) {
            SourceType.PDF -> extractPdf(file)
            SourceType.DOCX -> extractDocx(file)
            SourceType.PPTX -> extractPptx(file)
            SourceType.EPUB -> extractEpub(file)
            SourceType.IMAGE -> extractImage(file)
            SourceType.AUDIO -> extractAudio(file, existingMetadata)
            SourceType.VIDEO -> extractVideo(file, existingMetadata)
            SourceType.WEB_PAGE -> extractWebPage(file, existingMetadata)
            SourceType.TXT, SourceType.MARKDOWN, SourceType.NOTE -> extractPlainText(file)
        }
    }

    private suspend fun extractPdf(file: File): ExtractedDocument {
        PDDocument.load(file).use { doc ->
            val totalPages = doc.numberOfPages.coerceAtLeast(1)
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
            }
            val pages = mutableListOf<ExtractedPage>()
            for (page in 1..totalPages) {
                stripper.startPage = page
                stripper.endPage = page
                val raw = normalizeWhitespace(stripper.getText(doc))
                pages.add(ExtractedPage(pageNumber = page, text = raw))
            }

            val totalChars = pages.sumOf { it.text.count { ch -> !ch.isWhitespace() } }
            if (totalChars < 15 && totalPages > 0) {
                val ocrRes = ocrEngine.extractTextWithReport(file, SourceType.PDF)
                if (ocrRes.isSuccess) {
                    val summary = ocrRes.getOrThrow()
                    val joined = summary.pages.filter { it.text.isNotBlank() }
                        .joinToString("\n\n") { "[Página ${it.pageNumber}]\n${it.text}" }
                    val meta = mutableMapOf(
                        "pages" to summary.pages.size.coerceAtLeast(1).toString(),
                        "ocr" to "true",
                        "ocr_provider" to summary.providerUsed
                    )
                    if (summary.failedPages.isNotEmpty()) {
                        meta["ocr_failed_pages"] = summary.failedPages.keys.sorted().joinToString(",")
                    }
                    val partialMsg = if (summary.isPartialFailure) {
                        "OCR parcial: página(s) ${summary.failedPages.keys.sorted().joinToString(", ")} falharam."
                    } else null
                    return ExtractedDocument(
                        pages = summary.pages,
                        fullText = joined,
                        requiresOcr = false,
                        metadata = meta,
                        partialError = partialMsg,
                        statusOverride = if (summary.isPartialFailure) SourceStatus.PARTIAL else null
                    )
                }
                val errMsg = ocrRes.exceptionOrNull()?.message ?: "OCR necessário para este PDF digitalizado."
                return ExtractedDocument(
                    pages = pages,
                    fullText = "",
                    requiresOcr = true,
                    metadata = mapOf("pages" to totalPages.toString(), "ocr_needed" to "true"),
                    partialError = errMsg
                )
            }

            val meta = mutableMapOf("pages" to totalPages.toString())
            doc.documentInformation?.title?.takeIf { it.isNotBlank() }?.let { meta["pdf_title"] = it }
            doc.documentInformation?.author?.takeIf { it.isNotBlank() }?.let { meta["pdf_author"] = it }

            val joined = pages.filter { it.text.isNotBlank() }
                .joinToString("\n\n") { "[Página ${it.pageNumber}]\n${it.text}" }
            return ExtractedDocument(
                pages = pages,
                fullText = joined,
                requiresOcr = false,
                metadata = meta
            )
        }
    }

    private fun extractDocx(file: File): ExtractedDocument {
        val pages = mutableListOf<String>()
        val currentPage = StringBuilder()

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val parser = createXmlParser()
                    parser.setInput(zip, "UTF-8")

                    var eventType = parser.eventType
                    var inParagraph = false
                    var inTableCell = false
                    var headingLevel = 0
                    val paragraphBuffer = StringBuilder()
                    val rowCells = mutableListOf<String>()
                    var cellBuffer = StringBuilder()

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val tag = parser.name
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                when (tag) {
                                    "tr" -> rowCells.clear()
                                    "tc" -> {
                                        inTableCell = true
                                        cellBuffer = StringBuilder()
                                    }
                                    "p" -> {
                                        inParagraph = true
                                        headingLevel = 0
                                        paragraphBuffer.clear()
                                    }
                                    "pStyle" -> {
                                        val styleVal = parser.getAttributeValue(null, "val")
                                            ?: parser.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val")
                                            ?: ""
                                        headingLevel = when {
                                            styleVal.contains("Heading1", ignoreCase = true) || styleVal.equals("Title", true) -> 1
                                            styleVal.contains("Heading2", ignoreCase = true) -> 2
                                            styleVal.contains("Heading3", ignoreCase = true) -> 3
                                            else -> 0
                                        }
                                    }
                                    "br" -> {
                                        val brType = parser.getAttributeValue(null, "type")
                                            ?: parser.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "type")
                                        if (brType == "page" && currentPage.isNotBlank()) {
                                            pages.add(normalizeWhitespace(currentPage.toString()))
                                            currentPage.clear()
                                        } else if (inParagraph) {
                                            paragraphBuffer.append("\n")
                                        }
                                    }
                                    "tab" -> if (inParagraph) paragraphBuffer.append(" ")
                                }
                            }
                            XmlPullParser.TEXT -> {
                                if (inParagraph) paragraphBuffer.append(parser.text ?: "")
                            }
                            XmlPullParser.END_TAG -> {
                                when (tag) {
                                    "p" -> {
                                        val pText = paragraphBuffer.toString().trim()
                                        if (pText.isNotEmpty()) {
                                            val formatted = when (headingLevel) {
                                                1 -> "# $pText"
                                                2 -> "## $pText"
                                                3 -> "### $pText"
                                                else -> pText
                                            }
                                            if (inTableCell) {
                                                if (cellBuffer.isNotEmpty()) cellBuffer.append(" ")
                                                cellBuffer.append(formatted)
                                            } else {
                                                currentPage.append(formatted).append("\n\n")
                                            }
                                        }
                                        inParagraph = false
                                    }
                                    "tc" -> {
                                        rowCells.add(cellBuffer.toString().trim())
                                        inTableCell = false
                                    }
                                    "tr" -> {
                                        if (rowCells.any { it.isNotBlank() }) {
                                            currentPage.append("| ")
                                                .append(rowCells.joinToString(" | "))
                                                .append(" |\n")
                                        }
                                    }
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }

        if (currentPage.isNotBlank()) {
            pages.add(normalizeWhitespace(currentPage.toString()))
        }

        val extractedPages = if (pages.isEmpty()) {
            listOf(ExtractedPage(1, ""))
        } else {
            pages.mapIndexed { idx, txt -> ExtractedPage(idx + 1, txt) }
        }
        val joined = extractedPages.filter { it.text.isNotBlank() }.joinToString("\n\n") { it.text }
        return ExtractedDocument(
            pages = extractedPages,
            fullText = joined,
            requiresOcr = false,
            metadata = mapOf("pages" to extractedPages.size.toString(), "format" to "docx")
        )
    }

    internal fun extractPptx(file: File): ExtractedDocument {
        val slideRegex = Regex("""^ppt/slides/slide(\d+)\.xml$""")
        val notesRegex = Regex("""^ppt/notesSlides/notesSlide(\d+)\.xml$""")
        val slidesRaw = mutableMapOf<Int, String>()
        val notesRaw = mutableMapOf<Int, String>()

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val slideMatch = slideRegex.matchEntire(name)
                val noteMatch = notesRegex.matchEntire(name)
                if (slideMatch != null) {
                    val idx = slideMatch.groupValues[1].toIntOrNull()
                    if (idx != null) {
                        slidesRaw[idx] = parsePptxSlideXml(readZipEntryString(zip), idx)
                    }
                } else if (noteMatch != null) {
                    val idx = noteMatch.groupValues[1].toIntOrNull()
                    if (idx != null) {
                        notesRaw[idx] = parsePptxNotesXml(readZipEntryString(zip))
                    }
                }
                entry = zip.nextEntry
            }
        }

        val orderedNumbers = slidesRaw.keys.sorted()
        val pages = if (orderedNumbers.isEmpty()) {
            listOf(ExtractedPage(1, ""))
        } else {
            orderedNumbers.mapIndexed { pos, slideNum ->
                val slideBody = slidesRaw[slideNum].orEmpty()
                val noteBody = notesRaw[slideNum].orEmpty()
                val combined = buildString {
                    append(slideBody)
                    if (noteBody.isNotBlank()) {
                        append("\n\n> Notas do apresentador: ").append(noteBody)
                    }
                }.trim()
                ExtractedPage(pageNumber = pos + 1, text = normalizeWhitespace(combined))
            }
        }

        val joined = pages.filter { it.text.isNotBlank() }
            .joinToString("\n\n") { "[Slide ${it.pageNumber}]\n${it.text}" }

        return ExtractedDocument(
            pages = pages,
            fullText = joined,
            requiresOcr = false,
            metadata = mapOf(
                "format" to "pptx",
                "slides" to pages.size.toString(),
                "pages" to pages.size.toString()
            )
        )
    }

    private fun parsePptxSlideXml(xmlContent: String, slideNumber: Int): String {
        val parser = createXmlParser()
        parser.setInput(StringReader(xmlContent))
        val blocks = mutableListOf<String>()
        var inParagraph = false
        var isTitleShape = false
        var isBulletParagraph = false
        val paraBuf = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "sp" -> isTitleShape = false
                        "ph" -> {
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            if (type.equals("title", true) || type.equals("ctrTitle", true)) {
                                isTitleShape = true
                            }
                        }
                        "p" -> {
                            inParagraph = true
                            isBulletParagraph = false
                            paraBuf.clear()
                        }
                        "buChar", "buAutoNum" -> if (inParagraph) isBulletParagraph = true
                        "br" -> if (inParagraph) paraBuf.append("\n")
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inParagraph) paraBuf.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p") {
                        val txt = paraBuf.toString().trim()
                        if (txt.isNotEmpty()) {
                            when {
                                isTitleShape && blocks.none { it.startsWith("# ") } -> blocks.add("# $txt")
                                isBulletParagraph -> blocks.add("- $txt")
                                else -> blocks.add(txt)
                            }
                        }
                        inParagraph = false
                    }
                }
            }
            eventType = parser.next()
        }

        if (blocks.none { it.startsWith("# ") }) {
            blocks.add(0, "# Slide $slideNumber")
        }
        return blocks.joinToString("\n\n")
    }

    private fun parsePptxNotesXml(xmlContent: String): String {
        val parser = createXmlParser()
        parser.setInput(StringReader(xmlContent))
        val paragraphs = mutableListOf<String>()
        var inParagraph = false
        val buf = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "p") {
                    inParagraph = true
                    buf.clear()
                }
                XmlPullParser.TEXT -> if (inParagraph) buf.append(parser.text ?: "")
                XmlPullParser.END_TAG -> if (parser.name == "p") {
                    val line = buf.toString().trim()
                    if (line.isNotEmpty() && !line.all { it.isDigit() }) {
                        paragraphs.add(line)
                    }
                    inParagraph = false
                }
            }
            eventType = parser.next()
        }
        return paragraphs.joinToString(" ")
    }

    internal fun extractEpub(file: File): ExtractedDocument {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name.trimStart('/')] = readZipEntryBytes(zip)
                }
                entry = zip.nextEntry
            }
        }

        val containerBytes = entries["META-INF/container.xml"]
        val opfPath = containerBytes?.let { parseEpubOpfPath(String(it, Charsets.UTF_8)) }
            ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }

        val meta = mutableMapOf("format" to "epub")
        val chapterPaths = mutableListOf<String>()

        if (opfPath != null && entries.containsKey(opfPath)) {
            val opfXml = String(entries[opfPath]!!, Charsets.UTF_8)
            val opfDir = opfPath.substringBeforeLast('/', "")
            val parsedOpf = parseEpubOpf(opfXml)
            parsedOpf.title?.let { meta["epub_title"] = it }
            parsedOpf.author?.let { meta["epub_author"] = it }
            for (idref in parsedOpf.spineIdRefs) {
                val href = parsedOpf.manifestHrefById[idref] ?: continue
                val cleanHref = href.substringBefore('#')
                val resolved = if (opfDir.isBlank()) cleanHref else "$opfDir/$cleanHref"
                val normalized = normalizeZipPath(resolved)
                if (entries.containsKey(normalized)) {
                    chapterPaths.add(normalized)
                }
            }
        }

        if (chapterPaths.isEmpty()) {
            chapterPaths.addAll(
                entries.keys
                    .filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true) }
                    .sorted()
            )
        }

        val pages = mutableListOf<ExtractedPage>()
        for (path in chapterPaths) {
            val html = entries[path]?.toString(Charsets.UTF_8).orEmpty()
            if (html.isBlank()) continue
            val chapterTitle = extractHtmlTitleOrHeading(html)
            val mdBody = webClipperEngine.htmlFragmentToMarkdown(html)
            if (mdBody.isBlank()) continue
            val pageText = if (!chapterTitle.isNullOrBlank() && !mdBody.startsWith("#")) {
                "## $chapterTitle\n\n$mdBody"
            } else {
                mdBody
            }
            pages.add(ExtractedPage(pageNumber = pages.size + 1, text = normalizeWhitespace(pageText)))
        }

        val finalPages = if (pages.isEmpty()) listOf(ExtractedPage(1, "")) else pages
        meta["pages"] = finalPages.size.toString()
        val joined = finalPages.filter { it.text.isNotBlank() }
            .joinToString("\n\n") { "[Capítulo ${it.pageNumber}]\n${it.text}" }

        return ExtractedDocument(
            pages = finalPages,
            fullText = joined,
            requiresOcr = false,
            metadata = meta
        )
    }

    private suspend fun extractImage(file: File): ExtractedDocument {
        val ocrRes = ocrEngine.extractTextWithReport(file, SourceType.IMAGE)
        val baseMeta = mutableMapOf("format" to "image", "image_path" to file.absolutePath)
        if (ocrRes.isSuccess) {
            val summary = ocrRes.getOrThrow()
            val text = normalizeWhitespace(summary.pages.firstOrNull()?.text.orEmpty())
            baseMeta["ocr"] = "true"
            baseMeta["ocr_provider"] = summary.providerUsed
            baseMeta["pages"] = "1"
            return ExtractedDocument(
                pages = listOf(ExtractedPage(1, text)),
                fullText = text,
                requiresOcr = false,
                metadata = baseMeta
            )
        }
        val errMsg = ocrRes.exceptionOrNull()?.message
            ?: "Imagem salva localmente. Configure um provedor OCR externo nas Configurações para extrair texto."
        baseMeta["ocr_needed"] = "true"
        baseMeta["pages"] = "1"
        return ExtractedDocument(
            pages = listOf(ExtractedPage(1, "")),
            fullText = "",
            requiresOcr = true,
            metadata = baseMeta,
            partialError = errMsg
        )
    }

    private suspend fun extractAudio(
        file: File,
        existingMetadata: Map<String, String>
    ): ExtractedDocument {
        val info = mediaMetadataReader.readMetadata(file)
        val meta = existingMetadata.toMutableMap().apply {
            put("format", "audio")
            put("duration_ms", info.durationMs.toString())
            put("duration_formatted", info.formattedDuration)
            if (info.mimeType.isNotBlank()) put("media_mime", info.mimeType)
            if (info.title.isNotBlank()) put("media_title", info.title)
            if (info.artist.isNotBlank()) put("media_artist", info.artist)
            put("pages", "1")
        }

        val transRes = transcriptionEngine.transcribe(file, info.mimeType.ifBlank { "audio/mpeg" })
        if (transRes.isSuccess) {
            val res = transRes.getOrThrow()
            val formatted = buildTranscriptMarkdown("Áudio", info, res)
            val pages = splitIntoLogicalPages(formatted, 2800).ifEmpty { listOf(formatted) }
                .mapIndexed { idx, txt -> ExtractedPage(idx + 1, txt) }
            meta["transcribed"] = "true"
            meta["pages"] = pages.size.toString()
            res.detectedLanguage?.let { meta["detected_language"] = it }
            return ExtractedDocument(
                pages = pages,
                fullText = formatted,
                requiresOcr = false,
                metadata = meta
            )
        }

        val errMsg = transRes.exceptionOrNull()?.message
            ?: "Áudio armazenado (${info.formattedDuration}). Configure um provedor externo de transcrição para indexar o conteúdo."
        meta["transcription_needed"] = "true"
        return ExtractedDocument(
            pages = listOf(ExtractedPage(1, "")),
            fullText = "",
            requiresOcr = false,
            requiresTranscription = true,
            metadata = meta,
            partialError = errMsg
        )
    }

    private suspend fun extractVideo(
        file: File,
        existingMetadata: Map<String, String>
    ): ExtractedDocument {
        val info = mediaMetadataReader.readMetadata(file)
        val meta = existingMetadata.toMutableMap().apply {
            put("format", "video")
            put("duration_ms", info.durationMs.toString())
            put("duration_formatted", info.formattedDuration)
            if (info.width > 0 && info.height > 0) {
                put("resolution", "${info.width}x${info.height}")
            }
            if (info.mimeType.isNotBlank()) put("media_mime", info.mimeType)
            put("pages", "1")
        }

        val tempAudio = File.createTempFile("folionote_aud_", ".m4a", file.parentFile)
        return try {
            val extractedAudio = mediaMetadataReader.extractAudioTrack(file, tempAudio)
            val targetForTranscription = if (extractedAudio && tempAudio.length() > 0L) tempAudio else file
            val targetMime = if (extractedAudio && tempAudio.length() > 0L) "audio/mp4" else info.mimeType.ifBlank { "video/mp4" }
            meta["audio_track_extracted"] = extractedAudio.toString()

            val transRes = transcriptionEngine.transcribe(targetForTranscription, targetMime)
            if (transRes.isSuccess) {
                val res = transRes.getOrThrow()
                val formatted = buildTranscriptMarkdown("Vídeo", info, res)
                val pages = splitIntoLogicalPages(formatted, 2800).ifEmpty { listOf(formatted) }
                    .mapIndexed { idx, txt -> ExtractedPage(idx + 1, txt) }
                meta["transcribed"] = "true"
                meta["pages"] = pages.size.toString()
                res.detectedLanguage?.let { meta["detected_language"] = it }
                ExtractedDocument(
                    pages = pages,
                    fullText = formatted,
                    requiresOcr = false,
                    metadata = meta
                )
            } else {
                val errMsg = transRes.exceptionOrNull()?.message
                    ?: "Vídeo armazenado (${info.formattedDuration}). Configure um provedor externo de transcrição para indexar a fala."
                meta["transcription_needed"] = "true"
                ExtractedDocument(
                    pages = listOf(ExtractedPage(1, "")),
                    fullText = "",
                    requiresOcr = false,
                    requiresTranscription = true,
                    metadata = meta,
                    partialError = errMsg
                )
            }
        } finally {
            runCatching { tempAudio.delete() }
        }
    }

    private fun extractWebPage(file: File, existingMetadata: Map<String, String>): ExtractedDocument {
        val raw = file.readText(Charsets.UTF_8)
        val trimmed = raw.trimStart()
        val markdown = if (trimmed.startsWith("<") && (trimmed.contains("<html", true) || trimmed.contains("<body", true))) {
            val urlHint = existingMetadata["source_url"] ?: existingMetadata["canonical_url"] ?: ""
            webClipperEngine.parseHtmlToClippedPage(raw, urlHint, urlHint, "").markdownContent
        } else {
            normalizeWhitespace(raw)
        }
        val logicalPages = splitIntoLogicalPages(markdown, 2800).ifEmpty { listOf(markdown) }
            .mapIndexed { idx, txt -> ExtractedPage(idx + 1, txt) }
        val meta = existingMetadata.toMutableMap().apply {
            put("format", "web_page")
            put("pages", logicalPages.size.toString())
        }
        return ExtractedDocument(
            pages = logicalPages,
            fullText = markdown,
            requiresOcr = false,
            metadata = meta
        )
    }

    private fun extractPlainText(file: File): ExtractedDocument {
        val raw = normalizeWhitespace(file.readText(Charsets.UTF_8))
        val explicitPages = if (raw.contains('\u000C')) {
            raw.split('\u000C').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            splitIntoLogicalPages(raw, approxCharsPerPage = 2800)
        }
        val pages = if (explicitPages.isEmpty()) {
            listOf(ExtractedPage(1, ""))
        } else {
            explicitPages.mapIndexed { index, text -> ExtractedPage(index + 1, text) }
        }
        return ExtractedDocument(
            pages = pages,
            fullText = raw,
            requiresOcr = false,
            metadata = mapOf("pages" to pages.size.toString())
        )
    }

    private fun buildTranscriptMarkdown(
        kindLabel: String,
        info: MediaMetadataInfo,
        res: TranscriptionResult
    ): String = buildString {
        append("# Transcrição de ").append(kindLabel).append("\n\n")
        if (info.durationMs > 0L) append("Duração: ").append(info.formattedDuration).append("\n")
        if (info.width > 0 && info.height > 0) append("Resolução: ").append(info.width).append("x").append(info.height).append("\n")
        if (!res.detectedLanguage.isNullOrBlank()) append("Idioma detectado: ").append(res.detectedLanguage).append("\n")
        append("\n")
        append(res.fullTranscript)
    }.trim()

    private fun splitIntoLogicalPages(text: String, approxCharsPerPage: Int): List<String> {
        if (text.isBlank()) return emptyList()
        if (text.length <= approxCharsPerPage) return listOf(text)

        val paragraphs = text.split(Regex("\n{2,}"))
        val pages = mutableListOf<String>()
        val current = StringBuilder()
        for (p in paragraphs) {
            if (current.length + p.length + 2 > approxCharsPerPage && current.isNotBlank()) {
                pages.add(current.toString().trim())
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p)
        }
        if (current.isNotBlank()) {
            pages.add(current.toString().trim())
        }
        return pages
    }

    private fun createXmlParser(): XmlPullParser {
        return runCatching { Xml.newPullParser() }.getOrElse {
            XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        }
    }

    private fun readZipEntryBytes(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val read = zip.read(buf)
            if (read <= 0) break
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun readZipEntryString(zip: ZipInputStream): String =
        readZipEntryBytes(zip).toString(Charsets.UTF_8)

    private fun parseEpubOpfPath(containerXml: String): String? {
        val parser = createXmlParser()
        parser.setInput(StringReader(containerXml))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrBlank()) return fullPath.trimStart('/')
            }
            eventType = parser.next()
        }
        return null
    }

    private data class EpubOpfData(
        val title: String? = null,
        val author: String? = null,
        val manifestHrefById: Map<String, String> = emptyMap(),
        val spineIdRefs: List<String> = emptyList()
    )

    private fun parseEpubOpf(opfXml: String): EpubOpfData {
        val parser = createXmlParser()
        parser.setInput(StringReader(opfXml))
        var title: String? = null
        var author: String? = null
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        var currentTag = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.orEmpty()
                    when (currentTag) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                            if (!id.isNullOrBlank() && !href.isNullOrBlank() &&
                                (mediaType.contains("html") || href.endsWith("html", true) || href.endsWith("htm", true))
                            ) {
                                manifest[id] = href
                            }
                        }
                        "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref")
                            if (!idref.isNullOrBlank()) spine.add(idref)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val txt = parser.text?.trim().orEmpty()
                    if (txt.isNotEmpty()) {
                        if (currentTag == "title" && title == null) title = txt
                        if (currentTag == "creator" && author == null) author = txt
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return EpubOpfData(title, author, manifest, spine)
    }

    private fun normalizeZipPath(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        val stack = mutableListOf<String>()
        for (p in parts) {
            if (p == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            } else {
                stack.add(p)
            }
        }
        return stack.joinToString("/")
    }

    private fun extractHtmlTitleOrHeading(html: String): String? {
        val h1 = Regex("(?is)<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)
        val h2 = Regex("(?is)<h2[^>]*>(.*?)</h2>").find(html)?.groupValues?.get(1)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        val raw = h1 ?: h2 ?: title ?: return null
        val cleaned = webClipperEngine.decodeHtmlEntities(raw.replace(Regex("(?is)<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun normalizeWhitespace(input: String): String {
        return input
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[ \t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
