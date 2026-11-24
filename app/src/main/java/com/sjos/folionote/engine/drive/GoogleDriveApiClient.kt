package com.sjos.folionote.engine.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class DriveFileItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedTime: String,
    val modifiedEpochMs: Long,
    val md5Checksum: String? = null,
    val appProperties: Map<String, String> = emptyMap()
)

/**
 * Google Drive v3 REST client scoped to `appDataFolder` + app-created files.
 * Supports chunked resumable uploads (HTTP 308 Range resume), Range-based partial downloads,
 * listing, renaming, and deleting files. Base URLs are injectable for local HttpServer tests.
 */
class GoogleDriveApiClient(
    private val baseHttpClient: OkHttpClient = OkHttpClient(),
    private val driveApiBaseUrl: String = "https://www.googleapis.com/drive/v3",
    private val driveUploadBaseUrl: String = "https://www.googleapis.com/upload/drive/v3"
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun listBackups(accessToken: String): Result<List<DriveFileItem>> {
        val query = "'appDataFolder' in parents and trashed = false and name contains 'folionote_backup'"
        return listAppDataFiles(accessToken = accessToken, queryFilter = query)
            .map { items -> items.sortedByDescending { it.modifiedEpochMs } }
    }

    suspend fun listAppDataFiles(
        accessToken: String,
        queryFilter: String? = "'appDataFolder' in parents and trashed = false",
        pageSize: Int = 100
    ): Result<List<DriveFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = baseHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val urlBuilder = "${driveApiBaseUrl.trimEnd('/')}/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("pageSize", pageSize.coerceIn(1, 500).toString())
                .addQueryParameter("orderBy", "modifiedTime desc")
                .addQueryParameter("fields", "files(id,name,size,modifiedTime,md5Checksum,appProperties)")
            if (!queryFilter.isNullOrBlank()) {
                urlBuilder.addQueryParameter("q", queryFilter)
            }

            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Google Drive HTTP ${resp.code}: ${extractGoogleError(raw)}")
                }
                val root = JSONObject(raw)
                val filesArr = root.optJSONArray("files") ?: return@runCatching emptyList()
                val out = mutableListOf<DriveFileItem>()
                for (i in 0 until filesArr.length()) {
                    val obj = filesArr.optJSONObject(i) ?: continue
                    out.add(parseDriveFile(obj))
                }
                out
            }
        }
    }

    suspend fun findFileByNameInAppData(
        accessToken: String,
        fileName: String
    ): Result<DriveFileItem?> {
        val escaped = fileName.replace("'", "\\'")
        val q = "'appDataFolder' in parents and trashed = false and name = '$escaped'"
        return listAppDataFiles(accessToken = accessToken, queryFilter = q, pageSize = 10)
            .map { it.firstOrNull() }
    }

    suspend fun initiateResumableSession(
        accessToken: String,
        remoteFileName: String,
        mimeType: String,
        totalBytes: Long,
        existingFileId: String? = null,
        appProperties: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val client = baseHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val metadata = JSONObject().apply {
                put("name", remoteFileName)
                if (existingFileId.isNullOrBlank()) {
                    put("parents", org.json.JSONArray().put("appDataFolder"))
                }
                if (appProperties.isNotEmpty()) {
                    val props = JSONObject()
                    appProperties.forEach { (k, v) -> props.put(k, v) }
                    put("appProperties", props)
                }
            }

            val uploadUrl = if (existingFileId.isNullOrBlank()) {
                "${driveUploadBaseUrl.trimEnd('/')}/files?uploadType=resumable&fields=id,name,size,modifiedTime,md5Checksum,appProperties"
            } else {
                "${driveUploadBaseUrl.trimEnd('/')}/files/$existingFileId?uploadType=resumable&fields=id,name,size,modifiedTime,md5Checksum,appProperties"
            }

            val reqBuilder = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", totalBytes.toString())

            val body = metadata.toString().toRequestBody(jsonMediaType)
            val request = if (existingFileId.isNullOrBlank()) {
                reqBuilder.post(body).build()
            } else {
                reqBuilder.patch(body).build()
            }

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    throw IllegalStateException("Falha ao iniciar upload resumable HTTP ${resp.code}: ${extractGoogleError(raw)}")
                }
                resp.header("Location")?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Google Drive não retornou cabeçalho Location para sessão resumable.")
            }
        }
    }

    suspend fun queryUploadedOffset(
        sessionUri: String,
        totalBytes: Long,
        accessToken: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val client = baseHttpClient.newBuilder()
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val emptyBody = ByteArray(0).toRequestBody(null)
            val req = Request.Builder()
                .url(sessionUri)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Range", "bytes */$totalBytes")
                .put(emptyBody)
                .build()

            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200, 201 -> totalBytes
                    308 -> parseRangeUpperBound(resp.header("Range"))?.plus(1L) ?: 0L
                    404, 410 -> throw IllegalStateException("Sessão de upload expirada (HTTP ${resp.code}).")
                    else -> throw IllegalStateException("Falha ao consultar progresso do upload (HTTP ${resp.code}).")
                }
            }
        }
    }

    suspend fun resumableUploadFile(
        accessToken: String,
        localFile: File,
        remoteFileName: String,
        mimeType: String,
        existingFileId: String? = null,
        existingSessionUri: String? = null,
        appProperties: Map<String, String> = emptyMap(),
        chunkSizeBytes: Int = 256 * 1024,
        onSessionCreated: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Result<DriveFileItem> = withContext(Dispatchers.IO) {
        runCatching {
            require(localFile.exists()) { "Arquivo local para upload não encontrado." }
            val totalBytes = localFile.length()
            val client = baseHttpClient.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            var sessionUri = existingSessionUri
            var offset = 0L

            if (!sessionUri.isNullOrBlank()) {
                val offsetCheck = queryUploadedOffset(sessionUri, totalBytes, accessToken)
                if (offsetCheck.isSuccess) {
                    offset = offsetCheck.getOrThrow().coerceIn(0L, totalBytes)
                } else {
                    sessionUri = null
                }
            }

            if (sessionUri.isNullOrBlank()) {
                sessionUri = initiateResumableSession(
                    accessToken = accessToken,
                    remoteFileName = remoteFileName,
                    mimeType = mimeType,
                    totalBytes = totalBytes,
                    existingFileId = existingFileId,
                    appProperties = appProperties
                ).getOrThrow()
                onSessionCreated(sessionUri)
                offset = 0L
            }

            if (totalBytes == 0L) {
                val req = Request.Builder()
                    .url(sessionUri)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Length", "0")
                    .put(ByteArray(0).toRequestBody(mimeType.toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Upload HTTP ${resp.code}: ${extractGoogleError(raw)}")
                    }
                    onProgress(1f)
                    return@runCatching parseDriveFile(JSONObject(raw))
                }
            }

            val safeChunk = chunkSizeBytes.coerceAtLeast(64 * 1024)
            var finalItem: DriveFileItem? = null

            RandomAccessFile(localFile, "r").use { raf ->
                while (offset < totalBytes && finalItem == null) {
                    if (isCancelled()) {
                        throw CancellationException("Upload cancelado pelo usuário.")
                    }
                    val remaining = totalBytes - offset
                    val len = minOf(safeChunk.toLong(), remaining).toInt()
                    val endInclusive = offset + len - 1L
                    val buf = ByteArray(len)
                    raf.seek(offset)
                    raf.readFully(buf)

                    val chunkBody = object : RequestBody() {
                        override fun contentType() = mimeType.toMediaType()
                        override fun contentLength() = len.toLong()
                        override fun writeTo(sink: BufferedSink) {
                            sink.write(buf, 0, len)
                        }
                    }

                    val putReq = Request.Builder()
                        .url(sessionUri)
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Range", "bytes $offset-$endInclusive/$totalBytes")
                        .put(chunkBody)
                        .build()

                    client.newCall(putReq).execute().use { resp ->
                        when (resp.code) {
                            200, 201 -> {
                                val raw = resp.body?.string().orEmpty()
                                finalItem = parseDriveFile(JSONObject(raw))
                                offset = totalBytes
                                onProgress(1f)
                            }
                            308 -> {
                                val rangeEnd = parseRangeUpperBound(resp.header("Range"))
                                offset = if (rangeEnd != null) rangeEnd + 1L else offset + len
                                onProgress((offset.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f))
                            }
                            else -> {
                                val raw = resp.body?.string().orEmpty()
                                throw IllegalStateException("Upload resumable HTTP ${resp.code}: ${extractGoogleError(raw)}")
                            }
                        }
                    }
                }
            }

            finalItem ?: throw IllegalStateException("Upload finalizado sem resposta do Drive.")
        }
    }

    suspend fun downloadFile(
        accessToken: String,
        fileId: String,
        destFile: File,
        resumeIfPartial: Boolean = true,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destFile.parentFile?.mkdirs()
            val partFile = File(destFile.parentFile, "${destFile.name}.part")
            val existingOffset = if (resumeIfPartial && partFile.exists()) partFile.length() else {
                runCatching { partFile.delete() }
                0L
            }

            val client = baseHttpClient.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val url = "${driveApiBaseUrl.trimEnd('/')}/files/$fileId?alt=media"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()

            if (existingOffset > 0L) {
                reqBuilder.header("Range", "bytes=$existingOffset-")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    val raw = resp.body?.string().orEmpty()
                    throw IllegalStateException("Download HTTP ${resp.code}: ${extractGoogleError(raw)}")
                }

                val isPartialAppend = resp.code == 206 && existingOffset > 0L
                val body = resp.body ?: throw IllegalStateException("Resposta de download vazia.")
                val contentLength = body.contentLength()
                val totalExpected = if (isPartialAppend && contentLength > 0L) {
                    existingOffset + contentLength
                } else {
                    contentLength
                }

                FileOutputStream(partFile, isPartialAppend).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        var copied = if (isPartialAppend) existingOffset else 0L
                        while (true) {
                            if (isCancelled()) {
                                throw CancellationException("Download cancelado pelo usuário.")
                            }
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            copied += read
                            if (totalExpected > 0L) {
                                onProgress((copied.toFloat() / totalExpected.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            if (destFile.exists()) destFile.delete()
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
                partFile.delete()
            }
            onProgress(1f)
            destFile
        }
    }

    suspend fun renameFile(
        accessToken: String,
        fileId: String,
        newName: String
    ): Result<DriveFileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = newName.trim()
            require(clean.isNotBlank()) { "O novo nome não pode estar em branco." }
            val client = baseHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val url = "${driveApiBaseUrl.trimEnd('/')}/files/$fileId?fields=id,name,size,modifiedTime,md5Checksum,appProperties"
            val body = JSONObject().put("name", clean).toString().toRequestBody(jsonMediaType)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .patch(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Erro ao renomear backup HTTP ${resp.code}: ${extractGoogleError(raw)}")
                }
                parseDriveFile(JSONObject(raw))
            }
        }
    }

    suspend fun deleteFile(accessToken: String, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val client = baseHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("${driveApiBaseUrl.trimEnd('/')}/files/$fileId")
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 204 && resp.code != 404) {
                    val raw = resp.body?.string().orEmpty()
                    throw IllegalStateException("Erro ao excluir arquivo HTTP ${resp.code}: ${extractGoogleError(raw)}")
                }
            }
        }
    }

    internal fun parseDriveFile(obj: JSONObject): DriveFileItem {
        val props = mutableMapOf<String, String>()
        obj.optJSONObject("appProperties")?.let { p ->
            val keys = p.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                props[k] = p.optString(k, "")
            }
        }
        val modified = obj.optString("modifiedTime", "")
        return DriveFileItem(
            id = obj.optString("id"),
            name = obj.optString("name"),
            sizeBytes = obj.optString("size", "0").toLongOrNull() ?: obj.optLong("size", 0L),
            modifiedTime = modified,
            modifiedEpochMs = parseIso8601Epoch(modified),
            md5Checksum = obj.optString("md5Checksum").takeIf { it.isNotBlank() },
            appProperties = props
        )
    }

    private fun parseRangeUpperBound(rangeHeader: String?): Long? {
        if (rangeHeader.isNullOrBlank()) return null
        val match = Regex("""bytes=\d+-(\d+)""").find(rangeHeader) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun extractGoogleError(raw: String): String {
        return runCatching {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.optString("message").orEmpty().ifBlank { raw.take(160) }
        }.getOrElse { raw.take(160) }
    }

    private fun parseIso8601Epoch(value: String): Long {
        if (value.isBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }
}
