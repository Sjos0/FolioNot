package com.sjos.folionote.ui.notebook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.SourceStatus
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.theme.AccentAmber
import com.sjos.folionote.ui.theme.AccentBlue
import com.sjos.folionote.ui.theme.AccentCoral
import com.sjos.folionote.ui.theme.AccentCyan
import com.sjos.folionote.ui.theme.AccentEmerald
import com.sjos.folionote.ui.theme.AccentPink
import com.sjos.folionote.ui.theme.AccentPurple
import com.sjos.folionote.ui.theme.AccentTeal

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesTabContent(
    state: NotebookUiState,
    onAddFilesClick: () -> Unit,
    onAddGoogleDriveClick: () -> Unit = {},
    onCreateNote: (String, String) -> Unit,
    onImportWebUrl: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onDismissBanner: () -> Unit,
    onToggleSelectAll: (Boolean) -> Unit,
    onToggleSourceSelected: (SourceEntity) -> Unit,
    onOpenReader: (SourceEntity) -> Unit,
    onReprocessSource: (SourceEntity) -> Unit,
    onReindexAll: () -> Unit,
    onDeleteSource: (SourceEntity) -> Unit
) {
    var showCreateNoteDialog by remember { mutableStateOf(false) }
    var showWebUrlDialog by remember { mutableStateOf(false) }

    val allSelected = state.sources.isNotEmpty() && state.sources.all { it.isSelectedForChat }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.hasIncompatibleEmbeddings && state.activeEmbeddingModel != null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Índice de embeddings incompatível",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            text = "Este notebook possui vetores gerados por ${state.incompatibleEmbeddingLabels.joinToString(", ")}, diferente do modelo ativo (${state.activeEmbeddingModel.modelId} • ${state.activeEmbeddingModel.embeddingDimensions ?: "?"}d). Reindexe para migrar sem misturar espaços vetoriais.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = onReindexAll,
                            shape = CircleShape,
                            modifier = Modifier.testTag("migrate_embeddings_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Migrar e reindexar fontes")
                        }
                    }
                }
            }
        }

        if (state.settings.showSourcesBanner) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.StickyNote2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Processamento local e provedores desacoplados",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "PDFs, DOCX, PPTX, EPUB, páginas Web, Markdown e TXT são extraídos localmente. OCR, transcrição de mídia e Gemini Embedding 2 são opcionais via chaves externas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                            )
                        }
                        IconButton(onClick = onDismissBanner, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Fechar aviso", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAddFilesClick,
                    shape = CircleShape,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("add_sources_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Arquivos", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = { showCreateNoteDialog = true },
                    shape = CircleShape,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("create_note_source_button")
                ) {
                    Icon(Icons.Outlined.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Criar nota", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = { showWebUrlDialog = true },
                    enabled = !state.isClippingWebUrl,
                    shape = CircleShape,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("add_web_url_button")
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Adicionar URL", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onAddGoogleDriveClick,
                    shape = CircleShape,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("add_google_drive_source_button")
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Google Drive", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (state.isClippingWebUrl) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.sources.isNotEmpty() || state.sourceSearchQuery.isNotBlank()) {
            item {
                OutlinedTextField(
                    value = state.sourceSearchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Filtrar fontes no notebook...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.sourceSearchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Limpar")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSelectAll(!allSelected) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Selecionar tudo (${state.sources.count { it.isSelectedForChat }}/${state.sources.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = onToggleSelectAll,
                        modifier = Modifier.testTag("select_all_sources_checkbox")
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        if (state.sources.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Nenhuma fonte importada", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Importe PDFs, DOCX, PPTX, EPUB, imagens, áudio/vídeo, páginas web, Markdown ou crie uma nota local.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.sources, key = { it.id }) { source ->
                val chunkCount = remember(state.chunks, source.id) {
                    state.chunks.count { it.sourceId == source.id }
                }
                SourceRowItem(
                    source = source,
                    chunkCount = chunkCount,
                    onClick = { onOpenReader(source) },
                    onToggleSelect = { onToggleSourceSelected(source) },
                    onReprocess = { onReprocessSource(source) },
                    onDelete = { onDeleteSource(source) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showCreateNoteDialog) {
        CreateNoteSourceDialog(
            onDismiss = { showCreateNoteDialog = false },
            onSave = { title, content ->
                onCreateNote(title, content)
                showCreateNoteDialog = false
            }
        )
    }

    if (showWebUrlDialog) {
        WebClipUrlDialog(
            onDismiss = { showWebUrlDialog = false },
            onClip = { url ->
                showWebUrlDialog = false
                onImportWebUrl(url)
            }
        )
    }
}

@Composable
private fun SourceRowItem(
    source: SourceEntity,
    chunkCount: Int,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onReprocess: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val status = remember(source.processingStatus) {
        runCatching { SourceStatus.valueOf(source.processingStatus) }.getOrDefault(SourceStatus.PENDING)
    }
    val type = remember(source.sourceType) {
        runCatching { SourceType.valueOf(source.sourceType) }.getOrDefault(SourceType.TXT)
    }

    val (icon, tint) = when (type) {
        SourceType.PDF -> Icons.Outlined.PictureAsPdf to AccentCoral
        SourceType.DOCX -> Icons.Outlined.Description to AccentBlue
        SourceType.PPTX -> Icons.Outlined.Slideshow to AccentAmber
        SourceType.EPUB -> Icons.Outlined.MenuBook to AccentPurple
        SourceType.IMAGE -> Icons.Outlined.Image to AccentPink
        SourceType.AUDIO -> Icons.Outlined.AudioFile to AccentTeal
        SourceType.VIDEO -> Icons.Outlined.VideoFile to AccentCyan
        SourceType.WEB_PAGE -> Icons.Outlined.Language to AccentBlue
        SourceType.MARKDOWN -> Icons.Outlined.Description to AccentTeal
        SourceType.NOTE -> Icons.Outlined.StickyNote2 to AccentAmber
        SourceType.TXT -> Icons.Outlined.Description to AccentEmerald
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("source_row_${source.id}")
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = type.name, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val unitLabel = when (type) {
                        SourceType.PPTX -> "slides"
                        SourceType.EPUB -> "cap."
                        else -> "pág."
                    }
                    val statusLabel = when (status) {
                        SourceStatus.PENDING -> "Na fila..."
                        SourceStatus.EXTRACTING -> "Extraindo conteúdo..."
                        SourceStatus.CHUNKING -> "Segmentando..."
                        SourceStatus.EMBEDDING -> "Gerando embeddings..."
                        SourceStatus.READY -> "${source.pageCount} $unitLabel • $chunkCount trechos • ${FormatUtils.formatBytes(source.sizeBytes)}"
                        SourceStatus.PARTIAL -> "Parcial (${source.pageCount} $unitLabel • $chunkCount trechos) — ${source.errorMessage ?: "algumas páginas falharam"}"
                        SourceStatus.REQUIRES_OCR -> source.errorMessage ?: "Requer OCR externo configurado nas Configurações"
                        SourceStatus.REQUIRES_TRANSCRIPTION -> source.errorMessage ?: "Requer endpoint de transcrição nas Configurações"
                        SourceStatus.FAILED -> source.errorMessage ?: "Falha ao processar"
                    }
                    val isWarning = status == SourceStatus.FAILED ||
                        status == SourceStatus.REQUIRES_OCR ||
                        status == SourceStatus.REQUIRES_TRANSCRIPTION ||
                        status == SourceStatus.PARTIAL

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isWarning) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = if (status == SourceStatus.PARTIAL) AccentAmber else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                status == SourceStatus.PARTIAL -> AccentAmber
                                isWarning -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Opções da fonte")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Ler offline") },
                            leadingIcon = { Icon(Icons.Rounded.MenuBook, null) },
                            onClick = {
                                menuOpen = false
                                onClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reprocessar") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            onClick = {
                                menuOpen = false
                                onReprocess()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }

                Checkbox(
                    checked = source.isSelectedForChat,
                    onCheckedChange = { onToggleSelect() }
                )
            }

            AnimatedVisibility(
                visible = status == SourceStatus.PENDING ||
                    status == SourceStatus.EXTRACTING ||
                    status == SourceStatus.CHUNKING ||
                    status == SourceStatus.EMBEDDING
            ) {
                LinearProgressIndicator(
                    progress = { source.indexingProgress.coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, end = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun CreateNoteSourceDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova nota como fonte") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título da nota") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Conteúdo (texto ou Markdown)") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.ifBlank { "Nota local" }, content) },
                enabled = content.isNotBlank()
            ) {
                Text("Salvar fonte")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun WebClipUrlDialog(
    onDismiss: () -> Unit,
    onClip: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capturar página da Web") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Cole a URL do artigo ou página pública. O conteúdo principal será extraído em Markdown e salvo offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (http:// ou https://)") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("web_clip_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onClip(url.trim()) },
                enabled = url.trim().startsWith("http://", ignoreCase = true) ||
                    url.trim().startsWith("https://", ignoreCase = true),
                modifier = Modifier.testTag("web_clip_confirm_button")
            ) {
                Text("Capturar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
