package com.example.engine.document

import android.util.Xml
import com.example.data.local.SourceType
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

data class ExtractedPage(
    val pageNumber: Int,
    val text: String
)

data class ExtractedDocument(
    val pages: List<ExtractedPage>,
    val fullText: String,
    val requiresOcr: Boolean,
    val metadata: Map<String, String> = emptyMap()
)

interface OcrEngine {
    suspend fun extractText(file: File): Result<List<ExtractedPage>>
}

class NoOpOcrEngine : OcrEngine {
    override suspend fun extractText(file: File): Result<List<ExtractedPage>> {
        return Result.failure(UnsupportedOperationException("OCR offline ainda não configurado."))
    }
}

class DocumentExtractor(
    private val ocrEngine: OcrEngine = NoOpOcrEngine()
) {

    suspend fun extract(file: File, sourceType: SourceType): ExtractedDocument {
        return when (sourceType) {
            SourceType.PDF -> extractPdf(file)
            SourceType.DOCX -> extractDocx(file)
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
                val ocrResult = ocrEngine.extractText(file)
                if (ocrResult.isSuccess) {
                    val ocrPages = ocrResult.getOrDefault(emptyList())
                    val joined = ocrPages.joinToString("\n\n") { it.text }
                    return ExtractedDocument(
                        pages = ocrPages,
                        fullText = joined,
                        requiresOcr = false,
                        metadata = mapOf("pages" to totalPages.toString(), "ocr" to "true")
                    )
                }
                return ExtractedDocument(
                    pages = pages,
                    fullText = "",
                    requiresOcr = true,
                    metadata = mapOf("pages" to totalPages.toString(), "ocr_needed" to "true")
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
                    val parser = Xml.newPullParser()
                    parser.setInput(zip, "UTF-8")

                    var eventType = parser.eventType
                    var inParagraph = false
                    var inTableRow = false
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
                                    "tr" -> {
                                        inTableRow = true
                                        rowCells.clear()
                                    }
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
                                if (inParagraph) {
                                    val text = parser.text ?: ""
                                    paragraphBuffer.append(text)
                                }
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
                                        inTableRow = false
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

    private fun normalizeWhitespace(input: String): String {
        return input
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[ \t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
