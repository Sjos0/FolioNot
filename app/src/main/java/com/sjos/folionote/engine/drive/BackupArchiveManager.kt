package com.sjos.folionote.engine.drive

import android.content.Context
import androidx.room.withTransaction
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.data.local.CitationEntity
import com.sjos.folionote.data.local.CollectionEntity
import com.sjos.folionote.data.local.ConversationEntity
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.StudioArtifactEntity
import com.sjos.folionote.engine.document.FileStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupSummary(
    val archiveFile: File,
    val createdAt: Long,
    val notebookCount: Int,
    val sourceCount: Int,
    val messageCount: Int,
    val totalBytes: Long
)

data class RestoreSummary(
    val collectionsRestored: Int,
    val notebooksRestored: Int,
    val sourcesRestored: Int,
    val chunksRestored: Int,
    val conversationsRestored: Int,
    val messagesRestored: Int,
    val artifactsRestored: Int,
    val filesCopied: Int
)

/**
 * Builds and restores portable `.zip` backups (`manifest.json`, `database.json`, and `files/...`).
 * Strictly excludes all API keys, OAuth tokens, and passwords.
 */
class BackupArchiveManager(
    private val context: Context,
    private val database: FolioNoteDatabase,
    private val fileStorageManager: FileStorageManager,
    private val preferencesStore: AppPreferencesStore
) {
    companion object {
        const val ARCHIVE_VERSION = 1
        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_DATABASE = "database.json"
    }

    fun defaultBackupFileName(customName: String? = null, nowMs: Long = System.currentTimeMillis()): String {
        val cleaned = customName?.trim()
            ?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            ?.trim('_')
            .orEmpty()
        if (cleaned.isNotBlank()) {
            val base = if (cleaned.startsWith("folionote_backup")) cleaned else "folionote_backup_$cleaned"
            return if (base.endsWith(".zip", ignoreCase = true)) base else "$base.zip"
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        return "folionote_backup_$stamp.zip"
    }

    suspend fun createBackupArchive(
        destFile: File,
        createdAt: Long = System.currentTimeMillis()
    ): BackupSummary = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()

        val nbDao = database.notebookDao()
        val srcDao = database.sourceDao()
        val chunkDao = database.chunkAndEmbeddingDao()
        val convDao = database.conversationAndMessageDao()

        val collections = nbDao.getAllCollectionsOnce()
        val notebooks = nbDao.getAllNotebooksOnce()
        val sources = srcDao.getAllSourcesOnce()
        val chunks = chunkDao.getAllChunksOnce()
        val conversations = convDao.getAllConversationsOnce()
        val messages = convDao.getAllMessagesOnce()
        val citations = convDao.getAllCitationsOnce()
        val artifacts = convDao.getAllStudioArtifactsOnce()
        val nonSecretPrefs = preferencesStore.exportNonSecretPreferencesJson()

        val fileMap = linkedMapOf<String, String>()
        val sourceArray = JSONArray()
        for (src in sources) {
            val localFile = File(src.filePath)
            var archiveRelPath = ""
            if (localFile.exists() && localFile.isFile) {
                val safeName = localFile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                archiveRelPath = "files/${src.notebookId}/$safeName"
                fileMap[src.id] = archiveRelPath
            }
            sourceArray.put(sourceToJson(src, archiveRelPath))
        }

        val dbJson = JSONObject().apply {
            put("archiveVersion", ARCHIVE_VERSION)
            put("exportedAt", createdAt)
            put("collections", JSONArray().apply { collections.forEach { put(collectionToJson(it)) } })
            put("notebooks", JSONArray().apply { notebooks.forEach { put(notebookToJson(it)) } })
            put("sources", sourceArray)
            put("chunks", JSONArray().apply { chunks.forEach { put(chunkToJson(it)) } })
            put("conversations", JSONArray().apply { conversations.forEach { put(conversationToJson(it)) } })
            put("messages", JSONArray().apply { messages.forEach { put(messageToJson(it)) } })
            put("citations", JSONArray().apply { citations.forEach { put(citationToJson(it)) } })
            put("studioArtifacts", JSONArray().apply { artifacts.forEach { put(artifactToJson(it)) } })
            put("preferences", nonSecretPrefs)
        }

        val manifestJson = JSONObject().apply {
            put("archiveVersion", ARCHIVE_VERSION)
            put("appPackage", context.packageName)
            put("createdAt", createdAt)
            put("excludesSecrets", true)
            put("counts", JSONObject().apply {
                put("collections", collections.size)
                put("notebooks", notebooks.size)
                put("sources", sources.size)
                put("chunks", chunks.size)
                put("conversations", conversations.size)
                put("messages", messages.size)
                put("citations", citations.size)
                put("studioArtifacts", artifacts.size)
                put("files", fileMap.size)
            })
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(destFile))).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifestJson.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_DATABASE))
            zip.write(dbJson.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val buffer = ByteArray(16 * 1024)
            for (src in sources) {
                val relPath = fileMap[src.id] ?: continue
                val file = File(src.filePath)
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry(relPath))
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }

        BackupSummary(
            archiveFile = destFile,
            createdAt = createdAt,
            notebookCount = notebooks.size,
            sourceCount = sources.size,
            messageCount = messages.size,
            totalBytes = destFile.length()
        )
    }

    suspend fun restoreFromBackupArchive(archiveFile: File): RestoreSummary = withContext(Dispatchers.IO) {
        require(archiveFile.exists() && archiveFile.length() > 0L) {
            "Arquivo de backup inválido ou vazio."
        }

        var manifestRaw: String? = null
        var databaseRaw: String? = null
        val stagingDir = File(context.cacheDir, "restore_stage_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            val extractedFilesByRel = mutableMapOf<String, File>()
            ZipInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.trimStart('/')
                    if (!entry.isDirectory && !name.contains("..")) {
                        when (name) {
                            ENTRY_MANIFEST -> manifestRaw = readZipText(zip)
                            ENTRY_DATABASE -> databaseRaw = readZipText(zip)
                            else -> if (name.startsWith("files/")) {
                                val outFile = File(stagingDir, name)
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    val buf = ByteArray(16 * 1024)
                                    while (true) {
                                        val read = zip.read(buf)
                                        if (read <= 0) break
                                        out.write(buf, 0, read)
                                    }
                                }
                                extractedFilesByRel[name] = outFile
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }

            if (manifestRaw.isNullOrBlank() || databaseRaw.isNullOrBlank()) {
                throw IllegalStateException("Backup corrompido: manifest.json ou database.json ausentes.")
            }

            val manifest = JSONObject(manifestRaw!!)
            val version = manifest.optInt("archiveVersion", -1)
            if (version <= 0 || version > ARCHIVE_VERSION) {
                throw IllegalStateException("Versão de backup não suportada ($version).")
            }

            val dbJson = JSONObject(databaseRaw!!)
            val collections = parseCollections(dbJson.optJSONArray("collections"))
            val notebooks = parseNotebooks(dbJson.optJSONArray("notebooks"))
            val rawSources = parseSourcesWithRelPaths(dbJson.optJSONArray("sources"))
            val chunks = parseChunks(dbJson.optJSONArray("chunks"))
            val conversations = parseConversations(dbJson.optJSONArray("conversations"))
            val messages = parseMessages(dbJson.optJSONArray("messages"))
            val citations = parseCitations(dbJson.optJSONArray("citations"))
            val artifacts = parseArtifacts(dbJson.optJSONArray("studioArtifacts"))

            var filesCopied = 0
            val restoredSources = rawSources.map { (src, archiveRelPath) ->
                val staged = archiveRelPath.takeIf { it.isNotBlank() }?.let { extractedFilesByRel[it] }
                if (staged != null && staged.exists()) {
                    val targetDir = fileStorageManager.ensureNotebookSourcesDir(src.notebookId)
                    val targetFile = File(targetDir, staged.name)
                    staged.copyTo(targetFile, overwrite = true)
                    filesCopied++
                    src.copy(filePath = targetFile.absolutePath, sizeBytes = targetFile.length())
                } else {
                    src
                }
            }

            database.withTransaction {
                val nbDao = database.notebookDao()
                val srcDao = database.sourceDao()
                val chunkDao = database.chunkAndEmbeddingDao()
                val convDao = database.conversationAndMessageDao()

                collections.forEach { nbDao.upsertCollection(it) }
                notebooks.forEach { nbDao.upsertNotebook(it) }
                restoredSources.forEach { srcDao.upsertSource(it) }
                if (chunks.isNotEmpty()) chunkDao.insertChunks(chunks)
                conversations.forEach { convDao.upsertConversation(it) }
                messages.forEach { convDao.upsertMessage(it) }
                if (citations.isNotEmpty()) convDao.insertCitations(citations)
                artifacts.forEach { convDao.upsertStudioArtifact(it) }
            }

            dbJson.optJSONObject("preferences")?.let { prefsJson ->
                preferencesStore.importNonSecretPreferencesJson(prefsJson)
            }

            RestoreSummary(
                collectionsRestored = collections.size,
                notebooksRestored = notebooks.size,
                sourcesRestored = restoredSources.size,
                chunksRestored = chunks.size,
                conversationsRestored = conversations.size,
                messagesRestored = messages.size,
                artifactsRestored = artifacts.size,
                filesCopied = filesCopied
            )
        } finally {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private fun readZipText(zip: ZipInputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = zip.read(buf)
            if (r <= 0) break
            out.write(buf, 0, r)
        }
        return out.toString("UTF-8")
    }

    private fun collectionToJson(c: CollectionEntity) = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("description", c.description)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
    }

    private fun notebookToJson(n: NotebookEntity) = JSONObject().apply {
        put("id", n.id)
        put("title", n.title)
        put("description", n.description)
        put("iconKey", n.iconKey)
        put("collectionId", n.collectionId ?: JSONObject.NULL)
        put("isPinned", n.isPinned)
        put("createdAt", n.createdAt)
        put("updatedAt", n.updatedAt)
    }

    private fun sourceToJson(s: SourceEntity, archiveRelPath: String) = JSONObject().apply {
        put("id", s.id)
        put("notebookId", s.notebookId)
        put("name", s.name)
        put("mimeType", s.mimeType)
        put("sourceType", s.sourceType)
        put("archiveRelPath", archiveRelPath)
        put("sizeBytes", s.sizeBytes)
        put("fileHashSha256", s.fileHashSha256)
        put("extractedText", s.extractedText)
        put("pageCount", s.pageCount)
        put("metadataJson", s.metadataJson)
        put("processingStatus", s.processingStatus)
        put("indexingProgress", s.indexingProgress.toDouble())
        put("errorMessage", s.errorMessage ?: JSONObject.NULL)
        put("isSelectedForChat", s.isSelectedForChat)
        put("importedAt", s.importedAt)
        put("updatedAt", s.updatedAt)
    }

    private fun chunkToJson(c: DocumentChunkEntity) = JSONObject().apply {
        put("id", c.id)
        put("sourceId", c.sourceId)
        put("notebookId", c.notebookId)
        put("chunkIndex", c.chunkIndex)
        put("sectionTitle", c.sectionTitle)
        put("pageNumber", c.pageNumber)
        put("startOffset", c.startOffset)
        put("endOffset", c.endOffset)
        put("content", c.content)
        put("tokenEstimate", c.tokenEstimate)
        put("createdAt", c.createdAt)
    }

    private fun conversationToJson(c: ConversationEntity) = JSONObject().apply {
        put("id", c.id)
        put("notebookId", c.notebookId)
        put("title", c.title)
        put("selectedModelInternalId", c.selectedModelInternalId ?: JSONObject.NULL)
        put("systemRolePreset", c.systemRolePreset)
        put("customSystemPrompt", c.customSystemPrompt)
        put("responseLength", c.responseLength)
        put("runningSummary", c.runningSummary)
        put("summarizedUpToOrder", c.summarizedUpToOrder)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
    }

    private fun messageToJson(m: MessageEntity) = JSONObject().apply {
        put("id", m.id)
        put("conversationId", m.conversationId)
        put("role", m.role)
        put("content", m.content)
        put("modelDisplayName", m.modelDisplayName ?: JSONObject.NULL)
        put("toolCallsJson", m.toolCallsJson ?: JSONObject.NULL)
        put("toolCallId", m.toolCallId ?: JSONObject.NULL)
        put("tokenCountEstimate", m.tokenCountEstimate)
        put("contextTokensUsed", m.contextTokensUsed)
        put("isPinned", m.isPinned)
        put("isError", m.isError)
        put("orderIndex", m.orderIndex)
        put("createdAt", m.createdAt)
    }

    private fun citationToJson(c: CitationEntity) = JSONObject().apply {
        put("id", c.id)
        put("messageId", c.messageId)
        put("sourceId", c.sourceId)
        put("sourceName", c.sourceName)
        put("chunkId", c.chunkId)
        put("pageNumber", c.pageNumber)
        put("sectionTitle", c.sectionTitle)
        put("startOffset", c.startOffset)
        put("endOffset", c.endOffset)
        put("excerpt", c.excerpt)
        put("relevanceScore", c.relevanceScore.toDouble())
    }

    private fun artifactToJson(a: StudioArtifactEntity) = JSONObject().apply {
        put("id", a.id)
        put("notebookId", a.notebookId)
        put("artifactType", a.artifactType)
        put("title", a.title)
        put("content", a.content)
        put("sourceCount", a.sourceCount)
        put("modelDisplayName", a.modelDisplayName ?: JSONObject.NULL)
        put("createdAt", a.createdAt)
        put("updatedAt", a.updatedAt)
    }

    private fun parseCollections(arr: JSONArray?): List<CollectionEntity> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CollectionEntity(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }

    private fun parseNotebooks(arr: JSONArray?): List<NotebookEntity> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            NotebookEntity(
                id = o.getString("id"),
                title = o.getString("title"),
                description = o.optString("description", ""),
                iconKey = o.optString("iconKey", "book"),
                collectionId = o.optString("collectionId").takeIf { !o.isNull("collectionId") && it.isNotBlank() },
                isPinned = o.optBoolean("isPinned", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }

    private fun parseSourcesWithRelPaths(arr: JSONArray?): List<Pair<SourceEntity, String>> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val entity = SourceEntity(
                id = o.getString("id"),
                notebookId = o.getString("notebookId"),
                name = o.getString("name"),
                mimeType = o.optString("mimeType", "text/plain"),
                sourceType = o.optString("sourceType", "TXT"),
                filePath = "",
                sizeBytes = o.optLong("sizeBytes", 0L),
                fileHashSha256 = o.optString("fileHashSha256", ""),
                extractedText = o.optString("extractedText", ""),
                pageCount = o.optInt("pageCount", 1),
                metadataJson = o.optString("metadataJson", "{}"),
                processingStatus = o.optString("processingStatus", "READY"),
                indexingProgress = o.optDouble("indexingProgress", 1.0).toFloat(),
                errorMessage = o.optString("errorMessage").takeIf { !o.isNull("errorMessage") && it.isNotBlank() },
                isSelectedForChat = o.optBoolean("isSelectedForChat", true),
                importedAt = o.optLong("importedAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
            entity to o.optString("archiveRelPath", "")
        }
    }

    private fun parseChunks(arr: JSONArray?): List<DocumentChunkEntity> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DocumentChunkEntity(
                id = o.getString("id"),
                sourceId = o.getString("sourceId"),
                notebookId = o.getString("notebookId"),
                chunkIndex = o.optInt("chunkIndex", 0),
                sectionTitle = o.optString("sectionTitle", ""),
                pageNumber = o.optInt("pageNumber", 1),
                startOffset = o.optInt("startOffset", 0),
                endOffset = o.optInt("endOffset", 0),
                content = o.getString("content"),
                tokenEstimate = o.optInt("tokenEstimate", 0),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    private fun parseConversations(arr: JSONArray?): List<ConversationEntity> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ConversationEntity(
                id = o.getString("id"),
                notebookId = o.getString("notebookId"),
                title = o.getString("title"),
                selectedModelInternalId = o.optString("selectedModelInternalId").takeIf { !o.isNull("selectedModelInternalId") && it.isNotBlank() },
                systemRolePreset = o.optString("systemRolePreset", "DEFAULT"),
                customSystemPrompt = o.optString("customSystemPrompt", ""),
                responseLength = o.optString("responseLength", "DEFAULT"),
                runningSummary = o.optString("runningSummary", ""),
                summarizedUpToOrder = o.optInt("summarizedUpToOrder", 0),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }

    private fun parseMessages(arr: JSONArray?): List<MessageEntity> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MessageEntity(
                id = o.getString("id"),
                conversationId = o.getString("conversationId"),
                role = o.getString("role"),
                content = o.getString("content"),
                modelDisplayName = o.optString("modelDisplayName").takeIf { !o.isNull("modelDisplayName") && it.isNotBlank() },
                toolCallsJson = o.optString("toolCallsJson").takeIf { !o.isNull("toolCallsJson") && it.isNotBlank() },
                toolCallId = o.optString("toolCallId").takeIf { !o.isNull("toolCallId") && it.isNotBlank() },
                tokenCountEstimate = o.optInt("tokenCountEstimate", 0),
                contextTokensUsed = o.optInt("contextTokensUsed", 0),
                isPinned = o.optBoolean("isPinned", false),
                isError = o.optBoolean("isError", false),
                orderIndex = o.optInt("orderIndex", 0),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    private fun parseCitations(arr: JSONArray?): List<CitationEntity> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CitationEntity(
                id = o.getString("id"),
                messageId = o.getString("messageId"),
                sourceId = o.getString("sourceId"),
                sourceName = o.getString("sourceName"),
                chunkId = o.getString("chunkId"),
                pageNumber = o.optInt("pageNumber", 1),
                sectionTitle = o.optString("sectionTitle", ""),
                startOffset = o.optInt("startOffset", 0),
                endOffset = o.optInt("endOffset", 0),
                excerpt = o.getString("excerpt"),
                relevanceScore = o.optDouble("relevanceScore", 0.0).toFloat()
            )
        }
    }

    private fun parseArtifacts(arr: JSONArray?): List<StudioArtifactEntity> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            StudioArtifactEntity(
                id = o.getString("id"),
                notebookId = o.getString("notebookId"),
                artifactType = o.getString("artifactType"),
                title = o.getString("title"),
                content = o.getString("content"),
                sourceCount = o.optInt("sourceCount", 0),
                modelDisplayName = o.optString("modelDisplayName").takeIf { !o.isNull("modelDisplayName") && it.isNotBlank() },
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
