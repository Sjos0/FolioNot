package com.example

import android.app.Application
import com.example.core.preferences.AppPreferencesStore
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.FolioNoteDatabase
import com.example.data.repository.ChatOrchestrator
import com.example.data.repository.NotebookRepository
import com.example.data.repository.ProviderRepository
import com.example.engine.ai.OpenAiCompatibleClient
import com.example.engine.context.ContextBudgetPlanner
import com.example.engine.document.DocumentExtractor
import com.example.engine.document.FileStorageManager
import com.example.engine.indexing.OpenAiCompatibleEmbeddingProvider
import com.example.engine.indexing.StructuralChunker
import com.example.engine.retrieval.HybridRetriever
import com.example.engine.tools.ToolEngine
import com.example.engine.worker.SourcePipelineManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

class AppContainer(app: Application) {
    val database: FolioNoteDatabase = FolioNoteDatabase.getInstance(app)
    val preferencesStore: AppPreferencesStore = AppPreferencesStore(app)
    val cryptoManager: KeystoreCryptoManager = KeystoreCryptoManager()

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
            redactHeader("x-api-key")
            redactHeader("api-key")
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    val fileStorageManager = FileStorageManager(app)
    val documentExtractor = DocumentExtractor()
    val chunker = StructuralChunker()
    val embeddingProvider = OpenAiCompatibleEmbeddingProvider(cryptoManager, okHttpClient)
    val aiClient = OpenAiCompatibleClient(cryptoManager, okHttpClient)
    val retriever = HybridRetriever()
    val budgetPlanner = ContextBudgetPlanner()
    val pipelineManager = SourcePipelineManager(app, database)

    val toolEngine = ToolEngine(
        sourceDao = database.sourceDao(),
        chunkDao = database.chunkAndEmbeddingDao(),
        conversationDao = database.conversationAndMessageDao(),
        notebookDao = database.notebookDao(),
        retriever = retriever
    )

    val notebookRepository = NotebookRepository(
        db = database,
        fileStorageManager = fileStorageManager,
        pipelineManager = pipelineManager,
        retriever = retriever
    )

    val providerRepository = ProviderRepository(
        db = database,
        aiClient = aiClient,
        cryptoManager = cryptoManager
    )

    val chatOrchestrator = ChatOrchestrator(
        db = database,
        aiClient = aiClient,
        embeddingProvider = embeddingProvider,
        retriever = retriever,
        budgetPlanner = budgetPlanner,
        toolEngine = toolEngine,
        preferencesStore = preferencesStore
    )
}

class FolioNoteApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        runCatching { PDFBoxResourceLoader.init(applicationContext) }
        container = AppContainer(this)
        appScope.launch {
            container.database.toolAndJobDao().upsertTools(ToolEngine.builtInTools())
            container.pipelineManager.recoverInterruptedJobs()
        }
    }
}
