package com.sjos.folionote.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        StudioArtifactEntity::class,
        SyncRecordEntity::class,
        SyncTombstoneEntity::class,
        SyncConflictEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FolioNoteDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun sourceDao(): SourceDao
    abstract fun chunkAndEmbeddingDao(): ChunkAndEmbeddingDao
    abstract fun providerAndModelDao(): ProviderAndModelDao
    abstract fun conversationAndMessageDao(): ConversationAndMessageDao
    abstract fun toolAndJobDao(): ToolAndJobDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile
        private var INSTANCE: FolioNoteDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_records` (
                        `recordKey` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `localUpdatedAt` INTEGER NOT NULL,
                        `lastSyncedAt` INTEGER NOT NULL,
                        `remoteDriveFileId` TEXT,
                        `isDirty` INTEGER NOT NULL,
                        PRIMARY KEY(`recordKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_tombstones` (
                        `id` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `syncedToRemote` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                        `id` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `localUpdatedAt` INTEGER NOT NULL,
                        `remoteUpdatedAt` INTEGER NOT NULL,
                        `localSummary` TEXT NOT NULL,
                        `remoteSummary` TEXT NOT NULL,
                        `remotePayloadJson` TEXT NOT NULL,
                        `resolutionStatus` TEXT NOT NULL,
                        `detectedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): FolioNoteDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FolioNoteDatabase::class.java,
                    "folionote_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
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
