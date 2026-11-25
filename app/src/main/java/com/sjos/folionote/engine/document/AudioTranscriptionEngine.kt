package com.sjos.folionote.engine.document

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.media.MediaMuxer
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.preferences.TranscriptionProviderType
import com.sjos.folionote.core.security.KeystoreCryptoManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MediaMetadataInfo(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "",
    val title: String = "",
    val artist: String = "",
    val hasAudioTrack: Boolean? = null
) {
    val formattedDuration: String
        get() = formatTimestampMs(durationMs)
}

data class TranscriptSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String
)

data class TranscriptionResult(
    val fullTranscript: String,
    val segments: List<TranscriptSegment>,
    val detectedLanguage: String? = null
)

class TranscriptionNotConfiguredException(message: String) : IllegalStateException(message)

class TranscriptionHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null,
    message: String
) : IOException(message)

class PartialTranscriptionException(
    message: String,
    val failedChunkIndex: Int,
    val totalChunks: Int,
    val completedChunks: List<CompletedChunkTranscript>,
    val mergedPartialResult: TranscriptionResult,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

interface MediaMetadataReader {
    fun readMetadata(file: File): MediaMetadataInfo
    fun extractAudioTrack(videoFile: File, outputFile: File): Boolean
}

class AndroidMediaMetadataReader : MediaMetadataReader {
    override fun readMetadata(file: File): MediaMetadataInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val hasAudioFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            } else {
                null
            }
            val detectedAudioTrack = if (hasAudioFlag.equals("yes", ignoreCase = true)) {
                true
            } else {
                probeHasAudioTrack(file)
            }
            MediaMetadataInfo(
                durationMs = duration.coerceAtLeast(0L),
                width = width,
                height = height,
                mimeType = mime,
                title = title,
                artist = artist,
                hasAudioTrack = detectedAudioTrack
            )
        } catch (_: Throwable) {
            MediaMetadataInfo()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun probeHasAudioTrack(file: File): Boolean? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    override fun extractAudioTrack(videoFile: File, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) return false

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                256 * 1024
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstTrack = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.flags = mapExtractorSampleFlagsToCodecBufferFlags(extractor.sampleFlags)
                muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                extractor.advance()
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}

interface AudioTranscriptionEngine {
    suspend fun transcribe(mediaFile: File, mimeType: String): Result<TranscriptionResult>

    suspend fun transcribeWithProgress(
        mediaFile: File,
        mimeType: String,
        metadata: MediaMetadataInfo = MediaMetadataInfo(),
        onProgress: suspend (completedParts: Int, totalParts: Int, statusLabel: String) -> Unit = { _, _, _ -> }
    ): Result<TranscriptionResult> = transcribe(mediaFile, mimeType)
}

/**
 * External audio/video transcription engine supporting Groq Whisper (`whisper-large-v3-turbo`,
 * `whisper-large-v3`), OpenAI Audio/Whisper-compatible providers, and custom REST endpoints.
 * Handles 10-minute operational chunk limits, 25 MB free-tier size validation, sequential chunk
 * processing with overlap deduplication, HTTP rate-limit/error mapping, and partial-progress resume.
 */
