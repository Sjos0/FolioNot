package com.sjos.folionote.engine.document

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

object TranscriptionLimits {
    const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    val GROQ_SUPPORTED_MODELS = listOf(
        "whisper-large-v3-turbo",
        "whisper-large-v3"
    )
    const val GROQ_DEFAULT_MODEL = "whisper-large-v3-turbo"
    const val GROQ_MAX_PART_DURATION_MS = 600_000L // 10 min product operational limit
    const val GROQ_PREFERRED_PART_DURATION_MS = 570_000L // 9m30s
    const val GROQ_PART_OVERLAP_MS = 12_000L // 12s overlap to avoid cutting words
    const val FREE_TIER_MAX_FILE_BYTES = 25L * 1024L * 1024L // 25 MB
    const val SAFE_CHUNK_TARGET_BYTES = 20L * 1024L * 1024L // margin below 25 MB
}

data class AudioChunkSpec(
    val index: Int,
    val file: File,
    val mimeType: String,
    val offsetMs: Long,
    val durationMs: Long,
    val isTemporary: Boolean
)

data class SegmentationPlanResult(
    val chunks: List<AudioChunkSpec>,
    val cleanupFiles: List<File>
)

class MediaSegmentationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

interface MediaSegmenter {
    fun segmentIfNeeded(
        mediaFile: File,
        mimeType: String,
        metadata: MediaMetadataInfo,
        maxPartDurationMs: Long,
        preferredPartDurationMs: Long,
        overlapMs: Long,
        maxPartBytes: Long,
        workDir: File
    ): SegmentationPlanResult
}

/** Converts extractor sample flags to the distinct MediaCodec.BufferInfo flag space. */
internal fun mapExtractorSampleFlagsToCodecBufferFlags(sampleFlags: Int): Int {
    if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
        throw IllegalArgumentException("Encrypted media samples cannot be segmented safely")
    }

    var codecFlags = 0
    if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0
    ) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    }
    return codecFlags
}

/**
 * Splits long or oversized audio/video sources into <= 10-minute / <= 25 MB segments
 * using native MediaExtractor/MediaMuxer for MP4/M4A/AAC streams and RIFF-PCM splitting for WAV,
 * never modifying the original file and tracking temp files for guaranteed cleanup.
 */
class AndroidMediaExtractorSegmenter : MediaSegmenter {

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
        val fileBytes = mediaFile.length()
        val durationMs = metadata.durationMs

        val exceedsDuration = durationMs > maxPartDurationMs
        val exceedsBytes = fileBytes > maxPartBytes

        if (!exceedsDuration && !exceedsBytes) {
            return SegmentationPlanResult(
                chunks = listOf(
                    AudioChunkSpec(
                        index = 0,
                        file = mediaFile,
                        mimeType = mimeType.ifBlank { "audio/mpeg" },
                        offsetMs = 0L,
                        durationMs = durationMs,
                        isTemporary = false
                    )
                ),
                cleanupFiles = emptyList()
            )
        }

        workDir.mkdirs()

        // Try native RIFF/PCM WAV chunking first when file is WAV (works identically across all API levels).
        if (isWavFile(mediaFile, mimeType)) {
            val wavPlan = trySplitWavPcm(
                wavFile = mediaFile,
                preferredDurationMs = preferredPartDurationMs.coerceAtMost(maxPartDurationMs - 10_000L),
                overlapMs = overlapMs,
                maxBytesPerChunk = TranscriptionLimits.SAFE_CHUNK_TARGET_BYTES.coerceAtMost(maxPartBytes),
                workDir = workDir
            )
            if (wavPlan != null) return wavPlan
        }

        // Compute target duration per part ensuring BOTH < maxPartDurationMs AND estimated size <= SAFE_CHUNK_TARGET_BYTES.
        val sizeBasedDurationMs = if (durationMs > 0L && fileBytes > TranscriptionLimits.SAFE_CHUNK_TARGET_BYTES) {
            ((durationMs.toDouble() * TranscriptionLimits.SAFE_CHUNK_TARGET_BYTES.toDouble()) / fileBytes.toDouble())
                .toLong()
                .coerceAtLeast(30_000L)
        } else {
            preferredPartDurationMs
        }
        val targetChunkDurationMs = minOf(
            preferredPartDurationMs.coerceAtMost(maxPartDurationMs - 5_000L),
            sizeBasedDurationMs
        ).coerceAtLeast(30_000L)

