package com.sjos.folionote.ui.notebook

import com.sjos.folionote.ui.components.DestructiveConfirmDialog
import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sjos.folionote.data.local.StudioArtifactEntity
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.components.MarkdownLiteText
import com.sjos.folionote.ui.theme.AccentAmber
import com.sjos.folionote.ui.theme.AccentBlue
import com.sjos.folionote.ui.theme.AccentCyan
import com.sjos.folionote.ui.theme.AccentEmerald
import com.sjos.folionote.ui.theme.AccentPink
import com.sjos.folionote.ui.theme.AccentPurple
import com.sjos.folionote.ui.theme.AccentTeal
import com.sjos.folionote.ui.theme.FolioIcons

private data class StudioToolTile(
    val type: String,
    val title: String,
    val icon: ImageVector,
    val tint: Color,
    val prompt: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioTabContent(
    state: NotebookUiState,
    onGenerateArtifact: (type: String, title: String, prompt: String) -> Unit,
    onDeleteArtifact: (String) -> Unit,
    onRunLexicalSearch: (String) -> Unit,
    onReindexAll: () -> Unit,
    onOpenReaderAtChunk: (sourceId: String, page: Int, chunkId: String) -> Unit
) {
    // Exclusão de material exige confirmação: o item fica pendente até o usuário confirmar.
    var pendingDeleteArtifact by remember { mutableStateOf<StudioArtifactEntity?>(null) }
    var showLexicalSearchSheet by remember { mutableStateOf(false) }
    var showIndexInspector by remember { mutableStateOf(false) }
    var readingArtifact by remember { mutableStateOf<StudioArtifactEntity?>(null) }
    val clipboard = LocalClipboardManager.current

    val tiles = remember {
        listOf(
            StudioToolTile(
                type = "SUMMARY",
                title = "Resumo Estruturado",
                icon = FolioIcons.Outlined.AutoAwesome,
                tint = AccentBlue,
                prompt = "Crie um resumo executivo estruturado por tópicos cobrindo todas as ideias centrais e evidências das fontes."
            ),
            StudioToolTile(
                type = "STUDY_GUIDE",
                title = "Guia de Estudo",
                icon = FolioIcons.Outlined.Article,
                tint = AccentPink,
                prompt = "Elabore um guia de estudo completo com conceitos-chave, glossário e questões dissertativas de revisão."
            ),
            StudioToolTile(
                type = "TOPIC_MAP",
                title = "Mapa de Tópicos",
                icon = FolioIcons.Outlined.AccountTree,
                tint = AccentPurple,
                prompt = "Organize os assuntos das fontes em uma árvore hierárquica de tópicos, subtópicos e relações entre conceitos."
            ),
            StudioToolTile(
                type = "FLASHCARDS",
                title = "Cartões Didáticos",
                icon = FolioIcons.Outlined.Style,
                tint = AccentAmber,
                prompt = "Crie 8 cartões didáticos (Frente: Pergunta / Verso: Resposta concisa) baseados exclusivamente nas fontes."
            ),
            StudioToolTile(
                type = "QUIZ",
                title = "Teste / Quiz",
                icon = FolioIcons.Outlined.Quiz,
                tint = AccentTeal,
                prompt = "Elabore um teste com 5 questões de múltipla escolha baseadas nas fontes, com gabarito comentado ao final."
            ),
            StudioToolTile(
                type = "LEXICAL_SEARCH",
                title = "Busca Lexical",
                icon = FolioIcons.Outlined.FindInPage,
                tint = AccentEmerald
            ),
            StudioToolTile(
                type = "INDEX_INSPECTOR",
                title = "Inspetor de Índice",
                icon = FolioIcons.Outlined.Storage,
                tint = AccentCyan
            ),
            StudioToolTile(
                type = "REINDEX",
                title = "Reindexar Fontes",
                icon = FolioIcons.Outlined.Refresh,
                tint = AccentAmber
            )
        )
    }

    pendingDeleteArtifact?.let { artifact ->
        DestructiveConfirmDialog(
            title = "Excluir \"${artifact.title}\"?",
            message = "Este material será removido do caderno. Esta ação não pode ser desfeita; as fontes originais não são afetadas.",
            confirmLabel = "Excluir",
            confirmTag = "confirm_delete_artifact",
            onConfirm = {
                onDeleteArtifact(artifact.id)
                pendingDeleteArtifact = null
            },
            onDismiss = { pendingDeleteArtifact = null }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.isGeneratingArtifact) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        items(tiles.chunked(2)) { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { tile ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !state.isGeneratingArtifact) {
                                when (tile.type) {
                                    "LEXICAL_SEARCH" -> showLexicalSearchSheet = true
                                    "INDEX_INSPECTOR" -> showIndexInspector = true
                                    "REINDEX" -> onReindexAll()
                                    else -> onGenerateArtifact(tile.type, tile.title, tile.prompt)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 64.dp)
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(tile.icon, contentDescription = null, tint = tile.tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = tile.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Notas e Materiais Salvos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (state.studioArtifacts.isEmpty()) {
            item {
                Text(
                    text = "Gere um material acima ou fixe uma resposta do Chat para salvá-la offline aqui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(state.studioArtifacts, key = { it.id }) { artifact ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { readingArtifact = artifact }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(FolioIcons.Outlined.Article, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(artifact.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "${artifact.sourceCount} fonte(s) • ${FormatUtils.formatDate(artifact.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { pendingDeleteArtifact = artifact }) {
                            Icon(FolioIcons.Outlined.DeleteOutline, contentDescription = "Excluir nota")
                        }
                    }
                }
            }
        }
    }

    if (showLexicalSearchSheet) {
        ModalBottomSheet(onDismissRequest = { showLexicalSearchSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Busca Lexical Offline (BM25)", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Funciona 100% sem internet sobre todos os trechos indexados neste notebook.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = state.lexicalSearchQuery,
                    onValueChange = onRunLexicalSearch,
                    label = { Text("Buscar palavras-chave ou termos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.lexicalSearchResults, key = { it.chunk.id }) { hit ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showLexicalSearchSheet = false
                                    onOpenReaderAtChunk(hit.chunk.sourceId, hit.chunk.pageNumber, hit.chunk.id)
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "${hit.sourceName} • p. ${hit.chunk.pageNumber} (score ${"%.2f".format(hit.lexicalScore)})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(hit.chunk.content.take(200), style = MaterialTheme.typography.bodySmall, maxLines = 4)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showIndexInspector) {
        val totalTokens = state.chunkStats.totalTokens.toInt()
        val embModelNames = remember(state.embeddingGroups) {
            state.embeddingGroups.map { "${it.embeddingModelId} (${it.dimensions}d)" }.distinct()
        }
        AlertDialog(
            onDismissRequest = { showIndexInspector = false },
            title = { Text("Inspetor de Chunks & Índice") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fontes indexadas: ${state.sources.size}")
                    Text("Chunks armazenados: ${state.chunkStats.chunkCount}")
                    Text("Tokens estimados no índice: $totalTokens")
                    Text("Vetores de embeddings: ${state.embeddingGroups.sumOf { it.vectorCount }}")
                    Text(
                        text = if (embModelNames.isEmpty()) {
                            "Modo vetorial: Apenas lexical offline"
                        } else {
                            "Coleção vetorial armazenada: ${embModelNames.joinToString(", ")}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (state.hasIncompatibleEmbeddings) {
                        Text(
                            text = "Atenção: coleção vetorial incompatível com o modelo de embedding ativo (${state.activeEmbeddingModel?.modelId ?: "nenhum"}). Reindexe para migrar com segurança.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = "Configuração atual: ${state.settings.chunkSizeChars} chars / sobreposição ${state.settings.chunkOverlapChars} / Top-K ${state.settings.ragTopK}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showIndexInspector = false }) { Text("Fechar") }
            },
            dismissButton = {
                if (state.hasIncompatibleEmbeddings) {
                    OutlinedButton(
                        onClick = {
                            showIndexInspector = false
                            onReindexAll()
                        }
                    ) {
                        Text("Migrar agora")
                    }
                }
            }
        )
    }

    if (readingArtifact != null) {
        val art = readingArtifact!!
        ModalBottomSheet(onDismissRequest = { readingArtifact = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(art.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(art.content)) }) {
                        Icon(FolioIcons.Outlined.ContentCopy, contentDescription = "Copiar")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(420.dp)) {
                    item {
                        MarkdownLiteText(text = art.content)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
