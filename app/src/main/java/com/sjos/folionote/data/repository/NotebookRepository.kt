package com.sjos.folionote.data.repository

import android.net.Uri
import com.sjos.folionote.data.local.CollectionEntity
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.SourceStatus
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.data.local.StudioArtifactEntity
import com.sjos.folionote.data.local.SyncTombstoneEntity
import com.sjos.folionote.engine.document.FileStorageManager
import com.sjos.folionote.engine.document.StorageStats
import com.sjos.folionote.engine.drive.DriveFileItem
import com.sjos.folionote.engine.drive.DriveSyncEngine
import com.sjos.folionote.engine.drive.GoogleDriveApiClient
import com.sjos.folionote.engine.retrieval.HybridRetriever
import com.sjos.folionote.engine.retrieval.RetrievedPassage
import com.sjos.folionote.engine.web.WebClipperEngine
import com.sjos.folionote.engine.worker.SourcePipelineManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GlobalSearchResults(
    val notebooks: List<NotebookEntity> = emptyList(),
    val sources: List<SourceEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val notebookIdByConversationId: Map<String, String> = emptyMap()
)

class NotebookRepository(
    private val db: FolioNoteDatabase,
    private val fileStorageManager: FileStorageManager,
    private val pipelineManager: SourcePipelineManager,
    private val retriever: HybridRetriever,
    private val webClipperEngine: WebClipperEngine = WebClipperEngine()
) {
    private val notebookDao = db.notebookDao()
    private val sourceDao = db.sourceDao()
    private val chunkDao = db.chunkAndEmbeddingDao()
    private val conversationDao = db.conversationAndMessageDao()

    val allNotebooks: Flow<List<NotebookEntity>> = notebookDao.observeAllNotebooks()
    val allCollections: Flow<List<CollectionEntity>> = notebookDao.observeAllCollections()
    val allSources: Flow<List<SourceEntity>> = sourceDao.observeAllSources()
    val totalStorageBytes: Flow<Long> = sourceDao.observeTotalStorageBytes()
    val recentConversations = conversationDao.observeRecentConversations()

    fun observeNotebook(notebookId: String): Flow<NotebookEntity?> = notebookDao.observeNotebook(notebookId)
    fun observeSources(notebookId: String): Flow<List<SourceEntity>> = sourceDao.observeSourcesForNotebook(notebookId)
    fun observeSource(sourceId: String): Flow<SourceEntity?> = sourceDao.observeSource(sourceId)
    fun observeChunksForSource(sourceId: String): Flow<List<DocumentChunkEntity>> = chunkDao.observeChunksForSource(sourceId)
    fun observeChunksForNotebook(notebookId: String): Flow<List<DocumentChunkEntity>> = chunkDao.observeChunksForNotebook(notebookId)
    fun observeEmbeddingsForNotebook(notebookId: String) = chunkDao.observeEmbeddingsForNotebook(notebookId)
    fun observeChunkStats(notebookId: String) = chunkDao.observeChunkStats(notebookId)
    fun observeChunkCountsBySource(notebookId: String) = chunkDao.observeChunkCountsBySource(notebookId)
    fun observeEmbeddingGroups(notebookId: String) = chunkDao.observeEmbeddingGroups(notebookId)
    fun observeProcessingJobs(notebookId: String) = db.toolAndJobDao().observeJobsForNotebook(notebookId)
    fun observeStudioArtifacts(notebookId: String): Flow<List<StudioArtifactEntity>> =
        conversationDao.observeStudioArtifacts(notebookId)

    suspend fun createNotebook(
        title: String,
        description: String = "",
        iconKey: String = "book",
        collectionId: String? = null
    ): NotebookEntity {
        val now = System.currentTimeMillis()
        val entity = NotebookEntity(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "Notebook sem título" },
            description = description.trim(),
            iconKey = iconKey,
            collectionId = collectionId,
            createdAt = now,
            updatedAt = now
        )
        notebookDao.upsertNotebook(entity)
        return entity
    }

    suspend fun updateNotebook(notebook: NotebookEntity) {
        notebookDao.upsertNotebook(notebook.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun togglePin(notebookId: String, currentPinned: Boolean) {
        notebookDao.setPinned(notebookId, !currentPinned)
    }

    suspend fun assignToCollection(notebookId: String, collectionId: String?) {
        notebookDao.setCollection(notebookId, collectionId)
    }

    suspend fun deleteNotebook(notebookId: String) {
        val now = System.currentTimeMillis()
        sourceDao.getSourcesForNotebookOnce(notebookId).forEach { src ->
            pipelineManager.cancelProcessing(src.id)
            recordTombstone(DriveSyncEngine.ENTITY_SOURCE, src.id, now)
        }
        recordTombstone(DriveSyncEngine.ENTITY_NOTEBOOK, notebookId, now)
        fileStorageManager.deleteNotebookDirectory(notebookId)
        notebookDao.deleteNotebookById(notebookId)
    }

    suspend fun createCollection(name: String, description: String = ""): CollectionEntity {
        val now = System.currentTimeMillis()
        val entity = CollectionEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Nova coleção" },
            description = description.trim(),
            createdAt = now,
            updatedAt = now
        )
        notebookDao.upsertCollection(entity)
        return entity
    }

    suspend fun deleteCollection(collectionId: String) {
        recordTombstone(DriveSyncEngine.ENTITY_COLLECTION, collectionId, System.currentTimeMillis())
        notebookDao.deleteCollectionById(collectionId)
    }

    suspend fun importUriSource(
        notebookId: String,
        uri: Uri,
        isFromGoogleDrive: Boolean = false
    ): SourceEntity = withContext(Dispatchers.IO) {
        val stored = fileStorageManager.importFromUri(notebookId, uri)
        val fromDrive = isFromGoogleDrive || fileStorageManager.isGoogleDriveUri(uri)
        val metaJson = if (fromDrive) {
            JSONObject().apply {
                put("origin", "google_drive")
                put("drive_uri", uri.toString())
            }.toString()
        } else {
            "{}"
        }
        val source = SourceEntity(
            id = stored.sourceId,
            notebookId = notebookId,
            name = stored.displayName,
            mimeType = stored.mimeType,
            sourceType = stored.sourceType.name,
            filePath = stored.file.absolutePath,
            sizeBytes = stored.sizeBytes,
            fileHashSha256 = stored.sha256,
            metadataJson = metaJson,
            processingStatus = SourceStatus.PENDING.name,
            indexingProgress = 0.05f
        )
        sourceDao.upsertSource(source)
        pipelineManager.scheduleProcessing(notebookId, source.id, "IMPORT")
        source
    }

    suspend fun importDriveFile(
        notebookId: String,
        accessToken: String,
        item: DriveFileItem,
        apiClient: GoogleDriveApiClient,
        isCancelled: () -> Boolean = { false }
    ): Result<SourceEntity> = withContext(Dispatchers.IO) {
        val tempDir = File(
            System.getProperty("java.io.tmpdir") ?: "/tmp",
            "folionote_drive_${UUID.randomUUID()}"
        ).apply { mkdirs() }

        try {
            runCatching {
                if (isCancelled()) throw CancellationException("Importação cancelada pelo usuário.")
                val downloaded = apiClient.downloadOrExportDriveFile(
                    accessToken = accessToken,
                    item = item,
                    destDir = tempDir,
                    isCancelled = isCancelled
                ).getOrThrow()

                if (isCancelled()) throw CancellationException("Importação cancelada pelo usuário.")
                val stored = fileStorageManager.importFromLocalFile(
                    notebookId = notebookId,
                    sourceFile = downloaded.file,
                    displayName = downloaded.resolvedName,
                    mimeType = downloaded.resolvedMimeType
                )

                val meta = JSONObject().apply {
                    put("origin", "google_drive")
                    put("drive_file_id", item.id)
                    put("drive_mime_type", item.mimeType)
                    if (item.modifiedTime.isNotBlank()) put("drive_modified_time", item.modifiedTime)
                    if (!item.webViewLink.isNullOrBlank()) put("drive_web_view_link", item.webViewLink)
                }.toString()

                val source = SourceEntity(
                    id = stored.sourceId,
                    notebookId = notebookId,
                    name = stored.displayName,
                    mimeType = stored.mimeType,
                    sourceType = stored.sourceType.name,
                    filePath = stored.file.absolutePath,
                    sizeBytes = stored.sizeBytes,
                    fileHashSha256 = stored.sha256,
                    metadataJson = meta,
                    processingStatus = SourceStatus.PENDING.name,
                    indexingProgress = 0.05f
                )
                sourceDao.upsertSource(source)
                pipelineManager.scheduleProcessing(notebookId, source.id, "IMPORT")
                source
            }
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    suspend fun createNoteSource(notebookId: String, title: String, content: String): SourceEntity = withContext(Dispatchers.IO) {
        val stored = fileStorageManager.createTextNoteFile(notebookId, title, content)
        val source = SourceEntity(
            id = stored.sourceId,
            notebookId = notebookId,
            name = stored.displayName,
            mimeType = stored.mimeType,
            sourceType = SourceType.NOTE.name,
            filePath = stored.file.absolutePath,
            sizeBytes = stored.sizeBytes,
            fileHashSha256 = stored.sha256,
            extractedText = content,
            processingStatus = SourceStatus.PENDING.name,
            indexingProgress = 0.05f
        )
        sourceDao.upsertSource(source)
        pipelineManager.scheduleProcessing(notebookId, source.id, "IMPORT")
        source
    }

    suspend fun importWebUrl(notebookId: String, rawUrl: String): Result<SourceEntity> = withContext(Dispatchers.IO) {
        val clippedRes = webClipperEngine.clipUrl(rawUrl)
        clippedRes.map { clipped ->
            val stored = fileStorageManager.createWebClipFile(
                notebookId = notebookId,
                title = clipped.title,
                markdownContent = clipped.markdownContent
            )
            val meta = JSONObject().apply {
                put("format", "web_page")
                put("source_url", clipped.requestedUrl)
                put("canonical_url", clipped.canonicalUrl)
                put("final_url", clipped.finalUrl)
                put("domain", clipped.domain)
                put("captured_at", clipped.capturedAtMillis.toString())
                if (!clipped.author.isNullOrBlank()) put("author", clipped.author)
            }.toString()

            val source = SourceEntity(
                id = stored.sourceId,
                notebookId = notebookId,
                name = clipped.title,
                mimeType = "text/markdown",
                sourceType = SourceType.WEB_PAGE.name,
                filePath = stored.file.absolutePath,
                sizeBytes = stored.sizeBytes,
                fileHashSha256 = stored.sha256,
                extractedText = clipped.markdownContent,
                metadataJson = meta,
                processingStatus = SourceStatus.PENDING.name,
                indexingProgress = 0.05f
            )
            sourceDao.upsertSource(source)
            pipelineManager.scheduleProcessing(notebookId, source.id, "IMPORT")
            source
        }
    }

    suspend fun reprocessSource(source: SourceEntity) {
        pipelineManager.scheduleProcessing(source.notebookId, source.id, "REPROCESS")
    }

    suspend fun reindexNotebook(notebookId: String) {
        val sources = sourceDao.getSourcesForNotebookOnce(notebookId)
        for (src in sources) {
            pipelineManager.scheduleProcessing(notebookId, src.id, "REINDEX")
        }
    }

    suspend fun clearNotebookEmbeddings(notebookId: String) {
        chunkDao.deleteEmbeddingsForNotebook(notebookId)
    }

    suspend fun setSourceSelected(sourceId: String, selected: Boolean) {
        sourceDao.setSourceSelected(sourceId, selected)
    }

    suspend fun setAllSourcesSelected(notebookId: String, selected: Boolean) {
        sourceDao.setAllSourcesSelected(notebookId, selected)
    }

    suspend fun setSourcesSelected(sourceIds: List<String>, selected: Boolean) {
        sourceDao.setSourcesSelected(sourceIds, selected)
    }

    suspend fun deleteSource(source: SourceEntity) {
        pipelineManager.cancelProcessing(source.id)
        recordTombstone(DriveSyncEngine.ENTITY_SOURCE, source.id, System.currentTimeMillis())
        fileStorageManager.deleteSourceFile(source.filePath)
        sourceDao.deleteSourceById(source.id)
    }

    suspend fun saveStudioArtifact(
        notebookId: String,
        artifactType: String,
        title: String,
        content: String,
        sourceCount: Int,
        modelDisplayName: String? = null
    ) {
        conversationDao.upsertStudioArtifact(
            StudioArtifactEntity(
                id = UUID.randomUUID().toString(),
                notebookId = notebookId,
                artifactType = artifactType,
                title = title,
                content = content,
                sourceCount = sourceCount,
                modelDisplayName = modelDisplayName
            )
        )
    }

    suspend fun deleteStudioArtifact(artifactId: String) {
        recordTombstone(DriveSyncEngine.ENTITY_ARTIFACT, artifactId, System.currentTimeMillis())
        conversationDao.deleteStudioArtifactById(artifactId)
    }

    private suspend fun recordTombstone(entityType: String, entityId: String, now: Long) {
        db.syncDao().upsertTombstone(
            SyncTombstoneEntity(
                id = "$entityType:$entityId",
                entityType = entityType,
                entityId = entityId,
                deletedAt = now,
                revision = now,
                syncedToRemote = false
            )
        )
    }

    suspend fun searchGlobal(query: String): GlobalSearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext GlobalSearchResults()
        val messages = conversationDao.searchMessagesGlobal(trimmed)
        val convIds = messages.map { it.conversationId }.distinct()
        val convToNotebook = mutableMapOf<String, String>()
        for (convId in convIds) {
            conversationDao.getConversationById(convId)?.let { conv ->
                convToNotebook[convId] = conv.notebookId
            }
        }
        GlobalSearchResults(
            notebooks = notebookDao.searchNotebooks(trimmed),
            sources = sourceDao.searchSourcesGlobal(trimmed),
            messages = messages,
            notebookIdByConversationId = convToNotebook
        )
    }

    suspend fun searchLexicalInNotebook(notebookId: String, query: String): List<RetrievedPassage> =
        withContext(Dispatchers.IO) {
            val sources = sourceDao.getSourcesForNotebookOnce(notebookId)
            retriever.searchLexicalOnDemand(
                query = query,
                sources = sources,
                limit = 30,
                pageSize = 64,
                loadChunkPage = { limit, offset ->
                    chunkDao.getChunksPageForNotebook(notebookId, limit, offset)
                },
                loadChunksByIds = { ids -> chunkDao.getChunksByIds(ids) }
            )
        }

    fun getStorageStats(usedBytes: Long): StorageStats {
        return fileStorageManager.getStorageStats(usedBytes)
    }
}
