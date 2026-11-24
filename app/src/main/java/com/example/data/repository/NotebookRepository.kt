package com.example.data.repository

import android.net.Uri
import com.example.data.local.CollectionEntity
import com.example.data.local.DocumentChunkEntity
import com.example.data.local.FolioNoteDatabase
import com.example.data.local.MessageEntity
import com.example.data.local.NotebookEntity
import com.example.data.local.SourceEntity
import com.example.data.local.SourceStatus
import com.example.data.local.SourceType
import com.example.data.local.StudioArtifactEntity
import com.example.engine.document.FileStorageManager
import com.example.engine.document.StorageStats
import com.example.engine.retrieval.HybridRetriever
import com.example.engine.retrieval.RetrievedPassage
import com.example.engine.worker.SourcePipelineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

data class GlobalSearchResults(
    val notebooks: List<NotebookEntity> = emptyList(),
    val sources: List<SourceEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList()
)

class NotebookRepository(
    private val db: FolioNoteDatabase,
    private val fileStorageManager: FileStorageManager,
    private val pipelineManager: SourcePipelineManager,
    private val retriever: HybridRetriever
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
        sourceDao.getSourcesForNotebookOnce(notebookId).forEach { src ->
            pipelineManager.cancelProcessing(src.id)
        }
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
        notebookDao.deleteCollectionById(collectionId)
    }

    suspend fun importUriSource(notebookId: String, uri: Uri): SourceEntity = withContext(Dispatchers.IO) {
        val stored = fileStorageManager.importFromUri(notebookId, uri)
        val source = SourceEntity(
            id = stored.sourceId,
            notebookId = notebookId,
            name = stored.displayName,
            mimeType = stored.mimeType,
            sourceType = stored.sourceType.name,
            filePath = stored.file.absolutePath,
            sizeBytes = stored.sizeBytes,
            fileHashSha256 = stored.sha256,
            processingStatus = SourceStatus.PENDING.name,
            indexingProgress = 0.05f
        )
        sourceDao.upsertSource(source)
        pipelineManager.scheduleProcessing(notebookId, source.id, "IMPORT")
        source
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

    suspend fun reprocessSource(source: SourceEntity) {
        pipelineManager.scheduleProcessing(source.notebookId, source.id, "REPROCESS")
    }

    suspend fun reindexNotebook(notebookId: String) {
        val sources = sourceDao.getSourcesForNotebookOnce(notebookId)
        for (src in sources) {
            pipelineManager.scheduleProcessing(notebookId, src.id, "REINDEX")
        }
    }

    suspend fun setSourceSelected(sourceId: String, selected: Boolean) {
        sourceDao.setSourceSelected(sourceId, selected)
    }

    suspend fun setAllSourcesSelected(notebookId: String, selected: Boolean) {
        sourceDao.setAllSourcesSelected(notebookId, selected)
    }

    suspend fun deleteSource(source: SourceEntity) {
        pipelineManager.cancelProcessing(source.id)
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
        conversationDao.deleteStudioArtifactById(artifactId)
    }

    suspend fun searchGlobal(query: String): GlobalSearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext GlobalSearchResults()
        GlobalSearchResults(
            notebooks = notebookDao.searchNotebooks(trimmed),
            sources = sourceDao.searchSourcesGlobal(trimmed),
            messages = conversationDao.searchMessagesGlobal(trimmed)
        )
    }

    suspend fun searchLexicalInNotebook(notebookId: String, query: String): List<RetrievedPassage> =
        withContext(Dispatchers.IO) {
            val sources = sourceDao.getSourcesForNotebookOnce(notebookId)
            val chunks = chunkDao.getChunksForNotebookOnce(notebookId)
            retriever.searchLexicalOffline(query, sources, chunks, limit = 30)
        }

    fun getStorageStats(usedBytes: Long): StorageStats {
        return fileStorageManager.getStorageStats(usedBytes)
    }
}
