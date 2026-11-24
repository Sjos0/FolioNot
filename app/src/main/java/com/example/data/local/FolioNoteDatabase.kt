package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Database(
    entities = [
        CollectionEntity::class,
        NotebookEntity::class,
        SourceEntity::class,
        DocumentChunkEntity::class,
        EmbeddingEntity::class,
        ProviderEntity::class,
        ModelEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        CitationEntity::class,
        ToolDefinitionEntity::class,
        ProcessingJobEntity::class,
        StudioArtifactEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FolioNoteDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun sourceDao(): SourceDao
    abstract fun chunkAndEmbeddingDao(): ChunkAndEmbeddingDao
    abstract fun providerAndModelDao(): ProviderAndModelDao
    abstract fun conversationAndMessageDao(): ConversationAndMessageDao
    abstract fun toolAndJobDao(): ToolAndJobDao

    companion object {
        @Volatile
        private var INSTANCE: FolioNoteDatabase? = null

        fun getInstance(context: Context): FolioNoteDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FolioNoteDatabase::class.java,
                    "folionote_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

object VectorCodec {
    fun floatArrayToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = bytes.size / 4
        return FloatArray(count) { buffer.getFloat() }
    }
}
