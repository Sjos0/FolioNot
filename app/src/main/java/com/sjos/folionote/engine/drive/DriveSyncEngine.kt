package com.sjos.folionote.engine.drive

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sjos.folionote.FolioNoteApp
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.DriveConflictPolicy
import com.sjos.folionote.data.local.CitationEntity
import com.sjos.folionote.data.local.CollectionEntity
import com.sjos.folionote.data.local.ConversationEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.StudioArtifactEntity
import com.sjos.folionote.data.local.SyncConflictEntity
import com.sjos.folionote.data.local.SyncRecordEntity
import com.sjos.folionote.data.local.SyncTombstoneEntity
import com.sjos.folionote.engine.document.FileStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConflictResolutionChoice {
    KEEP_LOCAL,
    KEEP_REMOTE,
    DUPLICATE_BOTH
}

data class DriveSyncRuntimeState(
    val isBusy: Boolean = false,
    val activeOperationLabel: String = "",
    val progress: Float = 0f,
    val backups: List<DriveFileItem> = emptyList(),
    val lastError: String? = null,
    val lastSyncSummary: String? = null
)

data class SyncExecutionResult(
    val uploadedCount: Int,
    val downloadedCount: Int,
    val deletedLocallyCount: Int,
    val deletedRemotelyCount: Int,
    val conflictsDetected: Int,
    val duplicatedConflicts: Int
)

/**
 * Orchestrates Google Drive backups CRUD and bidirectional multi-device sync
 * with stable UUIDs, SyncRecordEntity, SyncTombstoneEntity, SyncConflictEntity,
 * resumable transfers, and WorkManager offline/retry recovery.
 *
 * Strictly excludes all API keys, OAuth tokens, and private credentials from remote payloads.
 */
