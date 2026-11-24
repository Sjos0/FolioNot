package com.sjos.folionote.engine.indexing

import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.engine.context.TokenEstimator
import com.sjos.folionote.engine.document.ExtractedPage
import java.util.UUID

/**
 * Structure-aware document chunker respecting headings, paragraphs, lists, tables,
 * and page boundaries while preserving exact source offsets.
 */
class StructuralChunker {

    fun chunkPages(
        sourceId: String,
        notebookId: String,
        pages: List<ExtractedPage>,
        maxChunkChars: Int = 900,
        overlapChars: Int = 160
    ): List<DocumentChunkEntity> {
        val safeMax = maxChunkChars.coerceIn(250, 4000)
        val safeOverlap = overlapChars.coerceIn(0, safeMax / 2)
        val results = mutableListOf<DocumentChunkEntity>()
        var globalChunkIndex = 0
        var activeSection = ""
        var globalOffset = 0

        for (page in pages) {
            val pageText = page.text.trim()
            if (pageText.isBlank()) continue

            val blocks = splitIntoStructuralBlocks(pageText)
            val buffer = StringBuilder()
            var bufferStartOffset = globalOffset

            fun flushBuffer() {
                val text = buffer.toString().trim()
                if (text.isNotEmpty()) {
                    results.add(
                        DocumentChunkEntity(
                            id = UUID.randomUUID().toString(),
                            sourceId = sourceId,
                            notebookId = notebookId,
                            chunkIndex = globalChunkIndex++,
                            sectionTitle = activeSection,
                            pageNumber = page.pageNumber,
                            startOffset = bufferStartOffset,
                            endOffset = bufferStartOffset + text.length,
                            content = text,
                            tokenEstimate = TokenEstimator.estimateTokens(text)
                        )
                    )
                }
            }

            for (block in blocks) {
                val detectedHeading = extractHeadingTitle(block)
                if (detectedHeading != null) {
                    if (buffer.isNotEmpty()) {
                        flushBuffer()
                        buffer.clear()
                        bufferStartOffset = globalOffset
                    }
                    activeSection = detectedHeading
                }

                if (block.length > safeMax) {
                    if (buffer.isNotEmpty()) {
                        flushBuffer()
                        buffer.clear()
                    }
                    val subSlices = sliceLargeBlock(block, safeMax, safeOverlap)
                    for (slice in subSlices) {
                        results.add(
                            DocumentChunkEntity(
                                id = UUID.randomUUID().toString(),
                                sourceId = sourceId,
                                notebookId = notebookId,
                                chunkIndex = globalChunkIndex++,
                                sectionTitle = activeSection,
                                pageNumber = page.pageNumber,
                                startOffset = globalOffset,
                                endOffset = globalOffset + slice.length,
                                content = slice,
                                tokenEstimate = TokenEstimator.estimateTokens(slice)
                            )
                        )
                        globalOffset += slice.length + 1
                    }
                    bufferStartOffset = globalOffset
                    continue
                }

                val projected = if (buffer.isEmpty()) block.length else buffer.length + 2 + block.length
                if (projected > safeMax && buffer.isNotEmpty()) {
                    val prevText = buffer.toString()
                    flushBuffer()
                    buffer.clear()
                    if (safeOverlap > 0) {
                        val tail = takeOverlapTail(prevText, safeOverlap)
                        if (tail.isNotEmpty()) {
                            buffer.append(tail)
                            bufferStartOffset = (globalOffset - tail.length).coerceAtLeast(0)
                        } else {
                            bufferStartOffset = globalOffset
                        }
                    } else {
                        bufferStartOffset = globalOffset
                    }
                }

                if (buffer.isNotEmpty()) buffer.append("\n\n")
                buffer.append(block)
                globalOffset += block.length + 2
            }

            if (buffer.isNotEmpty()) {
                flushBuffer()
            }
        }

        return results
    }

    private fun splitIntoStructuralBlocks(pageText: String): List<String> {
        val lines = pageText.lines()
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var inTable = false

        fun pushCurrent() {
            val s = current.toString().trim()
            if (s.isNotEmpty()) blocks.add(s)
            current.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            val isHeading = trimmed.startsWith("#")
            val isTableLine = trimmed.startsWith("|") && trimmed.endsWith("|")
            val isListLine = trimmed.startsWith("- ") || trimmed.startsWith("* ") ||
                Regex("^\\d+[.)]\\s+.*").matches(trimmed)

            when {
                trimmed.isEmpty() -> {
                    inTable = false
                    pushCurrent()
                }
                isHeading -> {
                    inTable = false
                    pushCurrent()
                    blocks.add(trimmed)
                }
                isTableLine -> {
                    if (!inTable && current.isNotEmpty()) pushCurrent()
                    inTable = true
                    if (current.isNotEmpty()) current.append("\n")
                    current.append(trimmed)
                }
                isListLine -> {
                    if (inTable) {
                        inTable = false
                        pushCurrent()
                    }
                    if (current.isNotEmpty() && !current.toString().trimStart().let {
                            it.startsWith("- ") || it.startsWith("* ") || Regex("^\\d+[.)]\\s+.*").containsMatchIn(it)
                        }
                    ) {
                        pushCurrent()
                    }
                    if (current.isNotEmpty()) current.append("\n")
                    current.append(trimmed)
                }
                else -> {
                    if (inTable) {
                        inTable = false
                        pushCurrent()
                    }
                    if (current.isNotEmpty()) current.append("\n")
                    current.append(line)
                }
            }
        }
        pushCurrent()
        return blocks
    }

    private fun extractHeadingTitle(block: String): String? {
        val firstLine = block.lineSequence().firstOrNull()?.trim() ?: return null
        return when {
            firstLine.startsWith("#") -> firstLine.trimStart('#').trim().take(100)
            firstLine.length in 4..72 &&
                !firstLine.endsWith(".") &&
                firstLine.all { !it.isLetter() || it.isUpperCase() } &&
                firstLine.any { it.isLetter() } -> firstLine
            else -> null
        }
    }

    private fun sliceLargeBlock(block: String, maxChars: Int, overlapChars: Int): List<String> {
        val slices = mutableListOf<String>()
        var start = 0
        while (start < block.length) {
            var end = (start + maxChars).coerceAtMost(block.length)
            if (end < block.length) {
                val sentenceBreak = block.lastIndexOf(". ", end)
                val spaceBreak = block.lastIndexOf(' ', end)
                end = when {
                    sentenceBreak > start + maxChars / 2 -> sentenceBreak + 1
                    spaceBreak > start + maxChars / 2 -> spaceBreak
                    else -> end
                }
            }
            val slice = block.substring(start, end).trim()
            if (slice.isNotEmpty()) slices.add(slice)
            if (end >= block.length) break
            start = (end - overlapChars).coerceAtLeast(start + 1)
        }
        return slices
    }

    private fun takeOverlapTail(text: String, overlapChars: Int): String {
        if (text.length <= overlapChars) return text
        val rawTail = text.takeLast(overlapChars)
        val firstSpace = rawTail.indexOf(' ')
        return if (firstSpace in 1 until rawTail.length - 10) {
            rawTail.substring(firstSpace + 1).trim()
        } else {
            rawTail.trim()
        }
    }
}