        return splitWithMediaExtractorMuxer(
            mediaFile = mediaFile,
            fallbackDurationMs = durationMs,
            targetChunkDurationMs = targetChunkDurationMs,
            overlapMs = overlapMs.coerceIn(0L, targetChunkDurationMs / 4L),
            maxPartBytes = maxPartBytes,
            workDir = workDir
        )
    }

    private fun splitWithMediaExtractorMuxer(
        mediaFile: File,
        fallbackDurationMs: Long,
        targetChunkDurationMs: Long,
        overlapMs: Long,
        maxPartBytes: Long,
        workDir: File
    ): SegmentationPlanResult {
        val extractor = MediaExtractor()
        val createdTemps = mutableListOf<File>()
        try {
            extractor.setDataSource(mediaFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var audioMime = ""
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    audioMime = mime
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                throw MediaSegmentationException(
                    "O arquivo não possui faixa de áudio compatível para segmentação."
                )
            }

            extractor.selectTrack(audioTrackIndex)
            val totalDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(fallbackDurationMs * 1000L)
            } else {
                fallbackDurationMs * 1000L
            }
            if (totalDurationUs <= 0L) {
                throw MediaSegmentationException(
                    "Não foi possível determinar a duração da mídia para dividir em partes menores que 10 minutos."
                )
            }

            val maxInputSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()

            val targetChunkUs = targetChunkDurationMs * 1000L
            val overlapUs = overlapMs * 1000L

            val chunks = mutableListOf<AudioChunkSpec>()
            var partIndex = 0
            var seekTargetUs = 0L
            var baselineStartUs: Long? = null

            while (seekTargetUs < totalDurationUs) {
                extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val firstSampleUs = extractor.sampleTime
                if (firstSampleUs < 0L) break
                if (baselineStartUs == null) {
                    baselineStartUs = firstSampleUs.coerceAtLeast(0L)
                }

                val outFile = File(workDir, "chunk_${System.nanoTime()}_$partIndex.m4a")
                createdTemps.add(outFile)

                var muxer: MediaMuxer? = null
                var partEndUs = firstSampleUs
                var samplesWritten = 0
                val hardLimitUs = firstSampleUs + targetChunkUs

                try {
                    muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val dstTrack = muxer.addTrack(audioFormat)
                    muxer.start()

                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            partEndUs = totalDurationUs
                            break
                        }
                        val sampleUs = extractor.sampleTime
                        if (sampleUs < 0L) break
                        if (sampleUs > hardLimitUs && samplesWritten > 0) {
                            partEndUs = sampleUs
                            break
                        }
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = (sampleUs - firstSampleUs).coerceAtLeast(0L)
                        bufferInfo.flags = mapExtractorSampleFlagsToCodecBufferFlags(extractor.sampleFlags)
                        muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                        samplesWritten++
                        partEndUs = sampleUs
                        if (!extractor.advance()) {
                            partEndUs = totalDurationUs
                            break
                        }
                    }
                } catch (t: Throwable) {
                    createdTemps.forEach { runCatching { it.delete() } }
                    throw MediaSegmentationException(
                        "Não foi possível dividir este formato de áudio ($audioMime) automaticamente (${t.localizedMessage ?: "codec incompatível no contêiner MP4"}). Use .m4a/.mp4/.wav ou selecione um trecho menor.",
                        t
                    )
                } finally {
                    runCatching { muxer?.stop() }
                    runCatching { muxer?.release() }
                }

                if (samplesWritten == 0 || !outFile.exists() || outFile.length() == 0L) {
                    runCatching { outFile.delete() }
                    break
                }

                if (outFile.length() > maxPartBytes) {
                    createdTemps.forEach { runCatching { it.delete() } }
                    throw MediaSegmentationException(
                        "Um trecho segmentado ultrapassou o limite de ${maxPartBytes / (1024 * 1024)} MB. Reduza o bitrate ou escolha um arquivo menor."
                    )
                }

                val offsetMs = ((firstSampleUs - (baselineStartUs ?: 0L)).coerceAtLeast(0L)) / 1000L
                val partDurationMs = ((partEndUs - firstSampleUs).coerceAtLeast(0L)) / 1000L
                chunks.add(
                    AudioChunkSpec(
                        index = partIndex,
                        file = outFile,
                        mimeType = "audio/mp4",
                        offsetMs = offsetMs,
                        durationMs = partDurationMs,
                        isTemporary = true
                    )
                )
                partIndex++

                if (partEndUs >= totalDurationUs) break
                val nextSeek = (partEndUs - overlapUs).coerceAtLeast(firstSampleUs + 1_000_000L)
                if (nextSeek <= seekTargetUs) break
                seekTargetUs = nextSeek
            }

            if (chunks.isEmpty()) {
                createdTemps.forEach { runCatching { it.delete() } }
                throw MediaSegmentationException("Nenhum trecho de áudio pôde ser extraído para transcrição.")
            }

            return SegmentationPlanResult(chunks = chunks, cleanupFiles = createdTemps)
        } catch (se: MediaSegmentationException) {
            throw se
        } catch (t: Throwable) {
            createdTemps.forEach { runCatching { it.delete() } }
            throw MediaSegmentationException(
                "Falha ao preparar segmentação de áudio/vídeo (${t.localizedMessage ?: "formato não suportado"}).",
                t
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun isWavFile(file: File, mimeType: String): Boolean {
        val ext = file.name.lowercase(Locale.US)
        return ext.endsWith(".wav") || mimeType.lowercase(Locale.US).contains("wav")
    }

    internal fun trySplitWavPcm(
        wavFile: File,
        preferredDurationMs: Long,
        overlapMs: Long,
        maxBytesPerChunk: Long,
        workDir: File
    ): SegmentationPlanResult? {
        return runCatching {
            if (!workDir.exists() && !workDir.mkdirs()) return@runCatching null
            FileInputStream(wavFile).use { input ->
                val header = ByteArray(12)
                if (input.read(header) != 12) return@runCatching null
                val riff = String(header, 0, 4, Charsets.US_ASCII)
                val wave = String(header, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") return@runCatching null

                var sampleRate = 0
                var channels = 0
                var bitsPerSample = 0
                var byteRate = 0
                var blockAlign = 0
                var dataSize = -1L
                var dataOffset = -1L
                var pos = 12L

                val chunkHdr = ByteArray(8)
                while (input.read(chunkHdr) == 8) {
                    val id = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                    val len = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    pos += 8L
                    if (id == "fmt " && len >= 16) {
                        val fmtBytes = ByteArray(len.toInt())
                        if (input.read(fmtBytes) != len.toInt()) return@runCatching null
                        val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = bb.short.toInt() and 0xFFFF
                        if (audioFormat != 1) return@runCatching null // PCM only
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        byteRate = bb.int
                        blockAlign = bb.short.toInt() and 0xFFFF
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        pos += len
                    } else if (id == "data") {
                        dataOffset = pos
                        dataSize = len
                        break
                    } else {
                        val skip = if (len % 2L == 1L) len + 1L else len
                        input.skip(skip)
                        pos += skip
                    }
                }

                if (dataOffset < 0 || dataSize <= 0 || byteRate <= 0 || blockAlign <= 0) return@runCatching null

                val maxDataBytesByDuration = ((preferredDurationMs / 1000.0) * byteRate).toLong()
                val maxDataBytesBySize = (maxBytesPerChunk - 44L).coerceAtLeast(blockAlign.toLong())
                val rawChunkDataBytes = minOf(maxDataBytesByDuration, maxDataBytesBySize)
                val chunkDataBytes = (rawChunkDataBytes / blockAlign) * blockAlign
                if (chunkDataBytes <= blockAlign) return@runCatching null

                val overlapBytes = (((overlapMs / 1000.0) * byteRate).toLong() / blockAlign) * blockAlign
                val safeOverlapBytes = overlapBytes.coerceIn(0L, chunkDataBytes / 4L)

                val temps = mutableListOf<File>()
                val chunks = mutableListOf<AudioChunkSpec>()
                var cursor = 0L
                var partIdx = 0

                while (cursor < dataSize) {
                    val toCopy = minOf(chunkDataBytes, dataSize - cursor)
                    val outFile = File(workDir, "wav_chunk_${System.nanoTime()}_$partIdx.wav")
                    temps.add(outFile)

                    FileInputStream(wavFile).use { src ->
                        src.skip(dataOffset + cursor)
                        FileOutputStream(outFile).use { dst ->
                            writeWavHeader(dst, toCopy, channels, sampleRate, bitsPerSample, byteRate, blockAlign)
                            val copyBuf = ByteArray(16 * 1024)
                            var remaining = toCopy
                            while (remaining > 0L) {
                                val read = src.read(copyBuf, 0, minOf(copyBuf.size.toLong(), remaining).toInt())
                                if (read <= 0) break
                                dst.write(copyBuf, 0, read)
                                remaining -= read
                            }
                        }
                    }

                    val offsetMs = ((cursor.toDouble() / byteRate.toDouble()) * 1000.0).toLong()
                    val partDurationMs = ((toCopy.toDouble() / byteRate.toDouble()) * 1000.0).toLong()
                    chunks.add(
                        AudioChunkSpec(
                            index = partIdx,
                            file = outFile,
                            mimeType = "audio/wav",
                            offsetMs = offsetMs,
                            durationMs = partDurationMs,
                            isTemporary = true
                        )
                    )
                    partIdx++
                    if (cursor + toCopy >= dataSize) break
                    cursor = (cursor + toCopy - safeOverlapBytes).coerceAtLeast(cursor + blockAlign)
                }

                SegmentationPlanResult(chunks = chunks, cleanupFiles = temps)
            }
        }.getOrNull()
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        dataLength: Long,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        byteRate: Int,
        blockAlign: Int
    ) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((dataLength + 36L).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLength.toInt())
        out.write(header.array())
    }
}

data class CompletedChunkTranscript(
    val chunkIndex: Int,
    val offsetMs: Long,
    val result: TranscriptionResult
)

/**
 * Merges chunked [TranscriptionResult]s sequentially, offsets each segment timestamp by [offsetMs],
 * and removes overlapping junction duplicates caused by audio overlap between adjacent parts.
 */
object TranscriptMerger {

    fun merge(chunks: List<CompletedChunkTranscript>, overlapWindowSeconds: Double = 15.0): TranscriptionResult {
        if (chunks.isEmpty()) {
            return TranscriptionResult(fullTranscript = "", segments = emptyList(), detectedLanguage = null)
        }
        val sorted = chunks.sortedBy { it.chunkIndex }
        val mergedSegments = mutableListOf<TranscriptSegment>()
        val rawFallbackTexts = mutableListOf<String>()
        var detectedLang: String? = null

        for (chunk in sorted) {
            if (detectedLang.isNullOrBlank()) {
                detectedLang = chunk.result.detectedLanguage
            }
            val offsetSec = chunk.offsetMs / 1000.0
            if (chunk.result.segments.isEmpty()) {
                val cleanText = deduplicatePlainTextTail(
                    previous = rawFallbackTexts.lastOrNull().orEmpty(),
                    next = chunk.result.fullTranscript
                )
                if (cleanText.isNotBlank()) {
                    rawFallbackTexts.add(cleanText)
                }
                continue
            }

            val shifted = chunk.result.segments.map { seg ->
                val start = (seg.startSeconds + offsetSec).coerceAtLeast(0.0)
                val end = (seg.endSeconds + offsetSec).coerceAtLeast(start)
                seg.copy(startSeconds = start, endSeconds = end)
            }

            val boundaryEndSec = mergedSegments.lastOrNull()?.endSeconds ?: -1.0
            for (seg in shifted) {
                if (mergedSegments.isEmpty()) {
                    mergedSegments.add(seg)
                    continue
                }
                val last = mergedSegments.last()
                val nearJunction = seg.startSeconds <= boundaryEndSec + 1.5 &&
                    abs(seg.startSeconds - last.startSeconds) <= overlapWindowSeconds
                if (nearJunction && isDuplicateSegment(mergedSegments.takeLast(3), seg)) {
                    continue
                }
                val trimmedText = deduplicatePlainTextTail(last.text, seg.text)
                if (trimmedText.isNotBlank()) {
                    val safeStart = maxOf(seg.startSeconds, last.startSeconds)
                    val safeEnd = maxOf(seg.endSeconds, safeStart)
                    mergedSegments.add(seg.copy(startSeconds = safeStart, endSeconds = safeEnd, text = trimmedText))
                }
            }
        }

        val fullTranscript = if (mergedSegments.isNotEmpty()) {
            mergedSegments.joinToString("\n") { seg ->
                "[${formatTimestampSeconds(seg.startSeconds)}] ${seg.text}"
            }
        } else {
            rawFallbackTexts.joinToString("\n")
        }

        return TranscriptionResult(
            fullTranscript = fullTranscript,
            segments = mergedSegments,
            detectedLanguage = detectedLang
        )
    }

    private fun isDuplicateSegment(recentSegments: List<TranscriptSegment>, candidate: TranscriptSegment): Boolean {
        val normCandidate = normalizeForCompare(candidate.text)
        if (normCandidate.isBlank()) return true
        return recentSegments.any { prev ->
            val normPrev = normalizeForCompare(prev.text)
            normPrev == normCandidate ||
                (normCandidate.length >= 12 && normPrev.contains(normCandidate)) ||
                (normPrev.length >= 12 && normCandidate.contains(normPrev))
        }
    }

    internal fun deduplicatePlainTextTail(previous: String, next: String): String {
        val nextTrimmed = next.trim()
        if (previous.isBlank() || nextTrimmed.isBlank()) return nextTrimmed

        val prevWords = previous.trim().split(Regex("\\s+"))
        val nextWords = nextTrimmed.split(Regex("\\s+"))
        val maxOverlap = minOf(12, prevWords.size, nextWords.size)
        for (len in maxOverlap downTo 3) {
            val tail = prevWords.takeLast(len).map { normalizeForCompare(it) }
            val head = nextWords.take(len).map { normalizeForCompare(it) }
            if (tail == head) {
                return nextWords.drop(len).joinToString(" ").trim()
            }
        }
        return nextTrimmed
    }

    private fun normalizeForCompare(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
