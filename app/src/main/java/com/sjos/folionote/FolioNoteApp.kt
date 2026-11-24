package com.sjos.folionote

import android.app.Application
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.repository.ChatOrchestrator
import com.sjos.folionote.data.repository.NotebookRepository
import com.sjos.folionote.data.repository.ProviderRepository
import com.sjos.folionote.engine.ai.OpenAiCompatibleClient
import com.sjos.folionote.engine.context.ContextBudgetPlanner
import com.sjos.folionote.engine.document.DocumentExtractor
import com.sjos.folionote.engine.document.FileStorageManager
import com.sjos.folionote.engine.indexing.CompositeEmbeddingProvider
import com.sjos.folionote.engine.indexing.EmbeddingProvider
import com.sjos.folionote.engine.indexing.GeminiEmbeddingProvider
import com.sjos.folionote.engine.indexing.OpenAiCompatibleEmbeddingProvider
import com.sjos.folionote.engine.indexing.StructuralChunker
import com.sjos.folionote.engine.retrieval.HybridRetriever
import com.sjos.folionote.engine.tools.ToolEngine
import com.sjos.folionote.engine.worker.SourcePipelineManager
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
            redactHeader("x-goog-api-key")
            redactHeader("proxy-authorization")
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    val fileStorageManager = FileStorageManager(app)
    val documentExtractor = DocumentExtractor()
    val chunker = StructuralChunker()

    private val openAiEmbeddingProvider = OpenAiCompatibleEmbeddingProvider(cryptoManager, okHttpClient)
    private val geminiEmbeddingProvider = GeminiEmbeddingProvider(cryptoManager, okHttpClient)
    val embeddingProvider: EmbeddingProvider = CompositeEmbeddingProvider(
        openAiProvider = openAiEmbeddingProvider,
        geminiProvider = geminiEmbeddingProvider
    )

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
