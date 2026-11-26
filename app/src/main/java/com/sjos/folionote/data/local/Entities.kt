package com.sjos.folionote.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notebooks",
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("collectionId"), Index("updatedAt")]
)
data class NotebookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val iconKey: String = "book",
    val collectionId: String? = null,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class SourceType {
    PDF, TXT, MARKDOWN, DOCX, NOTE, PPTX, EPUB, IMAGE, AUDIO, VIDEO, WEB_PAGE
}

enum class SourceStatus {
    PENDING, EXTRACTING, CHUNKING, EMBEDDING, READY, PARTIAL, FAILED, REQUIRES_OCR, REQUIRES_TRANSCRIPTION
}

@Entity(
    tableName = "sources",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId"), Index("fileHashSha256")]
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val name: String,
    val mimeType: String,
    val sourceType: String,
    val filePath: String,
    val sizeBytes: Long,
    val fileHashSha256: String,
    val extractedText: String = "",
    val pageCount: Int = 1,
    val metadataJson: String = "{}",
    val processingStatus: String = SourceStatus.PENDING.name,
    val indexingProgress: Float = 0f,
    val errorMessage: String? = null,
    val isSelectedForChat: Boolean = true,
    val importedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sourceId"), Index("notebookId")]
)
data class DocumentChunkEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val notebookId: String,
    val chunkIndex: Int,
    val sectionTitle: String = "",
    val pageNumber: Int = 1,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val content: String,
    val tokenEstimate: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = DocumentChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chunkId"), Index("notebookId"), Index("sourceId"), Index("embeddingModelId")]
)
data class EmbeddingEntity(
    @PrimaryKey val id: String,
    val chunkId: String,
    val notebookId: String,
    val sourceId: String,
    val embeddingProviderId: String,
    val embeddingModelId: String,
    val dimensions: Int,
    val vectorBlob: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id &&
            chunkId == other.chunkId &&
            embeddingModelId == other.embeddingModelId &&
            dimensions == other.dimensions &&
            vectorBlob.contentEquals(other.vectorBlob)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + chunkId.hashCode()
        result = 31 * result + embeddingModelId.hashCode()
        result = 31 * result + dimensions
        result = 31 * result + vectorBlob.contentHashCode()
        return result
    }
}

enum class ProviderType {
    OPENAI_COMPATIBLE,
    GEMINI_OPENAI_COMPAT,
    GEMINI_NATIVE_EMBEDDING,
    ANTHROPIC_COMPAT,
    OLLAMA_LOCAL,
    CUSTOM
}

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val chatPath: String = "/chat/completions",
    val modelsPath: String = "/models",
    val embeddingsPath: String = "/embeddings",
    val authHeaderName: String = "Authorization",
    val authPrefix: String = "Bearer",
    val encryptedApiKey: String = "",
    val extraHeadersJson: String = "{}",
    val defaultModelId: String? = null,
    val timeoutSeconds: Int = 60,
    val supportsStreamingDefault: Boolean = true,
    val extraParamsJson: String = "{}",
    val providerType: String = ProviderType.OPENAI_COMPATIBLE.name,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("providerId"), Index(value = ["providerId", "modelId"], unique = true)]
)
data class ModelEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int = 8192,
    val maxOutputTokens: Int = 2048,
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = false,
    val supportsSystemMessage: Boolean = true,
    val enabled: Boolean = true,
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val customParamsJson: String = "{}",
    val notes: String = "",
    val isEmbeddingModel: Boolean = false,
    val embeddingDimensions: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ConversationGoalPreset {
    DEFAULT, STUDY_GUIDE, ANALYTICAL, CUSTOM
}

enum class ResponseLengthPreset {
    SHORT, DEFAULT, LONG
}

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId"), Index("updatedAt")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val title: String,
    val selectedModelInternalId: String? = null,
    val systemRolePreset: String = ConversationGoalPreset.DEFAULT.name,
    val customSystemPrompt: String = "",
    val responseLength: String = ResponseLengthPreset.DEFAULT.name,
    val runningSummary: String = "",
    val summarizedUpToOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL
}

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId"), Index("orderIndex")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val modelDisplayName: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val tokenCountEstimate: Int = 0,
    val contextTokensUsed: Int = 0,
    val isPinned: Boolean = false,
    val isError: Boolean = false,
    val isPartial: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "citations",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId"), Index("sourceId")]
)
data class CitationEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val sourceId: String,
    val sourceName: String,
    val chunkId: String,
    val pageNumber: Int,
    val sectionTitle: String,
    val startOffset: Int,
    val endOffset: Int,
    val excerpt: String,
    val relevanceScore: Float
)

@Entity(tableName = "tool_definitions")
data class ToolDefinitionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
    val requiresUserApproval: Boolean,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = true
)

enum class JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
}

@Entity(
    tableName = "processing_jobs",
    indices = [Index("notebookId"), Index("sourceId")]
)
data class ProcessingJobEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val sourceId: String,
    val jobType: String,
    val status: String = JobStatus.QUEUED.name,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val workRequestId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "studio_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId"), Index("updatedAt")]
)
data class StudioArtifactEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val artifactType: String,
    val title: String,
    val content: String,
    val sourceCount: Int,
    val modelDisplayName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_records")
data class SyncRecordEntity(
    @PrimaryKey val recordKey: String,
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val contentHash: String,
    val localUpdatedAt: Long,
    val lastSyncedAt: Long,
    val remoteDriveFileId: String? = null,
    val isDirty: Boolean = true
)

@Entity(tableName = "sync_tombstones")
data class SyncTombstoneEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val deletedAt: Long,
    val revision: Long,
    val syncedToRemote: Boolean = false
)

@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val title: String,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long,
    val localSummary: String,
    val remoteSummary: String,
    val remotePayloadJson: String,
    val resolutionStatus: String = "PENDING",
    val detectedAt: Long = System.currentTimeMillis()
)
