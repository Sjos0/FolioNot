package com.sjos.folionote.engine.document

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.TranscriptionProviderType
import com.sjos.folionote.core.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

data class MediaMetadataInfo(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "",
    val title: String = "",
    val artist: String = ""
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
            MediaMetadataInfo(
                durationMs = duration.coerceAtLeast(0L),
                width = width,
                height = height,
                mimeType = mime,
                title = title,
                artist = artist
            )
        } catch (_: Throwable) {
            MediaMetadataInfo()
        } finally {
            runCatching { retriever.release() }
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
                bufferInfo.flags = extractor.sampleFlags
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
}

/**
 * Decoupled external audio/video transcription engine (OpenAI Whisper-compatible / custom REST).
 * Never runs local AI models or fabricates transcripts when unconfigured.
 */
class ExternalAudioTranscriptionEngine(
    private val preferencesStore: AppPreferencesStore,
    private val cryptoManager: KeystoreCryptoManager,
    private val baseHttpClient: OkHttpClient = OkHttpClient()
) : AudioTranscriptionEngine {

    override suspend fun transcribe(mediaFile: File, mimeType: String): Result<TranscriptionResult> =
        withContext(Dispatchers.IO) {
            val settings = preferencesStore.settingsFlow.first()
            if (!settings.hasTranscriptionConfigured) {
                return@withContext Result.failure(
                    TranscriptionNotConfiguredException(
                        "Provedor externo de transcrição não configurado. Configure endpoint e chave nas Configurações para transcrever áudio e vídeo."
                    )
                )
            }

            val endpoint = settings.transcriptionEndpointUrl.trim()
            val plainKey = cryptoManager.decrypt(settings.transcriptionEncryptedApiKey).trim()
            val safeMime = mimeType.ifBlank { "audio/mpeg" }

            runCatching {
                val client = baseHttpClient.newBuilder()
                    .readTimeout(180, TimeUnit.SECONDS)
                    .writeTimeout(180, TimeUnit.SECONDS)
                    .build()

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        mediaFile.name,
                        mediaFile.asRequestBody(safeMime.toMediaType())
                    )
                    .addFormDataPart("model", settings.transcriptionModelId.ifBlank { "whisper-1" })
                    .addFormDataPart("response_format", "verbose_json")
                    .apply {
                        if (settings.transcriptionLanguage.isNotBlank()) {
                            addFormDataPart("language", settings.transcriptionLanguage)
                        }
                    }
                    .build()

                val reqBuilder = Request.Builder()
                    .url(endpoint)
                    .post(multipart)

                if (plainKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $plainKey")
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Transcrição HTTP ${response.code}: ${raw.take(220)}")
                    }
                    parseTranscriptionResponse(settings.transcriptionProviderType, raw)
                }
            }
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
                    segments.add(
                        TranscriptSegment(
                            startSeconds = item.optDouble("start", 0.0).coerceAtLeast(0.0),
                            endSeconds = item.optDouble("end", 0.0).coerceAtLeast(0.0),
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
