package com.sjos.folionote.engine.document

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.sjos.folionote.data.local.SourceType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class StoredSourceFile(
    val sourceId: String,
    val displayName: String,
    val mimeType: String,
    val sourceType: SourceType,
    val file: File,
    val sizeBytes: Long,
    val sha256: String
)

data class StorageStats(
    val usedBySourcesBytes: Long,
    val deviceAvailableBytes: Long,
    val deviceTotalBytes: Long
) {
    val isLowStorage: Boolean
        get() = deviceAvailableBytes < 100L * 1024L * 1024L
}

class FileStorageManager(private val context: Context) {

    fun isGoogleDriveUri(uri: Uri): Boolean {
        val authority = uri.authority?.lowercase().orEmpty()
        val full = uri.toString().lowercase()
        return authority.contains("docs.storage") ||
            full.contains("com.google.android.apps.docs")
    }

    fun importFromUri(notebookId: String, uri: Uri): StoredSourceFile {
        val resolver = context.contentResolver
        val rawName = queryDisplayName(uri) ?: "documento_${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri) ?: guessMimeFromName(rawName)
        val sourceType = detectSourceType(rawName, mimeType)
        val sourceId = UUID.randomUUID().toString()

        val targetDir = notebookSourcesDir(notebookId)
        val safeName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(targetDir, "${sourceId}_$safeName")

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output, bufferSize = 16 * 1024)
            }
        } ?: throw IllegalStateException("Não foi possível abrir o arquivo selecionado.")

        val sha256 = computeSha256(targetFile)
        return StoredSourceFile(
            sourceId = sourceId,
            displayName = rawName,
            mimeType = mimeType,
            sourceType = sourceType,
            file = targetFile,
            sizeBytes = targetFile.length(),
            sha256 = sha256
        )
    }

    fun importFromLocalFile(
        notebookId: String,
        sourceFile: File,
        displayName: String,
        mimeType: String
    ): StoredSourceFile {
        require(sourceFile.exists()) { "Arquivo baixado não encontrado." }
        val resolvedName = displayName.trim().ifBlank { sourceFile.name }
        val resolvedMime = mimeType.trim().ifBlank { guessMimeFromName(resolvedName) }
        val sourceType = detectSourceType(resolvedName, resolvedMime)
        val sourceId = UUID.randomUUID().toString()

        val targetDir = notebookSourcesDir(notebookId)
        val safeName = resolvedName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(targetDir, "${sourceId}_$safeName")

        sourceFile.inputStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output, bufferSize = 16 * 1024)
            }
        }

        val sha256 = computeSha256(targetFile)
        return StoredSourceFile(
            sourceId = sourceId,
            displayName = resolvedName,
            mimeType = resolvedMime,
            sourceType = sourceType,
            file = targetFile,
            sizeBytes = targetFile.length(),
            sha256 = sha256
        )
    }

    fun createTextNoteFile(notebookId: String, title: String, content: String): StoredSourceFile {
        val sourceId = UUID.randomUUID().toString()
        val safeTitle = title.trim().ifBlank { "Nota" }
        val safeName = safeTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".md"
        val targetFile = File(notebookSourcesDir(notebookId), "${sourceId}_$safeName")
        targetFile.writeText(content, Charsets.UTF_8)
        return StoredSourceFile(
            sourceId = sourceId,
            displayName = safeTitle,
            mimeType = "text/markdown",
            sourceType = SourceType.NOTE,
            file = targetFile,
            sizeBytes = targetFile.length(),
            sha256 = computeSha256(targetFile)
        )
    }

    fun createWebClipFile(
        notebookId: String,
        title: String,
        markdownContent: String
    ): StoredSourceFile {
        val sourceId = UUID.randomUUID().toString()
        val safeTitle = title.trim().ifBlank { "Página Web" }
        val safeName = safeTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(48) + ".md"
        val targetFile = File(notebookSourcesDir(notebookId), "${sourceId}_$safeName")
        targetFile.writeText(markdownContent, Charsets.UTF_8)
        return StoredSourceFile(
            sourceId = sourceId,
            displayName = safeTitle,
            mimeType = "text/markdown",
            sourceType = SourceType.WEB_PAGE,
            file = targetFile,
            sizeBytes = targetFile.length(),
            sha256 = computeSha256(targetFile)
        )
    }

    fun ensureNotebookSourcesDir(notebookId: String): File = notebookSourcesDir(notebookId)

    fun deleteSourceFile(filePath: String) {
        if (filePath.isBlank()) return
        runCatching { File(filePath).delete() }
    }

    fun deleteNotebookDirectory(notebookId: String) {
        val dir = File(context.filesDir, "notebooks/$notebookId")
        runCatching { dir.deleteRecursively() }
    }

    fun getStorageStats(usedBySourcesBytes: Long): StorageStats {
        val statFs = StatFs(context.filesDir.absolutePath)
        return StorageStats(
            usedBySourcesBytes = usedBySourcesBytes,
            deviceAvailableBytes = statFs.availableBytes,
            deviceTotalBytes = statFs.totalBytes
        )
    }

    private fun notebookSourcesDir(notebookId: String): File {
        val dir = File(context.filesDir, "notebooks/$notebookId/sources")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }

    private fun guessMimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            lower.endsWith(".epub") -> "application/epub+zip"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".aac") -> "audio/aac"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".md") || lower.endsWith(".markdown") -> "text/markdown"
            else -> "text/plain"
        }
    }

    fun detectSourceType(name: String, mimeType: String): SourceType {
        val lowerName = name.lowercase()
        val lowerMime = mimeType.lowercase()
        return when {
            lowerMime == "application/pdf" || lowerName.endsWith(".pdf") -> SourceType.PDF
            lowerMime.contains("wordprocessingml") || lowerName.endsWith(".docx") -> SourceType.DOCX
            lowerMime.contains("presentationml") || lowerName.endsWith(".pptx") -> SourceType.PPTX
            lowerMime == "application/epub+zip" || lowerName.endsWith(".epub") -> SourceType.EPUB
            lowerMime.startsWith("image/") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") ||
                lowerName.endsWith(".bmp") || lowerName.endsWith(".gif") ||
                lowerName.endsWith(".heic") -> SourceType.IMAGE
            lowerMime.startsWith("audio/") ||
                lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a") ||
                lowerName.endsWith(".aac") || lowerName.endsWith(".wav") ||
                lowerName.endsWith(".ogg") || lowerName.endsWith(".flac") ||
                lowerName.endsWith(".opus") -> SourceType.AUDIO
            lowerMime.startsWith("video/") ||
                lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                lowerName.endsWith(".webm") || lowerName.endsWith(".mov") ||
                lowerName.endsWith(".3gp") -> SourceType.VIDEO
            lowerMime == "text/html" || lowerName.endsWith(".html") || lowerName.endsWith(".htm") -> SourceType.WEB_PAGE
            lowerMime.contains("markdown") || lowerName.endsWith(".md") || lowerName.endsWith(".markdown") -> SourceType.MARKDOWN
            else -> SourceType.TXT
        }
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
