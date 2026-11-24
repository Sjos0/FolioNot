package com.sjos.folionote.ui.reader

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sjos.folionote.data.local.SourceStatus
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.theme.JetBrainsMonoFontFamily
import org.json.JSONObject
import java.io.File

@Composable
fun SourceReaderScreen(
    viewModel: SourceReaderViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showMetadata by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val sourceType = remember(state.source?.sourceType) {
        state.source?.sourceType?.let { runCatching { SourceType.valueOf(it) }.getOrNull() }
    }
    val sourceStatus = remember(state.source?.processingStatus) {
        state.source?.processingStatus?.let { runCatching { SourceStatus.valueOf(it) }.getOrNull() }
    }
    val metaJson = remember(state.source?.metadataJson) {
        runCatching { JSONObject(state.source?.metadataJson ?: "{}") }.getOrNull() ?: JSONObject()
    }

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

    val unitPrefix = when (sourceType) {
        SourceType.PPTX -> "Slide"
        SourceType.EPUB -> "Cap."
        else -> "Pág."
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
                                label = { Text("$unitPrefix $page") },
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
                    val src = state.source

                    // Status notice for partial / OCR / transcription states
                    if (src != null && (sourceStatus == SourceStatus.PARTIAL ||
                            sourceStatus == SourceStatus.REQUIRES_OCR ||
                            sourceStatus == SourceStatus.REQUIRES_TRANSCRIPTION) &&
                        !src.errorMessage.isNullOrBlank()
                    ) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = src.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }

                    // Image Preview for IMAGE sources
                    if (src != null && sourceType == SourceType.IMAGE && src.filePath.isNotBlank()) {
                        val imgFile = File(src.filePath)
                        if (imgFile.exists()) {
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AsyncImage(
                                        model = imgFile,
                                        contentDescription = "Pré-visualização de ${src.name}",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 360.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                    )
                                }
                            }
                        }
                    }

                    // Web Clipper metadata header for WEB_PAGE sources
                    if (src != null && sourceType == SourceType.WEB_PAGE) {
                        val canonicalUrl = metaJson.optString("canonical_url")
                            .ifBlank { metaJson.optString("source_url") }
                        val domain = metaJson.optString("domain")
                        val capturedAt = metaJson.optString("captured_at").toLongOrNull() ?: src.importedAt
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = domain.ifBlank { "Página Web capturada" },
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                    if (canonicalUrl.isNotBlank()) {
                                        Text(
                                            text = canonicalUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "Capturado em: ${FormatUtils.formatDate(capturedAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (canonicalUrl.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(canonicalUrl)))
                                                }
                                            },
                                            shape = CircleShape
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Abrir URL externa")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Audio / Video metadata header
                    if (src != null && (sourceType == SourceType.AUDIO || sourceType == SourceType.VIDEO)) {
                        val durationFormatted = metaJson.optString("duration_formatted")
                        val resolution = metaJson.optString("resolution")
                        val detectedLanguage = metaJson.optString("detected_language")
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = if (sourceType == SourceType.VIDEO) "Metadados do Vídeo" else "Metadados do Áudio",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (durationFormatted.isNotBlank()) {
                                        Text("Duração: $durationFormatted", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (resolution.isNotBlank()) {
                                        Text("Resolução: $resolution", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (detectedLanguage.isNotBlank()) {
                                        Text("Idioma transcrito: $detectedLanguage", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    // Google Drive origin metadata card
                    if (src != null && metaJson.optString("origin") == "google_drive") {
                        val driveFileId = metaJson.optString("drive_file_id")
                        val driveMimeType = metaJson.optString("drive_mime_type")
                        val driveWebLink = metaJson.optString("drive_web_view_link")
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.CloudQueue, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Origem: Google Drive",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                    if (driveMimeType.isNotBlank()) {
                                        Text(
                                            text = "Tipo original: $driveMimeType",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (driveFileId.isNotBlank()) {
                                        Text(
                                            text = "ID remoto: $driveFileId",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (driveWebLink.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(driveWebLink)))
                                                }
                                            },
                                            shape = CircleShape
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Abrir no Google Drive")
                                        }
                                    }
                                }
                            }
                        }
                    }

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
                                                append("$unitPrefix ${chunk.pageNumber} • Trecho #${chunk.chunkIndex + 1}")
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
                    Text("Unidades/Páginas: ${src.pageCount}")
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