class ExternalAudioTranscriptionEngine(
    private val preferencesStore: AppPreferencesStore,
    private val cryptoManager: KeystoreCryptoManager,
    private val baseHttpClient: OkHttpClient = OkHttpClient(),
    private val mediaMetadataReader: MediaMetadataReader = AndroidMediaMetadataReader(),
    private val mediaSegmenter: MediaSegmenter = AndroidMediaExtractorSegmenter(),
    private val allowLoopbackHttpForTests: Boolean = false
) : AudioTranscriptionEngine {

    override suspend fun transcribe(mediaFile: File, mimeType: String): Result<TranscriptionResult> {
        val metadata = mediaMetadataReader.readMetadata(mediaFile)
        return transcribeWithProgress(mediaFile, mimeType, metadata)
    }

    override suspend fun transcribeWithProgress(
        mediaFile: File,
        mimeType: String,
        metadata: MediaMetadataInfo,
        onProgress: suspend (completedParts: Int, totalParts: Int, statusLabel: String) -> Unit
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        val settings = preferencesStore.settingsFlow.first()
        if (!settings.hasTranscriptionConfigured) {
            val providerHint = if (settings.transcriptionProviderType == TranscriptionProviderType.GROQ_WHISPER) {
                "Configure sua chave pessoal da Groq nas Configurações para transcrever áudio e vídeo."
            } else {
                "Provedor externo de transcrição não configurado. Configure endpoint e chave nas Configurações para transcrever áudio e vídeo."
            }
            return@withContext Result.failure(TranscriptionNotConfiguredException(providerHint))
        }

        val endpoint = settings.transcriptionEndpointUrl.trim()
        if (settings.transcriptionProviderType == TranscriptionProviderType.GROQ_WHISPER &&
            !isSecureGroqEndpoint(endpoint)
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("O endpoint da Groq precisa usar HTTPS para proteger sua chave de API.")
            )
        }
        val plainKey = cryptoManager.decrypt(settings.transcriptionEncryptedApiKey).trim()
        if (settings.transcriptionProviderType != TranscriptionProviderType.CUSTOM_TRANSCRIPTION_ENDPOINT &&
            plainKey.isBlank()
        ) {
            return@withContext Result.failure(
                TranscriptionNotConfiguredException(
                    "Nenhuma chave de API configurada para ${settings.transcriptionProviderType.label}."
                )
            )
        }

        val safeMime = mimeType.ifBlank { metadata.mimeType.ifBlank { "audio/mpeg" } }
        val effectiveMeta = if (metadata.durationMs > 0L) metadata else mediaMetadataReader.readMetadata(mediaFile)
        val isGroq = settings.transcriptionProviderType == TranscriptionProviderType.GROQ_WHISPER
        val maxDurationLimitMs = if (isGroq) TranscriptionLimits.GROQ_MAX_PART_DURATION_MS else Long.MAX_VALUE
        val maxPartBytes = TranscriptionLimits.FREE_TIER_MAX_FILE_BYTES
        val tempWorkDir = File(mediaFile.parentFile ?: mediaFile.absoluteFile.parentFile, ".folionote_transcribe_tmp")

        val plan = try {
            if (isGroq || mediaFile.length() > maxPartBytes) {
                mediaSegmenter.segmentIfNeeded(
                    mediaFile = mediaFile,
                    mimeType = safeMime,
                    metadata = effectiveMeta,
                    maxPartDurationMs = maxDurationLimitMs,
                    preferredPartDurationMs = TranscriptionLimits.GROQ_PREFERRED_PART_DURATION_MS,
                    overlapMs = TranscriptionLimits.GROQ_PART_OVERLAP_MS,
                    maxPartBytes = maxPartBytes,
                    workDir = tempWorkDir
                )
            } else {
                SegmentationPlanResult(
                    chunks = listOf(
                        AudioChunkSpec(
                            index = 0,
                            file = mediaFile,
                            mimeType = safeMime,
                            offsetMs = 0L,
                            durationMs = effectiveMeta.durationMs,
                            isTemporary = false
                        )
                    ),
                    cleanupFiles = emptyList()
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext Result.failure(t)
        }

        val checkpointFile = resolveCheckpointFile(mediaFile, settings, plan.chunks.size)
        val completedMap = loadCheckpoint(checkpointFile, plan.chunks.size).toMutableMap()

        try {
            val totalParts = plan.chunks.size
            for (chunk in plan.chunks) {
                currentCoroutineContext().ensureActive()
                if (completedMap.containsKey(chunk.index)) {
                    onProgress(
                        completedMap.size,
                        totalParts,
                        "Parte ${chunk.index + 1} de $totalParts já concluída; retomando..."
                    )
                    continue
                }

                if (chunk.file.length() > maxPartBytes) {
                    val overSizeErr = TranscriptionHttpException(
                        statusCode = 413,
                        message = "A parte ${chunk.index + 1} tem ${formatMb(chunk.file.length())} e excede o limite de 25 MB por envio. Compacte ou divida o arquivo."
                    )
                    return@withContext buildFailureOrPartial(
                        failedChunkIndex = chunk.index,
                        totalParts = totalParts,
                        completed = completedMap.values.sortedBy { it.chunkIndex },
                        cause = overSizeErr
                    )
                }

                onProgress(
                    completedMap.size,
                    totalParts,
                    if (totalParts > 1) {
                        "Transcrevendo parte ${chunk.index + 1} de $totalParts (${settings.transcriptionProviderType.label})..."
                    } else {
                        "Transcrevendo mídia (${settings.transcriptionProviderType.label})..."
                    }
                )

                val chunkRes = try {
                    uploadChunkWithTransientRetry(
                        chunkFile = chunk.file,
                        mimeType = chunk.mimeType,
                        endpoint = endpoint,
                        plainKey = plainKey,
                        settings = settings
                    )
                } catch (ce: CancellationException) {
                    if (completedMap.isNotEmpty()) {
                        saveCheckpoint(checkpointFile, completedMap.values.sortedBy { it.chunkIndex })
                    }
                    throw ce
                }

                if (chunkRes.isSuccess) {
                    val completed = CompletedChunkTranscript(
                        chunkIndex = chunk.index,
                        offsetMs = chunk.offsetMs,
                        result = chunkRes.getOrThrow()
                    )
                    completedMap[chunk.index] = completed
                    if (totalParts > 1) {
                        saveCheckpoint(checkpointFile, completedMap.values.sortedBy { it.chunkIndex })
                    }
                    onProgress(
                        completedMap.size,
                        totalParts,
                        if (totalParts > 1) {
                            "Concluída parte ${chunk.index + 1} de $totalParts"
                        } else {
                            "Transcrição concluída"
                        }
                    )
                } else {
                    if (completedMap.isNotEmpty()) {
                        saveCheckpoint(checkpointFile, completedMap.values.sortedBy { it.chunkIndex })
                    }
                    return@withContext buildFailureOrPartial(
                        failedChunkIndex = chunk.index,
                        totalParts = totalParts,
                        completed = completedMap.values.sortedBy { it.chunkIndex },
                        cause = chunkRes.exceptionOrNull() ?: IllegalStateException("Falha na transcrição.")
                    )
                }
            }

            runCatching { checkpointFile.delete() }
            val ordered = completedMap.values.sortedBy { it.chunkIndex }
            val merged = if (ordered.size == 1 && ordered.first().offsetMs == 0L) {
                ordered.first().result
            } else {
                TranscriptMerger.merge(ordered)
            }
            Result.success(merged)
        } finally {
            plan.cleanupFiles.forEach { tmp -> runCatching { tmp.delete() } }
            if (tempWorkDir.exists() && tempWorkDir.listFiles().isNullOrEmpty()) {
                runCatching { tempWorkDir.delete() }
            }
        }
    }

    private fun buildFailureOrPartial(
        failedChunkIndex: Int,
        totalParts: Int,
        completed: List<CompletedChunkTranscript>,
        cause: Throwable
    ): Result<TranscriptionResult> {
        if (completed.isNotEmpty() && totalParts > 1) {
            val partialMerged = TranscriptMerger.merge(completed)
            val reason = cause.message ?: "Erro ao transcrever parte ${failedChunkIndex + 1}."
            return Result.failure(
                PartialTranscriptionException(
                    message = "Transcrição parcial (${completed.size}/$totalParts partes salvas). Falha na parte ${failedChunkIndex + 1}: $reason",
                    failedChunkIndex = failedChunkIndex,
                    totalChunks = totalParts,
                    completedChunks = completed,
                    mergedPartialResult = partialMerged,
                    cause = cause
                )
            )
        }
        return Result.failure(cause)
    }

    private fun isSecureGroqEndpoint(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val scheme = uri.scheme ?: return false
        if (uri.userInfo != null || uri.fragment != null) return false
        if (scheme.equals("https", ignoreCase = true)) return true
        val loopbackHost = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        return allowLoopbackHttpForTests && scheme.equals("http", ignoreCase = true) && loopbackHost
    }

    private suspend fun uploadChunkWithTransientRetry(
        chunkFile: File,
        mimeType: String,
        endpoint: String,
        plainKey: String,
        settings: AppSettings
    ): Result<TranscriptionResult> {
        val maxAttempts = 2
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            val attemptResult = runCatching {
                executeMultipartRequest(
                    chunkFile = chunkFile,
                    mimeType = mimeType,
                    endpoint = endpoint,
                    plainKey = plainKey,
                    settings = settings
                )
            }
            if (attemptResult.isSuccess) return attemptResult
            val err = attemptResult.exceptionOrNull()!!
            if (err is CancellationException) throw err
            lastError = err

            val shouldRetry = when (err) {
                is TranscriptionHttpException -> err.statusCode in listOf(502, 503, 504)
                is IOException -> true
                else -> false
            }
            if (!shouldRetry || attempt >= maxAttempts) {
                break
            }
            delay(1_200L)
        }
        return Result.failure(lastError ?: IllegalStateException("Falha desconhecida ao transcrever mídia."))
    }

    private suspend fun executeMultipartRequest(
        chunkFile: File,
        mimeType: String,
        endpoint: String,
        plainKey: String,
        settings: AppSettings
    ): TranscriptionResult {
        val client = baseHttpClient.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()

        val defaultModel = if (settings.transcriptionProviderType == TranscriptionProviderType.GROQ_WHISPER) {
            TranscriptionLimits.GROQ_DEFAULT_MODEL
        } else {
            "whisper-1"
        }
        val resolvedModel = settings.transcriptionModelId.ifBlank { defaultModel }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                chunkFile.name,
                chunkFile.asRequestBody(mimeType.toMediaType())
            )
            .addFormDataPart("model", resolvedModel)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply {
                if (settings.transcriptionLanguage.isNotBlank()) {
                    addFormDataPart("language", settings.transcriptionLanguage.trim())
                }
            }
            .build()

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(multipart)

        if (plainKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $plainKey")
        }

        val response = awaitCancellableCall(client.newCall(reqBuilder.build()))
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw mapHttpError(
                    code = resp.code,
                    retryAfterHeader = resp.header("Retry-After"),
                    rawBody = raw,
                    providerLabel = settings.transcriptionProviderType.label
                )
            }
            return parseTranscriptionResponse(settings.transcriptionProviderType, raw)
        }
    }

    private suspend fun awaitCancellableCall(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    internal fun mapHttpError(
        code: Int,
        retryAfterHeader: String?,
        rawBody: String,
        providerLabel: String
    ): TranscriptionHttpException {
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        val apiDetail = extractApiErrorMessage(rawBody)
        val msg = when (code) {
            401 -> "Chave de API inválida ou expirada para $providerLabel (HTTP 401). Verifique sua chave nas Configurações."
            413 -> "Arquivo acima do limite aceito pelo serviço ou plano atual (HTTP 413). Reduza o arquivo ou divida a mídia e tente novamente."
            422 -> "Formato de mídia ou parâmetro recusado por $providerLabel (HTTP 422)${if (apiDetail.isNotBlank()) ": $apiDetail" else "."}"
            429 -> buildString {
                append("Limite de cota/requisições atingido em $providerLabel (HTTP 429).")
                if (retryAfterSeconds != null) {
                    append(" Aguarde ${retryAfterSeconds}s antes de retomar.")
                } else {
                    append(" Aguarde alguns instantes e tente novamente.")
                }
            }
            in 500..599 -> "Serviço de transcrição temporariamente indisponível ($providerLabel • HTTP $code)."
            else -> "Transcrição HTTP $code${if (apiDetail.isNotBlank()) ": $apiDetail" else ""}"
        }
        return TranscriptionHttpException(
            statusCode = code,
            retryAfterSeconds = retryAfterSeconds,
            message = msg
        )
    }

    private fun extractApiErrorMessage(rawBody: String): String {
        return runCatching {
            val obj = JSONObject(rawBody)
            obj.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { obj.optString("message") }
        }.getOrDefault(rawBody.take(160)).trim()
    }

    internal fun parseTranscriptionResponse(
        providerType: TranscriptionProviderType,
        raw: String
    ): TranscriptionResult {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            return TranscriptionResult(fullTranscript = trimmed, segments = emptyList())
        }
        val root = JSONObject(trimmed)
        val fullText = root.optString("text").ifBlank { root.optString("transcript") }.trim()
        val lang = root.optString("language").takeIf { it.isNotBlank() }
        val segArray = root.optJSONArray("segments")
        val segments = mutableListOf<TranscriptSegment>()
        if (segArray != null) {
            for (i in 0 until segArray.length()) {
                val item = segArray.optJSONObject(i) ?: continue
                val segText = item.optString("text").trim()
                if (segText.isNotEmpty()) {
                    val start = item.optDouble("start", 0.0).coerceAtLeast(0.0)
                    val end = item.optDouble("end", start).coerceAtLeast(start)
                    segments.add(
                        TranscriptSegment(
                            startSeconds = start,
                            endSeconds = end,
                            text = segText
                        )
                    )
                }
            }
        }
        val formattedText = if (segments.isNotEmpty()) {
            segments.joinToString("\n") { seg ->
                "[${formatTimestampSeconds(seg.startSeconds)}] ${seg.text}"
            }
        } else {
            fullText
        }
        return TranscriptionResult(
            fullTranscript = formattedText,
            segments = segments,
            detectedLanguage = lang
        )
    }

    private fun resolveCheckpointFile(mediaFile: File, settings: AppSettings, totalParts: Int): File {
        val signature = "${mediaFile.name}|${mediaFile.length()}|${mediaFile.lastModified()}|" +
            "${settings.transcriptionProviderType.name}|${settings.transcriptionModelId}|" +
            "${settings.transcriptionLanguage}|$totalParts"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return File(mediaFile.parentFile ?: mediaFile.absoluteFile.parentFile, ".folionote_tr_ckpt_$digest.json")
    }

    private fun loadCheckpoint(file: File, totalParts: Int): Map<Int, CompletedChunkTranscript> {
        if (totalParts <= 1 || !file.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val parts = root.optJSONArray("parts") ?: return emptyMap()
            val map = linkedMapOf<Int, CompletedChunkTranscript>()
            for (i in 0 until parts.length()) {
                val item = parts.optJSONObject(i) ?: continue
                val idx = item.optInt("index", -1)
                if (idx !in 0 until totalParts) continue
                val offsetMs = item.optLong("offsetMs", 0L)
                val lang = item.optString("lang").takeIf { it.isNotBlank() }
                val text = item.optString("text", "")
                val segArr = item.optJSONArray("segments") ?: JSONArray()
                val segs = mutableListOf<TranscriptSegment>()
                for (s in 0 until segArr.length()) {
                    val so = segArr.optJSONObject(s) ?: continue
                    segs.add(
                        TranscriptSegment(
                            startSeconds = so.optDouble("start", 0.0),
                            endSeconds = so.optDouble("end", 0.0),
                            text = so.optString("text", "")
                        )
                    )
                }
                map[idx] = CompletedChunkTranscript(
                    chunkIndex = idx,
                    offsetMs = offsetMs,
                    result = TranscriptionResult(
                        fullTranscript = text,
                        segments = segs,
                        detectedLanguage = lang
                    )
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun saveCheckpoint(file: File, completed: List<CompletedChunkTranscript>) {
        runCatching {
            val arr = JSONArray()
            completed.forEach { item ->
                val segArr = JSONArray()
                item.result.segments.forEach { seg ->
                    segArr.put(
                        JSONObject()
                            .put("start", seg.startSeconds)
                            .put("end", seg.endSeconds)
                            .put("text", seg.text)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("index", item.chunkIndex)
                        .put("offsetMs", item.offsetMs)
                        .put("lang", item.result.detectedLanguage ?: "")
                        .put("text", item.result.fullTranscript)
                        .put("segments", segArr)
                )
            }
            file.writeText(JSONObject().put("parts", arr).toString(), Charsets.UTF_8)
        }
    }

    private fun formatMb(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}

fun formatTimestampMs(ms: Long): String {
    if (ms <= 0L) return "00:00"
    return formatTimestampSeconds(ms / 1000.0)
}

fun formatTimestampSeconds(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hrs = total / 3600L
    val mins = (total % 3600L) / 60L
    val secs = total % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
