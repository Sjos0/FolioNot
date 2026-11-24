package com.example.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.core.preferences.AppSettings
import com.example.data.local.CollectionEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.NotebookEntity
import com.example.data.local.SourceEntity
import com.example.data.repository.GlobalSearchResults
import com.example.engine.document.StorageStats
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HomeFilterMode(val label: String) {
    MY_NOTEBOOKS("Meus notebooks"),
    COLLECTIONS("Coleções"),
    ALL("Todos")
}

enum class NotebookSortMode(val label: String) {
    RECENT("Mais recentes"),
    TITLE("Alfabética")
}

data class HomeUiState(
    val filterMode: HomeFilterMode = HomeFilterMode.MY_NOTEBOOKS,
    val sortMode: NotebookSortMode = NotebookSortMode.RECENT,
    val selectedCollectionId: String? = null,
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: GlobalSearchResults = GlobalSearchResults(),
    val notebooks: List<NotebookEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val sourceCountsByNotebook: Map<String, Int> = emptyMap(),
    val notebookCountByCollection: Map<String, Int> = emptyMap(),
    val recentConversations: List<ConversationEntity> = emptyList(),
    val providerCount: Int = 0,
    val modelCount: Int = 0,
    val storageStats: StorageStats = StorageStats(0, 0, 0),
    val settings: AppSettings = AppSettings()
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val notebookRepo = container.notebookRepository
    private val providerRepo = container.providerRepository
    private val prefsStore = container.preferencesStore

    private val filterMode = MutableStateFlow(HomeFilterMode.MY_NOTEBOOKS)
    private val sortMode = MutableStateFlow(NotebookSortMode.RECENT)
    private val selectedCollectionId = MutableStateFlow<String?>(null)
    private val isSearchActive = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")
    private val searchResults = MutableStateFlow(GlobalSearchResults())
    private var searchJob: Job? = null

    private data class CoreCollectionsData(
        val notebooks: List<NotebookEntity>,
        val collections: List<CollectionEntity>,
        val sources: List<SourceEntity>,
        val conversations: List<ConversationEntity>,
        val storageBytes: Long
    )

    private val coreDataFlow = combine(
        notebookRepo.allNotebooks,
        notebookRepo.allCollections,
        notebookRepo.allSources,
        notebookRepo.recentConversations,
        notebookRepo.totalStorageBytes
    ) { notebooks, collections, sources, convs, storageBytes ->
        CoreCollectionsData(notebooks, collections, sources, convs, storageBytes)
    }

    private val controlsFlow = combine(
        filterMode,
        sortMode,
        selectedCollectionId,
        isSearchActive,
        searchQuery
    ) { fMode, sMode, colId, searchOpen, query ->
        ControlsSnapshot(fMode, sMode, colId, searchOpen, query)
    }

    private data class ControlsSnapshot(
        val filterMode: HomeFilterMode,
        val sortMode: NotebookSortMode,
        val selectedCollectionId: String?,
        val isSearchActive: Boolean,
        val searchQuery: String
    )

    val uiState: StateFlow<HomeUiState> = combine(
        coreDataFlow,
        controlsFlow,
        searchResults,
        providerRepo.allProviders,
        providerRepo.enabledChatModels,
        prefsStore.settingsFlow
    ) { arr ->
        val core = arr[0] as CoreCollectionsData
        val ctrl = arr[1] as ControlsSnapshot
        val sRes = arr[2] as GlobalSearchResults
        @Suppress("UNCHECKED_CAST")
        val providers = arr[3] as List<com.example.data.local.ProviderEntity>
        @Suppress("UNCHECKED_CAST")
        val models = arr[4] as List<com.example.data.local.ModelEntity>
        val settings = arr[5] as AppSettings

        val filtered = core.notebooks
            .let { list ->
                if (ctrl.selectedCollectionId != null) {
                    list.filter { it.collectionId == ctrl.selectedCollectionId }
                } else {
                    list
                }
            }
            .let { list ->
                when (ctrl.sortMode) {
                    NotebookSortMode.RECENT -> list.sortedWith(
                        compareByDescending<NotebookEntity> { it.isPinned }.thenByDescending { it.updatedAt }
                    )
                    NotebookSortMode.TITLE -> list.sortedWith(
                        compareByDescending<NotebookEntity> { it.isPinned }.thenBy { it.title.lowercase() }
                    )
                }
            }

        val sourceCounts = core.sources.groupingBy { it.notebookId }.eachCount()
        val collectionCounts = core.notebooks.mapNotNull { it.collectionId }.groupingBy { it }.eachCount()

        HomeUiState(
            filterMode = ctrl.filterMode,
            sortMode = ctrl.sortMode,
            selectedCollectionId = ctrl.selectedCollectionId,
            isSearchActive = ctrl.isSearchActive,
            searchQuery = ctrl.searchQuery,
            searchResults = sRes,
            notebooks = filtered,
            collections = core.collections,
            sourceCountsByNotebook = sourceCounts,
            notebookCountByCollection = collectionCounts,
            recentConversations = core.conversations,
            providerCount = providers.size,
            modelCount = models.size,
            storageStats = notebookRepo.getStorageStats(core.storageBytes),
            settings = settings
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun setFilterMode(mode: HomeFilterMode) {
        filterMode.value = mode
        if (mode != HomeFilterMode.MY_NOTEBOOKS) {
            selectedCollectionId.value = null
        }
    }

    fun openCollection(collectionId: String) {
        selectedCollectionId.value = collectionId
        filterMode.value = HomeFilterMode.MY_NOTEBOOKS
    }

    fun clearCollectionFilter() {
        selectedCollectionId.value = null
    }

    fun toggleSortMode() {
        sortMode.value = if (sortMode.value == NotebookSortMode.RECENT) {
            NotebookSortMode.TITLE
        } else {
            NotebookSortMode.RECENT
        }
    }

    fun setSearchActive(active: Boolean) {
        isSearchActive.value = active
        if (!active) {
            searchQuery.value = ""
            searchResults.value = GlobalSearchResults()
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180)
            searchResults.value = notebookRepo.searchGlobal(query)
        }
    }

    fun createNotebook(
        title: String,
        description: String,
        iconKey: String,
        collectionId: String?,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            val nb = notebookRepo.createNotebook(title, description, iconKey, collectionId ?: selectedCollectionId.value)
            onCreated(nb.id)
        }
    }

    fun updateNotebook(notebook: NotebookEntity) {
        viewModelScope.launch { notebookRepo.updateNotebook(notebook) }
    }

    fun togglePin(notebook: NotebookEntity) {
        viewModelScope.launch { notebookRepo.togglePin(notebook.id, notebook.isPinned) }
    }

    fun assignNotebookToCollection(notebookId: String, collectionId: String?) {
        viewModelScope.launch { notebookRepo.assignToCollection(notebookId, collectionId) }
    }

    fun deleteNotebook(notebookId: String) {
        viewModelScope.launch { notebookRepo.deleteNotebook(notebookId) }
    }

    fun createCollection(name: String, description: String = "") {
        viewModelScope.launch { notebookRepo.createCollection(name, description) }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            if (selectedCollectionId.value == collectionId) selectedCollectionId.value = null
            notebookRepo.deleteCollection(collectionId)
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch { prefsStore.setThemePreference(theme) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(container) as T
    }
}
