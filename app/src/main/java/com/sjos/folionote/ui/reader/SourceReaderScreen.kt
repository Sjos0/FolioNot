package com.sjos.folionote.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.theme.JetBrainsMonoFontFamily

@Composable
fun SourceReaderScreen(
    viewModel: SourceReaderViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showMetadata by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val availablePages = remember(state.chunks, state.source) {
        val pages = state.chunks.map { it.pageNumber }.distinct().sorted()
        if (pages.isNotEmpty()) pages else (1..(state.source?.pageCount ?: 1)).toList()
    }

    val visibleChunks = remember(state.chunks, state.selectedPage, state.searchQuery) {
        if (state.searchQuery.isNotBlank()) {
            state.chunks.filter {
                it.content.contains(state.searchQuery, ignoreCase = true) ||
                    it.sectionTitle.contains(state.searchQuery, ignoreCase = true)
            }
        } else {
            state.chunks.filter { it.pageNumber == state.selectedPage }
        }
    }

    LaunchedEffect(visibleChunks, state.highlightedChunkId) {
        val hlId = state.highlightedChunkId
        if (hlId != null) {
            val idx = visibleChunks.indexOfFirst { it.id == hlId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.source?.name ?: "Leitor de Fonte",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Leitura offline • ${state.source?.sourceType ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Buscar no documento")
                    }
                    IconButton(onClick = { showMetadata = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "Metadados da fonte")
                    }
                }

                if (showSearch) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        placeholder = { Text("Buscar nesta fonte...") },
                        trailingIcon = {
                            if (state.searchQuery.isNotBlank()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Rounded.Close, "Limpar")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                if (state.searchQuery.isBlank() && availablePages.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availablePages) { page ->
                            FilterChip(
                                selected = state.selectedPage == page,
                                onClick = { viewModel.selectPage(page) },
                                label = { Text("Pág. $page") },
                                shape = CircleShape
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (visibleChunks.isEmpty()) {
                        val fallbackText = state.source?.extractedText.orEmpty()
                        item {
                            if (fallbackText.isNotBlank()) {
                                Text(fallbackText, style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text(
                                    text = state.source?.errorMessage ?: "Nenhum texto extraído disponível para esta página.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(visibleChunks, key = { it.id }) { chunk ->
                            val highlighted = chunk.id == state.highlightedChunkId
                            Surface(
                                color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(14.dp),
                                border = if (highlighted) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = buildString {
                                                append("Página ${chunk.pageNumber} • Trecho #${chunk.chunkIndex + 1}")
                                                if (chunk.sectionTitle.isNotBlank()) append(" • ${chunk.sectionTitle}")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = chunk.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMetadata && state.source != null) {
        val src = state.source!!
        AlertDialog(
            onDismissRequest = { showMetadata = false },
            title = { Text("Metadados da fonte") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nome: ${src.name}")
                    Text("Tipo: ${src.sourceType} (${src.mimeType})")
                    Text("Tamanho: ${FormatUtils.formatBytes(src.sizeBytes)}")
                    Text("Páginas: ${src.pageCount}")
                    Text("Chunks indexados: ${state.chunks.size}")
                    Text("Importado em: ${FormatUtils.formatDate(src.importedAt)}")
                    Text(
                        text = "SHA-256:\n${src.fileHashSha256}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showMetadata = false }) { Text("Fechar") }
            }
        )
    }
}
