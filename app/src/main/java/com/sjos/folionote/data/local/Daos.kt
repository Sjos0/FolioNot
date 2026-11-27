package com.sjos.folionote.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAllNotebooks(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    fun observeNotebook(id: String): Flow<NotebookEntity?>

    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun getNotebookById(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchNotebooks(query: String): List<NotebookEntity>

    @Upsert
    suspend fun upsertNotebook(notebook: NotebookEntity)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun deleteNotebookById(id: String)

    @Query("UPDATE notebooks SET isPinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE notebooks SET collectionId = :collectionId, updatedAt = :now WHERE id = :notebookId")
    suspend fun setCollection(notebookId: String, collectionId: String?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE ASC")
    fun observeAllCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections")
    suspend fun getAllCollectionsOnce(): List<CollectionEntity>

    @Query("SELECT * FROM notebooks")
    suspend fun getAllNotebooksOnce(): List<NotebookEntity>

    @Upsert
    suspend fun upsertCollection(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollectionById(id: String)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources WHERE notebookId = :notebookId ORDER BY importedAt DESC")
    fun observeSourcesForNotebook(notebookId: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY importedAt DESC")
    fun observeAllSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :sourceId LIMIT 1")
    fun observeSource(sourceId: String): Flow<SourceEntity?>

    @Query("SELECT * FROM sources WHERE id = :sourceId LIMIT 1")
    suspend fun getSourceById(sourceId: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE notebookId = :notebookId AND isSelectedForChat = 1 AND processingStatus IN ('READY', 'PARTIAL')")
    suspend fun getSelectedReadySources(notebookId: String): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE notebookId = :notebookId")
    suspend fun getSourcesForNotebookOnce(notebookId: String): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE name LIKE '%' || :query || '%' OR extractedText LIKE '%' || :query || '%'")
    suspend fun searchSourcesGlobal(query: String): List<SourceEntity>

    @Upsert
    suspend fun upsertSource(source: SourceEntity)

    @Query("UPDATE sources SET isSelectedForChat = :selected, updatedAt = :now WHERE id = :sourceId")
    suspend fun setSourceSelected(sourceId: String, selected: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE sources SET isSelectedForChat = :selected, updatedAt = :now WHERE notebookId = :notebookId")
    suspend fun setAllSourcesSelected(notebookId: String, selected: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE sources SET isSelectedForChat = :selected, updatedAt = :now WHERE id IN (:sourceIds)")
    suspend fun setSourcesSelected(sourceIds: List<String>, selected: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE sources SET processingStatus = :status, indexingProgress = :progress, errorMessage = :errorMessage, updatedAt = :now WHERE id = :sourceId")
    suspend fun updateProcessingState(
        sourceId: String,
        status: String,
        progress: Float,
        errorMessage: String? = null,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM sources WHERE id = :sourceId")
    suspend fun deleteSourceById(sourceId: String)

    @Query("SELECT COUNT(*) FROM sources WHERE notebookId = :notebookId")
    fun observeSourceCount(notebookId: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM sources")
    fun observeTotalStorageBytes(): Flow<Long>

    @Query("SELECT * FROM sources")
    suspend fun getAllSourcesOnce(): List<SourceEntity>
}

@Dao
interface ChunkAndEmbeddingDao {
    @Query("SELECT * FROM document_chunks WHERE sourceId = :sourceId ORDER BY chunkIndex ASC")
    fun observeChunksForSource(sourceId: String): Flow<List<DocumentChunkEntity>>

    @Query("SELECT * FROM document_chunks WHERE notebookId = :notebookId ORDER BY sourceId, chunkIndex ASC")
    fun observeChunksForNotebook(notebookId: String): Flow<List<DocumentChunkEntity>>

    @Query("SELECT * FROM document_chunks WHERE sourceId IN (:sourceIds)")
    suspend fun getChunksForSources(sourceIds: List<String>): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE sourceId IN (:sourceIds) ORDER BY sourceId ASC, chunkIndex ASC LIMIT :limit OFFSET :offset")
    suspend fun getChunksPageForSources(sourceIds: List<String>, limit: Int, offset: Int): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE notebookId = :notebookId")
    suspend fun getChunksForNotebookOnce(notebookId: String): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE notebookId = :notebookId ORDER BY sourceId ASC, chunkIndex ASC LIMIT :limit OFFSET :offset")
    suspend fun getChunksPageForNotebook(notebookId: String, limit: Int, offset: Int): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE sourceId = :sourceId AND pageNumber = :pageNumber ORDER BY chunkIndex ASC")
    suspend fun getChunksForSourcePage(sourceId: String, pageNumber: Int): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE id IN (:chunkIds)")
    suspend fun getChunksByIds(chunkIds: List<String>): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE id = :chunkId LIMIT 1")
    suspend fun getChunkById(chunkId: String): DocumentChunkEntity?

    @Upsert
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Query("DELETE FROM document_chunks WHERE sourceId = :sourceId")
    suspend fun deleteChunksForSource(sourceId: String)

    @Query("SELECT * FROM embeddings WHERE sourceId IN (:sourceIds) AND embeddingModelId = :embeddingModelId AND dimensions = :dimensions")
    suspend fun getEmbeddingsForSources(
        sourceIds: List<String>,
        embeddingModelId: String,
        dimensions: Int
    ): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE sourceId IN (:sourceIds) AND embeddingModelId = :embeddingModelId AND dimensions = :dimensions ORDER BY chunkId ASC LIMIT :limit OFFSET :offset")
    suspend fun getEmbeddingsPageForSources(
        sourceIds: List<String>,
        embeddingModelId: String,
        dimensions: Int,
        limit: Int,
        offset: Int
    ): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE notebookId = :notebookId")
    fun observeEmbeddingsForNotebook(notebookId: String): Flow<List<EmbeddingEntity>>

    @Query("SELECT COUNT(*) AS chunkCount, COALESCE(SUM(tokenEstimate), 0) AS totalTokens FROM document_chunks WHERE notebookId = :notebookId")
    fun observeChunkStats(notebookId: String): Flow<NotebookChunkStats>

    @Query("SELECT sourceId AS sourceId, COUNT(*) AS chunkCount FROM document_chunks WHERE notebookId = :notebookId GROUP BY sourceId")
    fun observeChunkCountsBySource(notebookId: String): Flow<List<SourceChunkCount>>

    @Query("SELECT embeddingModelId AS embeddingModelId, dimensions AS dimensions, COUNT(*) AS vectorCount FROM embeddings WHERE notebookId = :notebookId GROUP BY embeddingModelId, dimensions")
    fun observeEmbeddingGroups(notebookId: String): Flow<List<EmbeddingGroup>>

    @Upsert
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE sourceId = :sourceId")
    suspend fun deleteEmbeddingsForSource(sourceId: String)

    @Query("DELETE FROM embeddings WHERE notebookId = :notebookId")
    suspend fun deleteEmbeddingsForNotebook(notebookId: String)

    @Query("DELETE FROM embeddings WHERE notebookId = :notebookId AND (embeddingModelId != :embeddingModelId OR dimensions != :dimensions)")
    suspend fun deleteIncompatibleEmbeddingsForNotebook(
        notebookId: String,
        embeddingModelId: String,
        dimensions: Int
    )

    @Query("SELECT * FROM document_chunks")
    suspend fun getAllChunksOnce(): List<DocumentChunkEntity>

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddingsOnce(): List<EmbeddingEntity>
}

@Dao
interface ProviderAndModelDao {
    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    fun observeAllProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id LIMIT 1")
    suspend fun getProviderById(id: String): ProviderEntity?

    @Upsert
    suspend fun upsertProvider(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteProviderById(id: String)

    @Query("UPDATE providers SET encryptedApiKey = '', updatedAt = :now WHERE id = :id")
    suspend fun clearProviderApiKey(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE providers SET encryptedApiKey = '', updatedAt = :now")
    suspend fun clearAllProviderApiKeys(now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM models ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAllModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE enabled = 1 AND isEmbeddingModel = 0 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeEnabledChatModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE enabled = 1 AND isEmbeddingModel = 1 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeEnabledEmbeddingModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY displayName COLLATE NOCASE ASC")
    fun observeModelsForProvider(providerId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId")
    suspend fun getModelsForProviderOnce(providerId: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :internalId LIMIT 1")
    suspend fun getModelByInternalId(internalId: String): ModelEntity?

    @Upsert
    suspend fun upsertModel(model: ModelEntity)

    @Upsert
    suspend fun upsertModels(models: List<ModelEntity>)

    @Query("DELETE FROM models WHERE id = :internalId")
    suspend fun deleteModelByInternalId(internalId: String)
}

@Dao
interface ConversationAndMessageDao {
    @Query("SELECT * FROM conversations WHERE notebookId = :notebookId ORDER BY updatedAt DESC")
    fun observeConversationsForNotebook(notebookId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 20")
    fun observeRecentConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY orderIndex ASC, createdAt ASC")
    fun observeMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun getMessagesForConversationOnce(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY orderIndex DESC, createdAt DESC LIMIT :limit")
    suspend fun getRecentMessagesForConversationDesc(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'USER' ORDER BY orderIndex ASC, createdAt ASC LIMIT 1")
    suspend fun getFirstUserMessageOnce(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND orderIndex > :afterOrder AND orderIndex < :beforeOrder AND role IN ('USER', 'ASSISTANT') AND isError = 0 ORDER BY orderIndex ASC, createdAt ASC LIMIT :limit")
    suspend fun getUnsummarizedMessagesInRangeOnce(
        conversationId: String,
        afterOrder: Int,
        beforeOrder: Int,
        limit: Int
    ): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND orderIndex > :afterOrder AND role IN ('USER', 'ASSISTANT') AND isError = 0")
    suspend fun countConversationalMessagesAfter(conversationId: String, afterOrder: Int): Int

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT 50")
    suspend fun searchMessagesGlobal(query: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(orderIndex), 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMaxOrderIndex(conversationId: String): Int

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("SELECT * FROM citations WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    fun observeCitationsForConversation(conversationId: String): Flow<List<CitationEntity>>

    @Upsert
    suspend fun insertCitations(citations: List<CitationEntity>)

    @Query("SELECT * FROM studio_artifacts WHERE notebookId = :notebookId ORDER BY updatedAt DESC")
    fun observeStudioArtifacts(notebookId: String): Flow<List<StudioArtifactEntity>>

    @Query("SELECT * FROM studio_artifacts")
    suspend fun getAllStudioArtifactsOnce(): List<StudioArtifactEntity>

    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationsOnce(): List<ConversationEntity>

    @Query("SELECT * FROM messages")
    suspend fun getAllMessagesOnce(): List<MessageEntity>

    @Query("SELECT * FROM citations")
    suspend fun getAllCitationsOnce(): List<CitationEntity>

    @Upsert
    suspend fun upsertStudioArtifact(artifact: StudioArtifactEntity)

    @Query("DELETE FROM studio_artifacts WHERE id = :id")
    suspend fun deleteStudioArtifactById(id: String)
}

@Dao
interface ToolAndJobDao {
    @Query("SELECT * FROM tool_definitions ORDER BY name ASC")
    fun observeAllTools(): Flow<List<ToolDefinitionEntity>>

    @Query("SELECT * FROM tool_definitions WHERE enabled = 1")
    suspend fun getEnabledToolsOnce(): List<ToolDefinitionEntity>

    @Upsert
    suspend fun upsertTools(tools: List<ToolDefinitionEntity>)

    @Query("UPDATE tool_definitions SET enabled = :enabled WHERE id = :id")
    suspend fun setToolEnabled(id: String, enabled: Boolean)

    @Query("SELECT * FROM processing_jobs WHERE notebookId = :notebookId ORDER BY createdAt DESC")
    fun observeJobsForNotebook(notebookId: String): Flow<List<ProcessingJobEntity>>

    @Query("SELECT * FROM processing_jobs WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun getActiveOrInterruptedJobs(): List<ProcessingJobEntity>

    @Upsert
    suspend fun upsertJob(job: ProcessingJobEntity)

    @Query("UPDATE processing_jobs SET status = :status, progress = :progress, statusMessage = :message, updatedAt = :now WHERE id = :jobId")
    suspend fun updateJobProgress(
        jobId: String,
        status: String,
        progress: Float,
        message: String,
        now: Long = System.currentTimeMillis()
    )
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_records")
    suspend fun getAllSyncRecordsOnce(): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE recordKey = :recordKey LIMIT 1")
    suspend fun getSyncRecord(recordKey: String): SyncRecordEntity?

    @Upsert
    suspend fun upsertSyncRecord(record: SyncRecordEntity)

    @Upsert
    suspend fun upsertSyncRecords(records: List<SyncRecordEntity>)

    @Query("SELECT * FROM sync_tombstones")
    suspend fun getAllTombstonesOnce(): List<SyncTombstoneEntity>

    @Query("SELECT * FROM sync_tombstones WHERE syncedToRemote = 0")
    suspend fun getPendingTombstonesOnce(): List<SyncTombstoneEntity>

    @Upsert
    suspend fun upsertTombstone(tombstone: SyncTombstoneEntity)

    @Query("DELETE FROM sync_tombstones WHERE id = :id")
    suspend fun deleteTombstoneById(id: String)

    @Query("SELECT * FROM sync_conflicts WHERE resolutionStatus = 'PENDING' ORDER BY detectedAt DESC")
    fun observePendingConflicts(): Flow<List<SyncConflictEntity>>

    @Query("SELECT * FROM sync_conflicts WHERE resolutionStatus = 'PENDING' ORDER BY detectedAt DESC")
    suspend fun getPendingConflictsOnce(): List<SyncConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE id = :id LIMIT 1")
    suspend fun getConflictById(id: String): SyncConflictEntity?

    @Upsert
    suspend fun upsertConflict(conflict: SyncConflictEntity)

    @Query("UPDATE sync_conflicts SET resolutionStatus = :status WHERE id = :id")
    suspend fun updateConflictStatus(id: String, status: String)

    @Query(
        """
        SELECT (
            EXISTS(SELECT 1 FROM sync_tombstones WHERE syncedToRemote = 0) OR
            EXISTS(SELECT 1 FROM sync_records WHERE isDirty = 1) OR
            EXISTS(SELECT 1 FROM collections c LEFT JOIN sync_records r ON r.recordKey = 'COLLECTION:' || c.id WHERE r.recordKey IS NULL OR c.updatedAt > r.localUpdatedAt) OR
            EXISTS(SELECT 1 FROM notebooks n LEFT JOIN sync_records r ON r.recordKey = 'NOTEBOOK:' || n.id WHERE r.recordKey IS NULL OR n.updatedAt > r.localUpdatedAt) OR
            EXISTS(SELECT 1 FROM sources s LEFT JOIN sync_records r ON r.recordKey = 'SOURCE:' || s.id WHERE r.recordKey IS NULL OR s.updatedAt > r.localUpdatedAt) OR
            EXISTS(SELECT 1 FROM conversations c LEFT JOIN sync_records r ON r.recordKey = 'CONVERSATION:' || c.id WHERE r.recordKey IS NULL OR c.updatedAt > r.localUpdatedAt) OR
            EXISTS(SELECT 1 FROM messages m LEFT JOIN sync_records r ON r.recordKey = 'MESSAGE:' || m.id WHERE r.recordKey IS NULL OR m.createdAt > r.localUpdatedAt) OR
            EXISTS(SELECT 1 FROM studio_artifacts a LEFT JOIN sync_records r ON r.recordKey = 'ARTIFACT:' || a.id WHERE r.recordKey IS NULL OR a.updatedAt > r.localUpdatedAt)
        )
        """
    )
    suspend fun hasPendingLocalChanges(): Boolean
}

// Agregados leves usados pela UI do workspace (evitam carregar chunks/vetores inteiros na RAM).
data class NotebookChunkStats(val chunkCount: Int, val totalTokens: Long)
data class SourceChunkCount(val sourceId: String, val chunkCount: Int)
data class EmbeddingGroup(val embeddingModelId: String, val dimensions: Int, val vectorCount: Int)
