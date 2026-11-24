package com.example.engine.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.FolioNoteApp
import com.example.data.local.EmbeddingEntity
import com.example.data.local.JobStatus
import com.example.data.local.ProcessingJobEntity
import com.example.data.local.SourceStatus
import com.example.data.local.SourceType
import com.example.data.local.VectorCodec
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class SourceProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_JOB_ID = "job_id"

        fun enqueue(context: Context, sourceId: String, jobId: String): UUID {
            val data = Data.Builder()
                .putString(KEY_SOURCE_ID, sourceId)
                .putString(KEY_JOB_ID, jobId)
                .build()
            val request = OneTimeWorkRequestBuilder<SourceProcessingWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("source_processing")
                .addTag("source_$sourceId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "process_source_$sourceId",
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }
    }

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: return Result.failure()
        val jobId = inputData.getString(KEY_JOB_ID) ?: UUID.randomUUID().toString()

        val app = applicationContext as FolioNoteApp
        val container = app.container
        val db = container.database
        val sourceDao = db.sourceDao()
        val chunkDao = db.chunkAndEmbeddingDao()
        val jobDao = db.toolAndJobDao()

        val source = sourceDao.getSourceById(sourceId) ?: return Result.failure()

        return try {
            jobDao.updateJobProgress(jobId, JobStatus.RUNNING.name, 0.1f, "Extraindo texto...")
            sourceDao.updateProcessingState(sourceId, SourceStatus.EXTRACTING.name, 0.15f)

            val file = File(source.filePath)
            if (!file.exists()) {
                val msg = "Arquivo local não encontrado."
                sourceDao.updateProcessingState(sourceId, SourceStatus.FAILED.name, 0f, msg)
                jobDao.updateJobProgress(jobId, JobStatus.FAILED.name, 0f, msg)
                return Result.failure()
            }

            val sourceType = runCatching { SourceType.valueOf(source.sourceType) }.getOrDefault(SourceType.TXT)
            val extracted = container.documentExtractor.extract(file, sourceType)

            if (extracted.requiresOcr) {
                val ocrMsg = "PDF sem camada de texto selecionável (requer OCR)."
                sourceDao.upsertSource(
                    source.copy(
                        extractedText = "",
                        pageCount = extracted.pages.size.coerceAtLeast(1),
                        metadataJson = JSONObject(extracted.metadata).toString(),
                        processingStatus = SourceStatus.REQUIRES_OCR.name,
                        indexingProgress = 1f,
                        errorMessage = ocrMsg,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                jobDao.updateJobProgress(jobId, JobStatus.FAILED.name, 1f, ocrMsg)
                return Result.success()
            }

            sourceDao.upsertSource(
                source.copy(
                    extractedText = extracted.fullText,
                    pageCount = extracted.pages.size.coerceAtLeast(1),
                    metadataJson = JSONObject(extracted.metadata).toString(),
                    processingStatus = SourceStatus.CHUNKING.name,
                    indexingProgress = 0.45f,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            jobDao.updateJobProgress(jobId, JobStatus.RUNNING.name, 0.45f, "Segmentando em chunks...")

            val settings = container.preferencesStore.settingsFlow.first()
            val chunks = container.chunker.chunkPages(
                sourceId = source.id,
                notebookId = source.notebookId,
                pages = extracted.pages,
                maxChunkChars = settings.chunkSizeChars,
                overlapChars = settings.chunkOverlapChars
            )

            chunkDao.deleteEmbeddingsForSource(source.id)
            chunkDao.deleteChunksForSource(source.id)
            chunkDao.insertChunks(chunks)

            // Optional decoupled embeddings generation if user selected an embedding model
            val embeddingModelId = settings.activeEmbeddingModelId
            var embeddingNotice: String? = null
            if (!embeddingModelId.isNullOrBlank() && chunks.isNotEmpty()) {
                val embModel = db.providerAndModelDao().getModelByInternalId(embeddingModelId)
                val embProvider = embModel?.let { db.providerAndModelDao().getProviderById(it.providerId) }
                if (embModel != null && embProvider != null && embModel.enabled && embProvider.isEnabled) {
                    sourceDao.updateProcessingState(sourceId, SourceStatus.EMBEDDING.name, 0.72f)
                    jobDao.updateJobProgress(jobId, JobStatus.RUNNING.name, 0.72f, "Gerando embeddings...")

                    val batchSize = 16
                    val entities = mutableListOf<EmbeddingEntity>()
                    var embeddingFailed = false
                    for (batchStart in chunks.indices step batchSize) {
                        if (isStopped) break
                        val batch = chunks.subList(batchStart, (batchStart + batchSize).coerceAtMost(chunks.size))
                        val res = container.embeddingProvider.embedTexts(
                            provider = embProvider,
                            model = embModel,
                            texts = batch.map { it.content }
                        )
                        if (res.isSuccess) {
                            val result = res.getOrThrow()
                            batch.forEachIndexed { idx, chunk ->
                                val vec = result.vectors.getOrNull(idx)
                                if (vec != null && vec.isNotEmpty()) {
                                    entities.add(
                                        EmbeddingEntity(
                                            id = UUID.randomUUID().toString(),
                                            chunkId = chunk.id,
                                            notebookId = source.notebookId,
                                            sourceId = source.id,
                                            embeddingProviderId = embProvider.id,
                                            embeddingModelId = embModel.modelId,
                                            dimensions = result.dimensions,
                                            vectorBlob = VectorCodec.floatArrayToBytes(vec)
                                        )
                                    )
                                }
                            }
                        } else {
                            embeddingFailed = true
                            embeddingNotice = "Indexado lexicalmente (offline). Falha ao gerar embeddings: ${res.exceptionOrNull()?.message}"
                            break
                        }
                    }
                    if (!embeddingFailed && entities.isNotEmpty()) {
                        chunkDao.insertEmbeddings(entities)
                    }
                }
            }

            sourceDao.updateProcessingState(
                sourceId = sourceId,
                status = SourceStatus.READY.name,
                progress = 1f,
                errorMessage = embeddingNotice
            )
            jobDao.updateJobProgress(
                jobId = jobId,
                status = JobStatus.SUCCEEDED.name,
                progress = 1f,
                message = "Pronto • ${chunks.size} trecho(s)"
            )
            db.notebookDao().getNotebookById(source.notebookId)?.let { nb ->
                db.notebookDao().upsertNotebook(nb.copy(updatedAt = System.currentTimeMillis()))
            }

            Result.success()
        } catch (t: Throwable) {
            val err = t.localizedMessage ?: "Erro inesperado ao processar documento."
            sourceDao.updateProcessingState(sourceId, SourceStatus.FAILED.name, 0f, err)
            jobDao.updateJobProgress(jobId, JobStatus.FAILED.name, 0f, err)
            Result.failure()
        }
    }
}

class SourcePipelineManager(
    private val context: Context,
    private val db: com.example.data.local.FolioNoteDatabase
) {
    suspend fun scheduleProcessing(notebookId: String, sourceId: String, jobType: String = "IMPORT") {
        val jobId = UUID.randomUUID().toString()
        val workId = SourceProcessingWorker.enqueue(context, sourceId, jobId)
        db.toolAndJobDao().upsertJob(
            ProcessingJobEntity(
                id = jobId,
                notebookId = notebookId,
                sourceId = sourceId,
                jobType = jobType,
                status = JobStatus.QUEUED.name,
                progress = 0.05f,
                statusMessage = "Na fila de processamento...",
                workRequestId = workId.toString()
            )
        )
    }

    suspend fun recoverInterruptedJobs() {
        val interrupted = db.toolAndJobDao().getActiveOrInterruptedJobs()
        for (job in interrupted) {
            val src = db.sourceDao().getSourceById(job.sourceId)
            if (src != null && src.processingStatus != SourceStatus.READY.name) {
                SourceProcessingWorker.enqueue(context, job.sourceId, job.id)
            } else {
                db.toolAndJobDao().updateJobProgress(job.id, JobStatus.CANCELLED.name, 0f, "Cancelado")
            }
        }
    }

    fun cancelProcessing(sourceId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("process_source_$sourceId")
    }
}
