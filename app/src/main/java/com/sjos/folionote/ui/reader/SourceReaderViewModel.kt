package com.sjos.folionote.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjos.folionote.AppContainer
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.SourceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SourceReaderUiState(
    val source: SourceEntity? = null,
    val chunks: List<DocumentChunkEntity> = emptyList(),
    val selectedPage: Int = 1,
    val highlightedChunkId: String? = null,
    val searchQuery: String = ""
)

class SourceReaderViewModel(
    sourceId: String,
    initialPage: Int,
    initialChunkId: String?,
    container: AppContainer
) : ViewModel() {

    private val repo = container.notebookRepository
    private val selectedPage = MutableStateFlow(initialPage.coerceAtLeast(1))
    private val highlightedChunkId = MutableStateFlow(initialChunkId)
    private val searchQuery = MutableStateFlow("")

    val uiState: StateFlow<SourceReaderUiState> = combine(
        repo.observeSource(sourceId),
        repo.observeChunksForSource(sourceId),
        selectedPage,
        highlightedChunkId,
        searchQuery
    ) { src, chunks, page, hlId, query ->
        SourceReaderUiState(
            source = src,
            chunks = chunks,
            selectedPage = page,
            highlightedChunkId = hlId,
            searchQuery = query
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourceReaderUiState())

    fun selectPage(page: Int) {
        selectedPage.value = page.coerceAtLeast(1)
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    class Factory(
        private val sourceId: String,
        private val initialPage: Int,
        private val initialChunkId: String?,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SourceReaderViewModel(sourceId, initialPage, initialChunkId, container) as T
    }
}