class DriveSyncEngine(
    private val context: Context,
    private val database: FolioNoteDatabase,
    private val preferencesStore: AppPreferencesStore,
    private val fileStorageManager: FileStorageManager,
    private val authManager: GoogleDriveAuthManager,
    private val apiClient: GoogleDriveApiClient,
    private val backupArchiveManager: BackupArchiveManager
) {
    companion object {
        const val SYNC_MANIFEST_FILENAME = "folionote_sync_state_v1.json"
        const val ENTITY_COLLECTION = "COLLECTION"
        const val ENTITY_NOTEBOOK = "NOTEBOOK"
        const val ENTITY_SOURCE = "SOURCE"
        const val ENTITY_CONVERSATION = "CONVERSATION"
        const val ENTITY_MESSAGE = "MESSAGE"
        const val ENTITY_CITATION = "CITATION"
        const val ENTITY_ARTIFACT = "ARTIFACT"

        fun recordKey(entityType: String, entityId: String): String = "$entityType:$entityId"
    }

    private val _runtimeState = MutableStateFlow(DriveSyncRuntimeState())
    val runtimeState: StateFlow<DriveSyncRuntimeState> = _runtimeState.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var pendingBackupUploadFile: File? = null

    @Volatile
    private var pendingBackupUploadName: String? = null

    @Volatile
    private var pendingBackupSessionUri: String? = null

    fun cancelActiveOperation() {
        cancelRequested.set(true)
        _runtimeState.value = _runtimeState.value.copy(
            activeOperationLabel = "Cancelando operação..."
        )
    }

    fun clearError() {
        _runtimeState.value = _runtimeState.value.copy(lastError = null)
    }

    suspend fun refreshBackupList(): Result<List<DriveFileItem>> {
        _runtimeState.value = _runtimeState.value.copy(
            isBusy = true,
            activeOperationLabel = "Listando backups no Google Drive...",
            progress = 0.2f,
            lastError = null
        )
        val token = authManager.getValidAccessToken().getOrElse { err ->
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = false,
                activeOperationLabel = "",
                progress = 0f,
                lastError = err.message
            )
            return Result.failure(err)
        }
        val listRes = apiClient.listBackups(token)
        listRes.onSuccess { items ->
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = false,
                activeOperationLabel = "",
                progress = 0f,
                backups = items
            )
        }.onFailure { err ->
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = false,
                activeOperationLabel = "",
                progress = 0f,
                lastError = err.message
            )
        }
        return listRes
    }

    suspend fun createAndUploadBackup(
        customName: String? = null,
        scheduleRetryOnNetworkError: Boolean = true
    ): Result<DriveFileItem> {
        cancelRequested.set(false)
        _runtimeState.value = _runtimeState.value.copy(
            isBusy = true,
            activeOperationLabel = "Gerando arquivo de backup local...",
            progress = 0.1f,
            lastError = null
        )

        val token = authManager.getValidAccessToken().getOrElse { err ->
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = false,
                activeOperationLabel = "",
                progress = 0f,
                lastError = err.message
            )
            return Result.failure(err)
        }

        val now = System.currentTimeMillis()
        val fileName = pendingBackupUploadName ?: backupArchiveManager.defaultBackupFileName(customName, now)
        val stagingFile = pendingBackupUploadFile ?: File(context.cacheDir, fileName)
        if (pendingBackupUploadFile == null || !stagingFile.exists()) {
            val summary = runCatching {
                backupArchiveManager.createBackupArchive(stagingFile, createdAt = now)
            }.getOrElse { err ->
                _runtimeState.value = _runtimeState.value.copy(
                    isBusy = false,
                    activeOperationLabel = "",
                    progress = 0f,
                    lastError = err.message
                )
                return Result.failure(err)
            }
            pendingBackupUploadFile = summary.archiveFile
            pendingBackupUploadName = fileName
        }

        _runtimeState.value = _runtimeState.value.copy(
            activeOperationLabel = "Enviando backup para o Google Drive...",
            progress = 0.25f
        )

        val uploadRes = apiClient.resumableUploadFile(
            accessToken = token,
            localFile = stagingFile,
            remoteFileName = fileName,
            mimeType = "application/zip",
            existingSessionUri = pendingBackupSessionUri,
            appProperties = mapOf("folionote_kind" to "backup", "created_at" to now.toString()),
            onSessionCreated = { sessionUri -> pendingBackupSessionUri = sessionUri },
            onProgress = { pct ->
                _runtimeState.value = _runtimeState.value.copy(
                    progress = 0.25f + (pct * 0.7f)
                )
            },
            isCancelled = { cancelRequested.get() }
        )

        return uploadRes.fold(
            onSuccess = { uploaded ->
                runCatching { stagingFile.delete() }
                pendingBackupUploadFile = null
                pendingBackupUploadName = null
                pendingBackupSessionUri = null
                preferencesStore.updateDriveSyncTelemetry(
                    lastBackupAt = now,
                    status = "Backup salvo: ${uploaded.name}",
                    clearError = true
                )
                val updatedList = apiClient.listBackups(token).getOrDefault(
                    listOf(uploaded) + _runtimeState.value.backups.filterNot { it.id == uploaded.id }
                )
                _runtimeState.value = _runtimeState.value.copy(
                    isBusy = false,
                    activeOperationLabel = "",
                    progress = 0f,
                    backups = updatedList
                )
                Result.success(uploaded)
            },
            onFailure = { err ->
                val isCancelled = err is CancellationException
                if (isCancelled) {
                    runCatching { stagingFile.delete() }
                    pendingBackupUploadFile = null
                    pendingBackupUploadName = null
                    pendingBackupSessionUri = null
                } else if (scheduleRetryOnNetworkError && err is IOException) {
                    DriveSyncWorker.enqueueOneTime(context, DriveSyncWorker.MODE_BACKUP)
                }
                val msg = if (isCancelled) "Backup cancelado." else (err.message ?: "Falha ao enviar backup.")
                preferencesStore.updateDriveSyncTelemetry(status = "Falha no backup", error = msg)
                _runtimeState.value = _runtimeState.value.copy(
                    isBusy = false,
                    activeOperationLabel = "",
                    progress = 0f,
                    lastError = msg
                )
                Result.failure(err)
            }
        )
    }

    suspend fun renameBackup(fileId: String, newName: String): Result<DriveFileItem> {
        val clean = newName.trim().let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        _runtimeState.value = _runtimeState.value.copy(
            isBusy = true,
            activeOperationLabel = "Renomeando backup...",
            lastError = null
        )
        val token = authManager.getValidAccessToken().getOrElse { err ->
            _runtimeState.value = _runtimeState.value.copy(isBusy = false, activeOperationLabel = "", lastError = err.message)
            return Result.failure(err)
        }
        val res = apiClient.renameFile(token, fileId, clean)
        res.onSuccess { updated ->
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = false,
                activeOperationLabel = "",
                backups = _runtimeState.value.backups.map { if (it.id == fileId) updated else it }
            )
        }.onFailure { err ->
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = false,
                activeOperationLabel = "",
                lastError = err.message
            )
        }
        return res
    }

    suspend fun restoreBackup(item: DriveFileItem): Result<RestoreSummary> {
        cancelRequested.set(false)
        _runtimeState.value = _runtimeState.value.copy(
            isBusy = true,
            activeOperationLabel = "Baixando backup '${item.name}'...",
            progress = 0.1f,
            lastError = null
        )
        val token = authManager.getValidAccessToken().getOrElse { err ->
            _runtimeState.value = _runtimeState.value.copy(isBusy = false, activeOperationLabel = "", lastError = err.message)
            return Result.failure(err)
        }

        val downloadTarget = File(context.cacheDir, "restore_${item.id}.zip")
        val dlRes = apiClient.downloadFile(
            accessToken = token,
            fileId = item.id,
            destFile = downloadTarget,
            resumeIfPartial = true,
            onProgress = { pct ->
                _runtimeState.value = _runtimeState.value.copy(progress = 0.1f + pct * 0.65f)
            },
            isCancelled = { cancelRequested.get() }
        )

        val downloadedFile = dlRes.getOrElse { err ->
            val msg = err.message ?: "Falha ao baixar backup."
            _runtimeState.value = _runtimeState.value.copy(isBusy = false, activeOperationLabel = "", progress = 0f, lastError = msg)
            return Result.failure(err)
        }

        _runtimeState.value = _runtimeState.value.copy(
            activeOperationLabel = "Restaurando notebooks e fontes localmente...",
            progress = 0.85f
        )

        val restoreRes = runCatching {
            backupArchiveManager.restoreFromBackupArchive(downloadedFile)
        }
        runCatching { downloadedFile.delete() }

        return restoreRes.fold(
            onSuccess = { summary ->
                preferencesStore.updateDriveSyncTelemetry(
                    status = "Restaurado: ${summary.notebooksRestored} notebook(s), ${summary.sourcesRestored} fonte(s)",
                    clearError = true
                )
                _runtimeState.value = _runtimeState.value.copy(
                    isBusy = false,
                    activeOperationLabel = "",
                    progress = 0f,
                    lastSyncSummary = "Backup restaurado: ${summary.notebooksRestored} notebook(s) e ${summary.filesCopied} arquivo(s)."
                )
                Result.success(summary)
            },
            onFailure = { err ->
                val msg = err.message ?: "Falha ao restaurar backup."
                preferencesStore.updateDriveSyncTelemetry(status = "Falha ao restaurar", error = msg)
                _runtimeState.value = _runtimeState.value.copy(
                    isBusy = false,
                    activeOperationLabel = "",
                    progress = 0f,
                    lastError = msg
                )
                Result.failure(err)
            }
        )
    }

    suspend fun deleteBackup(fileId: String): Result<Unit> {
        _runtimeState.value = _runtimeState.value.copy(
            isBusy = true,
            activeOperationLabel = "Excluindo backup...",
            lastError = null
        )
        val token = authManager.getValidAccessToken().getOrElse { err ->
            _runtimeState.value = _runtimeState.value.copy(isBusy = false, activeOperationLabel = "", lastError = err.message)
            return Result.failure(err)
        }
        val res = apiClient.deleteFile(token, fileId)
        res.onSuccess {
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = false,
                activeOperationLabel = "",
                backups = _runtimeState.value.backups.filterNot { it.id == fileId }
            )
        }.onFailure { err ->
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = false,
                activeOperationLabel = "",
                lastError = err.message
            )
        }
        return res
    }

    /**
     * Bidirectional incremental synchronization with conflict detection, tombstones,
     * and source binary upload/download.
     */
    suspend fun syncNow(scheduleRetryOnNetworkError: Boolean = true): Result<SyncExecutionResult> =
        withContext(Dispatchers.IO) {
            cancelRequested.set(false)
            _runtimeState.value = _runtimeState.value.copy(
                isBusy = true,
                activeOperationLabel = "Conectando ao Google Drive...",
                progress = 0.08f,
                lastError = null
            )

            val token = authManager.getValidAccessToken().getOrElse { err ->
                _runtimeState.value = _runtimeState.value.copy(
                    isBusy = false,
                    activeOperationLabel = "",
                    progress = 0f,
                    lastError = err.message
                )
                return@withContext Result.failure(err)
            }

            val runRes = runCatching {
                val settings = preferencesStore.settingsFlow.first()
                val syncDao = database.syncDao()
                val now = System.currentTimeMillis()

                // 1. Fetch remote sync manifest (if present)
                _runtimeState.value = _runtimeState.value.copy(
                    activeOperationLabel = "Verificando estado remoto...",
                    progress = 0.2f
                )
                val remoteManifestItem = apiClient.findFileByNameInAppData(token, SYNC_MANIFEST_FILENAME).getOrThrow()
                val remoteRoot = if (remoteManifestItem != null) {
                    val tmpManifest = File(context.cacheDir, "remote_sync_manifest.json")
                    apiClient.downloadFile(token, remoteManifestItem.id, tmpManifest, resumeIfPartial = false).getOrThrow()
                    val text = tmpManifest.readText(Charsets.UTF_8)
                    runCatching { tmpManifest.delete() }
                    if (text.isNotBlank()) JSONObject(text) else JSONObject()
                } else {
                    JSONObject()
                }

                // 2. Build local snapshot & local hashes
                val localSnapshot = readLocalSnapshot()
                val existingRecords = syncDao.getAllSyncRecordsOnce().associateBy { it.recordKey }
                val localTombstones = syncDao.getAllTombstonesOnce().associateBy { recordKey(it.entityType, it.entityId) }

                val remoteCollections = parseMapById(remoteRoot.optJSONArray("collections"))
                val remoteNotebooks = parseMapById(remoteRoot.optJSONArray("notebooks"))
                val remoteSources = parseMapById(remoteRoot.optJSONArray("sources"))
                val remoteConversations = parseMapById(remoteRoot.optJSONArray("conversations"))
                val remoteMessages = parseMapById(remoteRoot.optJSONArray("messages"))
                val remoteArtifacts = parseMapById(remoteRoot.optJSONArray("studioArtifacts"))
                val remoteTombstones = parseTombstoneMap(remoteRoot.optJSONArray("tombstones"))

                var uploadedCount = 0
                var downloadedCount = 0
                var deletedLocallyCount = 0
                var deletedRemotelyCount = 0
                var conflictsDetected = 0
                var duplicatedConflicts = 0

                val mergedCollections = linkedMapOf<String, JSONObject>()
                val mergedNotebooks = linkedMapOf<String, JSONObject>()
                val mergedSources = linkedMapOf<String, JSONObject>()
                val mergedConversations = linkedMapOf<String, JSONObject>()
                val mergedMessages = linkedMapOf<String, JSONObject>()
                val mergedArtifacts = linkedMapOf<String, JSONObject>()
                val mergedTombstones = linkedMapOf<String, JSONObject>()

                val recordsToUpsert = mutableListOf<SyncRecordEntity>()

                // Helper to reconcile a homogeneous entity map in parent-first order
                suspend fun reconcileGroup(
                    entityType: String,
                    localItems: Map<String, JSONObject>,
                    remoteItems: Map<String, JSONObject>,
                    mergedOut: MutableMap<String, JSONObject>,
                    titleExtractor: (JSONObject) -> String,
                    applyRemote: suspend (JSONObject) -> Unit,
                    applyRemoteAsDuplicate: suspend (JSONObject) -> Unit,
                    deleteLocal: suspend (String) -> Unit
                ) {
                    val allIds = (localItems.keys + remoteItems.keys).toSet()
                    for (id in allIds) {
                        if (cancelRequested.get()) throw CancellationException("Sincronização cancelada.")
                        val key = recordKey(entityType, id)
                        val localObj = localItems[id]
                        val remoteObj = remoteItems[id]
                        val record = existingRecords[key]
                        val localTomb = localTombstones[key]
                        val remoteTomb = remoteTombstones[key]
                        val lastSyncedAt = record?.lastSyncedAt ?: 0L

                        when {
                            localObj != null && remoteObj == null -> {
                                val remoteDeletedAt = remoteTomb?.optLong("deletedAt", 0L) ?: 0L
                                val localUpdated = localObj.optLong("updatedAt", localObj.optLong("createdAt", 0L))
                                if (remoteTomb != null && remoteDeletedAt > lastSyncedAt) {
                                    if (localUpdated > remoteDeletedAt && lastSyncedAt > 0L) {
                                        // Modified locally after remote deletion
                                        val handled = handleConflict(
                                            policy = settings.driveConflictPolicy,
                                            entityType = entityType,
                                            entityId = id,
                                            title = titleExtractor(localObj),
                                            localUpdatedAt = localUpdated,
                                            remoteUpdatedAt = remoteDeletedAt,
                                            localSummary = "Editado localmente após exclusão remota",
                                            remoteSummary = "Excluído em outro dispositivo",
                                            remoteObj = JSONObject().put("_deleted", true).put("id", id),
                                            onKeepRemote = {
                                                deleteLocal(id)
                                                deletedLocallyCount++
                                                mergedTombstones[key] = remoteTomb
                                            },
                                            onDuplicateBoth = {
                                                mergedOut[id] = localObj
                                                uploadedCount++
                                            }
                                        )
                                        if (handled == ConflictAction.PENDING_USER) {
                                            conflictsDetected++
                                            mergedOut[id] = localObj
                                        }
                                    } else {
                                        deleteLocal(id)
                                        deletedLocallyCount++
                                        mergedTombstones[key] = remoteTomb
                                    }
                                } else {
                                    val pushed = prepareEntityForRemotePush(entityType, localObj, token)
                                    mergedOut[id] = pushed
                                    uploadedCount++
                                    recordsToUpsert.add(
                                        SyncRecordEntity(
                                            recordKey = key,
                                            entityType = entityType,
                                            entityId = id,
                                            revision = (record?.revision ?: 0L) + 1L,
                                            contentHash = hashJson(pushed),
                                            localUpdatedAt = localUpdated,
                                            lastSyncedAt = now,
                                            remoteDriveFileId = pushed.optString("remoteFileId").takeIf { it.isNotBlank() },
                                            isDirty = false
                                        )
                                    )
                                }
                            }

                            localObj == null && remoteObj != null -> {
                                val localDeletedAt = localTomb?.deletedAt ?: 0L
                                val remoteUpdated = remoteObj.optLong("updatedAt", remoteObj.optLong("createdAt", 0L))
                                if (localTomb != null && localDeletedAt >= remoteUpdated) {
                                    mergedTombstones[key] = tombstoneToJson(localTomb.copy(syncedToRemote = true))
                                    syncDao.upsertTombstone(localTomb.copy(syncedToRemote = true))
                                    deletedRemotelyCount++
                                } else {
                                    val preparedLocal = prepareEntityForLocalApply(entityType, remoteObj, token)
                                    applyRemote(preparedLocal)
                                    mergedOut[id] = remoteObj
                                    downloadedCount++
                                    recordsToUpsert.add(
                                        SyncRecordEntity(
                                            recordKey = key,
                                            entityType = entityType,
                                            entityId = id,
                                            revision = (record?.revision ?: 0L) + 1L,
                                            contentHash = hashJson(remoteObj),
                                            localUpdatedAt = remoteUpdated,
                                            lastSyncedAt = now,
                                            remoteDriveFileId = remoteObj.optString("remoteFileId").takeIf { it.isNotBlank() },
                                            isDirty = false
                                        )
                                    )
                                }
                            }

                            localObj != null && remoteObj != null -> {
                                val localHash = hashJson(localObj)
                                val remoteHash = hashJson(remoteObj)
                                val localUpdated = localObj.optLong("updatedAt", localObj.optLong("createdAt", 0L))
                                val remoteUpdated = remoteObj.optLong("updatedAt", remoteObj.optLong("createdAt", 0L))

                                if (localHash == remoteHash) {
                                    mergedOut[id] = remoteObj
                                    recordsToUpsert.add(
                                        SyncRecordEntity(
                                            recordKey = key,
                                            entityType = entityType,
                                            entityId = id,
                                            revision = (record?.revision ?: 1L),
                                            contentHash = localHash,
                                            localUpdatedAt = localUpdated,
                                            lastSyncedAt = now,
                                            remoteDriveFileId = remoteObj.optString("remoteFileId").takeIf { it.isNotBlank() },
                                            isDirty = false
                                        )
                                    )
                                } else {
                                    val localDirty = record == null || record.contentHash != localHash || localUpdated > lastSyncedAt
                                    val remoteDirty = record == null || record.contentHash != remoteHash || remoteUpdated > lastSyncedAt

                                    when {
                                        localDirty && !remoteDirty -> {
                                            val pushed = prepareEntityForRemotePush(entityType, localObj, token)
                                            mergedOut[id] = pushed
                                            uploadedCount++
                                            recordsToUpsert.add(
                                                SyncRecordEntity(
                                                    recordKey = key,
                                                    entityType = entityType,
                                                    entityId = id,
                                                    revision = (record?.revision ?: 0L) + 1L,
                                                    contentHash = hashJson(pushed),
                                                    localUpdatedAt = localUpdated,
                                                    lastSyncedAt = now,
                                                    remoteDriveFileId = pushed.optString("remoteFileId").takeIf { it.isNotBlank() },
                                                    isDirty = false
                                                )
                                            )
                                        }
                                        !localDirty && remoteDirty -> {
                                            val preparedLocal = prepareEntityForLocalApply(entityType, remoteObj, token)
                                            applyRemote(preparedLocal)
                                            mergedOut[id] = remoteObj
                                            downloadedCount++
                                            recordsToUpsert.add(
                                                SyncRecordEntity(
                                                    recordKey = key,
                                                    entityType = entityType,
                                                    entityId = id,
                                                    revision = (record?.revision ?: 0L) + 1L,
                                                    contentHash = remoteHash,
                                                    localUpdatedAt = remoteUpdated,
                                                    lastSyncedAt = now,
                                                    remoteDriveFileId = remoteObj.optString("remoteFileId").takeIf { it.isNotBlank() },
                                                    isDirty = false
                                                )
                                            )
                                        }
                                        else -> {
                                            // Genuine concurrent modification on both sides!
                                            val action = handleConflict(
                                                policy = settings.driveConflictPolicy,
                                                entityType = entityType,
                                                entityId = id,
                                                title = titleExtractor(localObj),
                                                localUpdatedAt = localUpdated,
                                                remoteUpdatedAt = remoteUpdated,
                                                localSummary = summarizeEntity(localObj),
                                                remoteSummary = summarizeEntity(remoteObj),
                                                remoteObj = remoteObj,
                                                onKeepRemote = {
                                                    val preparedLocal = prepareEntityForLocalApply(entityType, remoteObj, token)
                                                    applyRemote(preparedLocal)
                                                    mergedOut[id] = remoteObj
                                                    downloadedCount++
                                                    recordsToUpsert.add(
                                                        SyncRecordEntity(
                                                            recordKey = key,
                                                            entityType = entityType,
                                                            entityId = id,
                                                            revision = (record?.revision ?: 0L) + 1L,
                                                            contentHash = remoteHash,
                                                            localUpdatedAt = remoteUpdated,
                                                            lastSyncedAt = now,
                                                            remoteDriveFileId = remoteObj.optString("remoteFileId").takeIf { it.isNotBlank() },
                                                            isDirty = false
                                                        )
                                                    )
                                                },
                                                onDuplicateBoth = {
                                                    val pushedLocal = prepareEntityForRemotePush(entityType, localObj, token)
                                                    mergedOut[id] = pushedLocal
                                                    val preparedDup = prepareEntityForLocalApply(entityType, remoteObj, token)
                                                    applyRemoteAsDuplicate(preparedDup)
                                                    duplicatedConflicts++
                                                }
                                            )
                                            when (action) {
                                                ConflictAction.PENDING_USER -> {
                                                    conflictsDetected++
                                                    mergedOut[id] = remoteObj
                                                }
                                                ConflictAction.KEEP_LOCAL -> {
                                                    val pushed = prepareEntityForRemotePush(entityType, localObj, token)
                                                    mergedOut[id] = pushed
                                                    uploadedCount++
                                                    recordsToUpsert.add(
                                                        SyncRecordEntity(
                                                            recordKey = key,
                                                            entityType = entityType,
                                                            entityId = id,
                                                            revision = (record?.revision ?: 0L) + 1L,
                                                            contentHash = hashJson(pushed),
                                                            localUpdatedAt = localUpdated,
                                                            lastSyncedAt = now,
                                                            remoteDriveFileId = pushed.optString("remoteFileId").takeIf { it.isNotBlank() },
                                                            isDirty = false
                                                        )
                                                    )
                                                }
                                                ConflictAction.KEEP_REMOTE, ConflictAction.DUPLICATED -> Unit
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Propagate remaining local tombstones that never existed remotely
                for ((key, tomb) in localTombstones) {
                    mergedTombstones[key] = tombstoneToJson(tomb.copy(syncedToRemote = true))
                    if (!tomb.syncedToRemote) {
                        syncDao.upsertTombstone(tomb.copy(syncedToRemote = true))
                    }
                }
                for ((key, tombObj) in remoteTombstones) {
                    if (!mergedTombstones.containsKey(key)) mergedTombstones[key] = tombObj
                }

                _runtimeState.value = _runtimeState.value.copy(
                    activeOperationLabel = "Sincronizando coleções e notebooks...",
                    progress = 0.4f
                )

                val nbDao = database.notebookDao()
                val srcDao = database.sourceDao()
                val convDao = database.conversationAndMessageDao()

                reconcileGroup(
                    entityType = ENTITY_COLLECTION,
                    localItems = localSnapshot.collections,
                    remoteItems = remoteCollections,
                    mergedOut = mergedCollections,
                    titleExtractor = { it.optString("name", "Coleção") },
                    applyRemote = { nbDao.upsertCollection(jsonToCollection(it)) },
                    applyRemoteAsDuplicate = { obj ->
                        val dup = jsonToCollection(obj).let {
                            it.copy(id = UUID.randomUUID().toString(), name = "${it.name} (Nuvem)")
                        }
                        nbDao.upsertCollection(dup)
                    },
                    deleteLocal = { nbDao.deleteCollectionById(it) }
                )

                reconcileGroup(
                    entityType = ENTITY_NOTEBOOK,
                    localItems = localSnapshot.notebooks,
                    remoteItems = remoteNotebooks,
                    mergedOut = mergedNotebooks,
                    titleExtractor = { it.optString("title", "Notebook") },
                    applyRemote = { nbDao.upsertNotebook(jsonToNotebook(it)) },
                    applyRemoteAsDuplicate = { obj ->
                        val dup = jsonToNotebook(obj).let {
                            it.copy(id = UUID.randomUUID().toString(), title = "${it.title} (Nuvem)")
                        }
                        nbDao.upsertNotebook(dup)
                    },
                    deleteLocal = { nbDao.deleteNotebookById(it) }
                )

                _runtimeState.value = _runtimeState.value.copy(
                    activeOperationLabel = "Sincronizando fontes e arquivos...",
                    progress = 0.6f
                )

                reconcileGroup(
                    entityType = ENTITY_SOURCE,
                    localItems = localSnapshot.sources,
                    remoteItems = remoteSources,
                    mergedOut = mergedSources,
                    titleExtractor = { it.optString("name", "Fonte") },
                    applyRemote = { srcDao.upsertSource(jsonToSource(it)) },
                    applyRemoteAsDuplicate = { obj ->
                        val dupId = UUID.randomUUID().toString()
                        val src = jsonToSource(obj)
                        val dup = src.copy(id = dupId, name = "${src.name} (Nuvem)")
                        srcDao.upsertSource(dup)
                    },
                    deleteLocal = { id ->
                        srcDao.getSourceById(id)?.let { fileStorageManager.deleteSourceFile(it.filePath) }
                        srcDao.deleteSourceById(id)
                    }
                )

                _runtimeState.value = _runtimeState.value.copy(
                    activeOperationLabel = "Sincronizando conversas e notas...",
                    progress = 0.78f
                )

                reconcileGroup(
                    entityType = ENTITY_CONVERSATION,
                    localItems = localSnapshot.conversations,
                    remoteItems = remoteConversations,
                    mergedOut = mergedConversations,
                    titleExtractor = { it.optString("title", "Conversa") },
                    applyRemote = { convDao.upsertConversation(jsonToConversation(it)) },
                    applyRemoteAsDuplicate = { obj ->
                        val c = jsonToConversation(obj)
                        convDao.upsertConversation(c.copy(id = UUID.randomUUID().toString(), title = "${c.title} (Nuvem)"))
                    },
                    deleteLocal = { convDao.deleteConversationById(it) }
                )

                reconcileGroup(
                    entityType = ENTITY_MESSAGE,
                    localItems = localSnapshot.messages,
                    remoteItems = remoteMessages,
                    mergedOut = mergedMessages,
                    titleExtractor = { it.optString("content", "Mensagem").take(48) },
                    applyRemote = { convDao.upsertMessage(jsonToMessage(it)) },
                    applyRemoteAsDuplicate = { obj ->
                        val m = jsonToMessage(obj)
                        convDao.upsertMessage(m.copy(id = UUID.randomUUID().toString()))
                    },
                    deleteLocal = { convDao.deleteMessageById(it) }
                )

                reconcileGroup(
                    entityType = ENTITY_ARTIFACT,
                    localItems = localSnapshot.artifacts,
                    remoteItems = remoteArtifacts,
                    mergedOut = mergedArtifacts,
                    titleExtractor = { it.optString("title", "Material") },
                    applyRemote = { convDao.upsertStudioArtifact(jsonToArtifact(it)) },
                    applyRemoteAsDuplicate = { obj ->
                        val a = jsonToArtifact(obj)
                        convDao.upsertStudioArtifact(a.copy(id = UUID.randomUUID().toString(), title = "${a.title} (Nuvem)"))
                    },
                    deleteLocal = { convDao.deleteStudioArtifactById(it) }
                )

                if (recordsToUpsert.isNotEmpty()) {
                    syncDao.upsertSyncRecords(recordsToUpsert)
                }

                // 3. Upload updated merged sync manifest to Drive
                _runtimeState.value = _runtimeState.value.copy(
                    activeOperationLabel = "Atualizando manifesto no Google Drive...",
                    progress = 0.9f
                )
                val nextManifest = JSONObject().apply {
                    put("syncVersion", 1)
                    put("updatedAt", now)
                    put("excludesSecrets", true)
                    put("collections", JSONArray().apply { mergedCollections.values.forEach { put(it) } })
                    put("notebooks", JSONArray().apply { mergedNotebooks.values.forEach { put(it) } })
                    put("sources", JSONArray().apply { mergedSources.values.forEach { put(it) } })
                    put("conversations", JSONArray().apply { mergedConversations.values.forEach { put(it) } })
                    put("messages", JSONArray().apply { mergedMessages.values.forEach { put(it) } })
                    put("studioArtifacts", JSONArray().apply { mergedArtifacts.values.forEach { put(it) } })
                    put("tombstones", JSONArray().apply { mergedTombstones.values.forEach { put(it) } })
                }

                val outManifestFile = File(context.cacheDir, "outgoing_sync_manifest.json")
                outManifestFile.writeText(nextManifest.toString(), Charsets.UTF_8)
                try {
                    apiClient.resumableUploadFile(
                        accessToken = token,
                        localFile = outManifestFile,
                        remoteFileName = SYNC_MANIFEST_FILENAME,
                        mimeType = "application/json",
                        existingFileId = remoteManifestItem?.id,
                        appProperties = mapOf("folionote_kind" to "sync_manifest"),
                        isCancelled = { cancelRequested.get() }
                    ).getOrThrow()
                } finally {
                    runCatching { outManifestFile.delete() }
                }

                val summaryText = buildString {
                    append("↑ $uploadedCount • ↓ $downloadedCount")
                    if (deletedLocallyCount + deletedRemotelyCount > 0) {
                        append(" • ✕ ${deletedLocallyCount + deletedRemotelyCount}")
                    }
                    if (conflictsDetected > 0) {
                        append(" • $conflictsDetected conflito(s) pendente(s)")
                    }
                }
                preferencesStore.updateDriveSyncTelemetry(
                    lastSyncAt = now,
                    status = summaryText,
                    clearError = true
                )

                SyncExecutionResult(
                    uploadedCount = uploadedCount,
                    downloadedCount = downloadedCount,
                    deletedLocallyCount = deletedLocallyCount,
                    deletedRemotelyCount = deletedRemotelyCount,
                    conflictsDetected = conflictsDetected,
                    duplicatedConflicts = duplicatedConflicts
                )
            }

            runRes.fold(
                onSuccess = { res ->
                    val label = "Sincronizado: ↑${res.uploadedCount} ↓${res.downloadedCount}" +
                        if (res.conflictsDetected > 0) " • ${res.conflictsDetected} conflito(s)" else ""
                    _runtimeState.value = _runtimeState.value.copy(
                        isBusy = false,
                        activeOperationLabel = "",
                        progress = 0f,
                        lastSyncSummary = label
                    )
                    Result.success(res)
                },
                onFailure = { err ->
                    if (scheduleRetryOnNetworkError && err is IOException) {
                        DriveSyncWorker.enqueueOneTime(context, DriveSyncWorker.MODE_SYNC)
                    }
                    val msg = err.message ?: "Falha na sincronização com o Google Drive."
                    preferencesStore.updateDriveSyncTelemetry(status = "Falha na sync", error = msg)
                    _runtimeState.value = _runtimeState.value.copy(
                        isBusy = false,
                        activeOperationLabel = "",
                        progress = 0f,
                        lastError = msg
                    )
                    Result.failure(err)
                }
            )
        }

    suspend fun resolveConflict(
        conflictId: String,
        choice: ConflictResolutionChoice
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val syncDao = database.syncDao()
            val conflict = syncDao.getConflictById(conflictId)
                ?: throw IllegalStateException("Conflito não encontrado.")
            val remoteObj = JSONObject(conflict.remotePayloadJson)
            val isRemoteDeleted = remoteObj.optBoolean("_deleted", false)
            val now = System.currentTimeMillis()

            val nbDao = database.notebookDao()
            val srcDao = database.sourceDao()
            val convDao = database.conversationAndMessageDao()

            when (choice) {
                ConflictResolutionChoice.KEEP_LOCAL -> {
                    syncDao.upsertSyncRecord(
                        SyncRecordEntity(
                            recordKey = recordKey(conflict.entityType, conflict.entityId),
                            entityType = conflict.entityType,
                            entityId = conflict.entityId,
                            revision = now,
                            contentHash = "force_local_$now",
                            localUpdatedAt = now,
                            lastSyncedAt = 0L,
                            isDirty = true
                        )
                    )
                }
                ConflictResolutionChoice.KEEP_REMOTE -> {
                    if (isRemoteDeleted) {
                        when (conflict.entityType) {
                            ENTITY_COLLECTION -> nbDao.deleteCollectionById(conflict.entityId)
                            ENTITY_NOTEBOOK -> nbDao.deleteNotebookById(conflict.entityId)
                            ENTITY_SOURCE -> srcDao.deleteSourceById(conflict.entityId)
                            ENTITY_CONVERSATION -> convDao.deleteConversationById(conflict.entityId)
                            ENTITY_MESSAGE -> convDao.deleteMessageById(conflict.entityId)
                            ENTITY_ARTIFACT -> convDao.deleteStudioArtifactById(conflict.entityId)
                        }
                    } else {
                        val token = authManager.getValidAccessToken().getOrNull()
                        val prepared = if (token != null) {
                            prepareEntityForLocalApply(conflict.entityType, remoteObj, token)
                        } else {
                            remoteObj
                        }
                        when (conflict.entityType) {
                            ENTITY_COLLECTION -> nbDao.upsertCollection(jsonToCollection(prepared))
                            ENTITY_NOTEBOOK -> nbDao.upsertNotebook(jsonToNotebook(prepared))
                            ENTITY_SOURCE -> srcDao.upsertSource(jsonToSource(prepared))
                            ENTITY_CONVERSATION -> convDao.upsertConversation(jsonToConversation(prepared))
                            ENTITY_MESSAGE -> convDao.upsertMessage(jsonToMessage(prepared))
                            ENTITY_ARTIFACT -> convDao.upsertStudioArtifact(jsonToArtifact(prepared))
                        }
                        syncDao.upsertSyncRecord(
                            SyncRecordEntity(
                                recordKey = recordKey(conflict.entityType, conflict.entityId),
                                entityType = conflict.entityType,
                                entityId = conflict.entityId,
                                revision = now,
                                contentHash = hashJson(remoteObj),
                                localUpdatedAt = conflict.remoteUpdatedAt,
                                lastSyncedAt = now,
                                isDirty = false
                            )
                        )
                    }
                }
                ConflictResolutionChoice.DUPLICATE_BOTH -> {
                    if (!isRemoteDeleted) {
                        val token = authManager.getValidAccessToken().getOrNull()
                        val prepared = if (token != null) {
                            prepareEntityForLocalApply(conflict.entityType, remoteObj, token)
                        } else {
                            remoteObj
                        }
                        val dupId = UUID.randomUUID().toString()
                        when (conflict.entityType) {
                            ENTITY_COLLECTION -> {
                                val c = jsonToCollection(prepared)
                                nbDao.upsertCollection(c.copy(id = dupId, name = "${c.name} (Nuvem)"))
                            }
                            ENTITY_NOTEBOOK -> {
                                val n = jsonToNotebook(prepared)
                                nbDao.upsertNotebook(n.copy(id = dupId, title = "${n.title} (Nuvem)"))
                            }
                            ENTITY_SOURCE -> {
                                val s = jsonToSource(prepared)
                                srcDao.upsertSource(s.copy(id = dupId, name = "${s.name} (Nuvem)"))
                            }
                            ENTITY_CONVERSATION -> {
                                val c = jsonToConversation(prepared)
                                convDao.upsertConversation(c.copy(id = dupId, title = "${c.title} (Nuvem)"))
                            }
                            ENTITY_MESSAGE -> {
                                val m = jsonToMessage(prepared)
                                convDao.upsertMessage(m.copy(id = dupId))
                            }
                            ENTITY_ARTIFACT -> {
                                val a = jsonToArtifact(prepared)
                                convDao.upsertStudioArtifact(a.copy(id = dupId, title = "${a.title} (Nuvem)"))
                            }
                        }
                    }
                }
            }
            syncDao.updateConflictStatus(conflictId, "RESOLVED_${choice.name}")
        }
    }

    private enum class ConflictAction {
        PENDING_USER,
        KEEP_LOCAL,
        KEEP_REMOTE,
        DUPLICATED
    }

    private suspend fun handleConflict(
        policy: DriveConflictPolicy,
        entityType: String,
        entityId: String,
        title: String,
        localUpdatedAt: Long,
        remoteUpdatedAt: Long,
        localSummary: String,
        remoteSummary: String,
        remoteObj: JSONObject,
        onKeepRemote: suspend () -> Unit,
        onDuplicateBoth: suspend () -> Unit
    ): ConflictAction {
        return when (policy) {
            DriveConflictPolicy.KEEP_LOCAL -> ConflictAction.KEEP_LOCAL
            DriveConflictPolicy.KEEP_REMOTE -> {
                onKeepRemote()
                ConflictAction.KEEP_REMOTE
            }
            DriveConflictPolicy.KEEP_NEWER -> {
                if (remoteUpdatedAt > localUpdatedAt) {
                    onKeepRemote()
                    ConflictAction.KEEP_REMOTE
                } else {
                    ConflictAction.KEEP_LOCAL
                }
            }
            DriveConflictPolicy.DUPLICATE_BOTH -> {
                onDuplicateBoth()
                ConflictAction.DUPLICATED
            }
            DriveConflictPolicy.ASK_USER -> {
                val conflictId = "${entityType}_$entityId"
                database.syncDao().upsertConflict(
                    SyncConflictEntity(
                        id = conflictId,
                        entityType = entityType,
                        entityId = entityId,
                        title = title,
                        localUpdatedAt = localUpdatedAt,
                        remoteUpdatedAt = remoteUpdatedAt,
                        localSummary = localSummary,
                        remoteSummary = remoteSummary,
                        remotePayloadJson = remoteObj.toString(),
                        resolutionStatus = "PENDING"
                    )
                )
                ConflictAction.PENDING_USER
            }
        }
    }

    private suspend fun prepareEntityForRemotePush(
        entityType: String,
        localObj: JSONObject,
        token: String
    ): JSONObject {
        if (entityType != ENTITY_SOURCE) return localObj
        val copy = JSONObject(localObj.toString())
        val filePath = copy.optString("localFilePath", "")
        val sourceId = copy.getString("id")
        val existingRemoteId = copy.optString("remoteFileId").takeIf { it.isNotBlank() }
        val localFile = if (filePath.isNotBlank()) File(filePath) else null
        if (localFile != null && localFile.exists() && existingRemoteId == null) {
            val mime = copy.optString("mimeType", "application/octet-stream")
            val uploaded = apiClient.resumableUploadFile(
                accessToken = token,
                localFile = localFile,
                remoteFileName = "folionote_src_${sourceId}_${localFile.name.take(40)}",
                mimeType = mime,
                appProperties = mapOf("folionote_kind" to "sync_source", "source_id" to sourceId),
                isCancelled = { cancelRequested.get() }
            ).getOrNull()
            if (uploaded != null) {
                copy.put("remoteFileId", uploaded.id)
                copy.put("remoteFileName", localFile.name)
            }
        }
        copy.remove("localFilePath")
        return copy
    }

    private suspend fun prepareEntityForLocalApply(
        entityType: String,
        remoteObj: JSONObject,
        token: String
    ): JSONObject {
        if (entityType != ENTITY_SOURCE) return remoteObj
        val copy = JSONObject(remoteObj.toString())
        val remoteFileId = copy.optString("remoteFileId").takeIf { it.isNotBlank() }
        if (remoteFileId != null) {
            val notebookId = copy.getString("notebookId")
            val sourceId = copy.getString("id")
            val fileName = copy.optString("remoteFileName").ifBlank {
                copy.optString("name", "source_$sourceId").replace(Regex("[^a-zA-Z0-9._-]"), "_")
            }
            val destDir = fileStorageManager.ensureNotebookSourcesDir(notebookId)
            val destFile = File(destDir, fileName)
            val dl = apiClient.downloadFile(token, remoteFileId, destFile, resumeIfPartial = true)
            if (dl.isSuccess) {
                copy.put("localFilePath", destFile.absolutePath)
                copy.put("sizeBytes", destFile.length())
            }
        }
        return copy
    }

    private data class LocalSyncSnapshot(
        val collections: Map<String, JSONObject>,
        val notebooks: Map<String, JSONObject>,
        val sources: Map<String, JSONObject>,
        val conversations: Map<String, JSONObject>,
        val messages: Map<String, JSONObject>,
        val artifacts: Map<String, JSONObject>
    )

    private suspend fun readLocalSnapshot(): LocalSyncSnapshot {
        val nbDao = database.notebookDao()
        val srcDao = database.sourceDao()
        val convDao = database.conversationAndMessageDao()

        return LocalSyncSnapshot(
            collections = nbDao.getAllCollectionsOnce().associate { it.id to collectionToJson(it) },
            notebooks = nbDao.getAllNotebooksOnce().associate { it.id to notebookToJson(it) },
            sources = srcDao.getAllSourcesOnce().associate { it.id to sourceToJson(it) },
            conversations = convDao.getAllConversationsOnce().associate { it.id to conversationToJson(it) },
            messages = convDao.getAllMessagesOnce().associate { it.id to messageToJson(it) },
            artifacts = convDao.getAllStudioArtifactsOnce().associate { it.id to artifactToJson(it) }
        )
    }

    private fun parseMapById(arr: JSONArray?): Map<String, JSONObject> {
        if (arr == null) return emptyMap()
        val map = linkedMapOf<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) map[id] = o
        }
        return map
    }

    private fun parseTombstoneMap(arr: JSONArray?): Map<String, JSONObject> {
        if (arr == null) return emptyMap()
        val map = linkedMapOf<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("entityType")
            val id = o.optString("entityId")
            if (type.isNotBlank() && id.isNotBlank()) map[recordKey(type, id)] = o
        }
        return map
    }

    private fun hashJson(obj: JSONObject): String {
        val copy = JSONObject(obj.toString()).apply {
            remove("remoteFileId")
            remove("remoteFileName")
            remove("localFilePath")
        }
        val keys = copy.keys().asSequence().toList().sorted()
        val canonical = keys.joinToString("|") { "$it=${copy.opt(it)}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun summarizeEntity(obj: JSONObject): String {
        return obj.optString("title").ifBlank {
            obj.optString("name").ifBlank {
                obj.optString("content").take(90)
            }
        }
    }

    private fun collectionToJson(c: CollectionEntity) = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("description", c.description)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
    }

    private fun jsonToCollection(o: JSONObject) = CollectionEntity(
        id = o.getString("id"),
        name = o.getString("name"),
        description = o.optString("description", ""),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

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

    private fun jsonToNotebook(o: JSONObject) = NotebookEntity(
        id = o.getString("id"),
        title = o.getString("title"),
        description = o.optString("description", ""),
        iconKey = o.optString("iconKey", "book"),
        collectionId = o.optString("collectionId").takeIf { !o.isNull("collectionId") && it.isNotBlank() },
        isPinned = o.optBoolean("isPinned", false),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun sourceToJson(s: SourceEntity) = JSONObject().apply {
        put("id", s.id)
        put("notebookId", s.notebookId)
        put("name", s.name)
        put("mimeType", s.mimeType)
        put("sourceType", s.sourceType)
        put("localFilePath", s.filePath)
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

    private fun jsonToSource(o: JSONObject) = SourceEntity(
        id = o.getString("id"),
        notebookId = o.getString("notebookId"),
        name = o.getString("name"),
        mimeType = o.optString("mimeType", "text/plain"),
        sourceType = o.optString("sourceType", "TXT"),
        filePath = o.optString("localFilePath", ""),
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

    private fun jsonToConversation(o: JSONObject) = ConversationEntity(
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

    private fun jsonToMessage(o: JSONObject) = MessageEntity(
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

    private fun jsonToArtifact(o: JSONObject) = StudioArtifactEntity(
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

    private fun tombstoneToJson(t: SyncTombstoneEntity) = JSONObject().apply {
        put("id", t.id)
        put("entityType", t.entityType)
        put("entityId", t.entityId)
        put("deletedAt", t.deletedAt)
        put("revision", t.revision)
    }
}

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODE = "drive_worker_mode"
        const val MODE_SYNC = "SYNC"
        const val MODE_BACKUP = "BACKUP"
        private const val UNIQUE_ONETIME = "folionote_drive_retry"
        private const val UNIQUE_PERIODIC = "folionote_drive_periodic_sync"

        fun enqueueOneTime(context: Context, mode: String = MODE_SYNC) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putString(KEY_MODE, mode).build())
                .addTag("drive_sync")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONETIME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun schedulePeriodicSync(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(UNIQUE_PERIODIC)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val periodic = PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putString(KEY_MODE, MODE_SYNC).build())
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? FolioNoteApp ?: return Result.failure()
        val settings = app.container.preferencesStore.settingsFlow.first()
        if (!settings.isGoogleDriveConnected) return Result.success()

        val mode = inputData.getString(KEY_MODE) ?: MODE_SYNC
        val outcome = when (mode) {
            MODE_BACKUP -> app.container.driveSyncEngine.createAndUploadBackup(scheduleRetryOnNetworkError = false)
            else -> app.container.driveSyncEngine.syncNow(scheduleRetryOnNetworkError = false)
        }

        return outcome.fold(
            onSuccess = { Result.success() },
            onFailure = { err ->
                if (err is IOException && runAttemptCount < 4) Result.retry() else Result.failure()
            }
        )
    }
}
