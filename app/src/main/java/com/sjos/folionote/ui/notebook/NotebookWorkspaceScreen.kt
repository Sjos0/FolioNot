package com.sjos.folionote.ui.notebook

import androidx.compose.material3.minimumInteractiveComponentSize
import com.sjos.folionote.ui.components.DestructiveConfirmDialog

import androidx.compose.foundation.lazy.LazyRow
import com.sjos.folionote.ui.theme.NotebookIcons

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sjos.folionote.ui.components.FolioLogoMark
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.theme.FolioIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookWorkspaceScreen(
    viewModel: NotebookViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReader: (sourceId: String, page: Int, chunkId: String?) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showConfigSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showCustomizeDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showGoogleDriveSheet by remember { mutableStateOf(false) }

    val supportedMimeTypes = remember {
        arrayOf(
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/html",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/epub+zip",
            "image/*",
            "audio/*",
            "video/*",
            "*/*"
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris)
        }
    }

    val driveSafPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris, isFromGoogleDrive = true)
        }
    }

    val driveImportAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val activity = context.findWorkspaceActivity() ?: return@rememberLauncherForActivityResult
        viewModel.completeDriveImportAuth(activity, result.data)
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("workspace_topbar"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("notebook_back_button")) {
                        Icon(FolioIcons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                    FolioLogoMark(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.notebook?.title ?: "Notebook",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.startNewConversation() },
                        modifier = Modifier.testTag("new_conversation_button")
                    ) {
                        Icon(FolioIcons.Rounded.Add, contentDescription = "Nova conversa")
                    }
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.testTag("notebook_overflow_button")
                        ) {
                            Icon(FolioIcons.Rounded.MoreVert, contentDescription = "Mais opções")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Configurar as conversas") },
                                leadingIcon = { Icon(FolioIcons.Rounded.Tune, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showConfigSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Personalizar notebook") },
                                leadingIcon = { Icon(FolioIcons.Rounded.Edit, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showCustomizeDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Histórico de conversas") },
                                leadingIcon = { Icon(FolioIcons.Rounded.History, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showHistorySheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Orçamento de contexto") },
                                leadingIcon = { Icon(FolioIcons.Rounded.DataUsage, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showBudgetDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Configurações (Embeddings, OCR, Drive)") },
                                leadingIcon = { Icon(FolioIcons.Outlined.Settings, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    onOpenSettings()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Excluir histórico de conversa", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(FolioIcons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showOverflowMenu = false
                                    showClearHistoryDialog = true
                                }
                            )
                        }
                    }
                    IconButton(onClick = onOpenProviders) {
                        Icon(FolioIcons.Outlined.Apps, contentDescription = "Provedores e Modelos")
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        NotebookTab.entries.forEach { tab ->
                            val selected = state.selectedTab == tab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                    .minimumInteractiveComponentSize()
                                    .clickable { viewModel.selectTab(tab) }
                                    .padding(vertical = 9.dp)
                                    .testTag("notebook_tab_${tab.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (state.selectedTab) {
                        NotebookTab.SOURCES -> {
                            SourcesTabContent(
                                state = state,
                                onAddFilesClick = {
                                    filePickerLauncher.launch(supportedMimeTypes)
                                },
                                onAddGoogleDriveClick = {
                                    viewModel.clearDriveImportError()
                                    showGoogleDriveSheet = true
                                },
                                onCreateNote = viewModel::createNoteSource,
                                onImportWebUrl = viewModel::importWebUrl,
                                onSearchChange = viewModel::updateSourceSearch,
                                onDismissBanner = viewModel::dismissSourcesBanner,
                                onToggleSelectAll = viewModel::setAllSourcesSelected,
                                onToggleSourceSelected = viewModel::toggleSourceSelected,
                                onOpenReader = { src -> onOpenReader(src.id, 1, null) },
                                onReprocessSource = viewModel::reprocessSource,
                                onReindexAll = viewModel::reindexAllSources,
                                onDeleteSource = viewModel::deleteSource
                            )
                        }
                        NotebookTab.CHAT -> {
                            ChatTabContent(
                                state = state,
                                onSendMessage = viewModel::sendMessage,
                                onStopGeneration = viewModel::stopGeneration,
                                onRegenerate = viewModel::regenerateLastResponse,
                                onTogglePin = viewModel::togglePinMessage,
                                onSelectModel = viewModel::selectModel,
                                onOpenConfigSheet = { showConfigSheet = true },
                                onOpenSourcesTab = { viewModel.selectTab(NotebookTab.SOURCES) },
                                onOpenProviders = onOpenProviders,
                                onOpenSourceAtCitation = { srcId, page, chunkId ->
                                    onOpenReader(srcId, page, chunkId)
                                },
                                onResolveToolApproval = viewModel::resolveToolApproval,
                                onAcknowledgeExternalDisclosure = viewModel::acknowledgeExternalDisclosure,
                                onOpenContextBudget = { showBudgetDialog = true },
                                onDismissError = viewModel::clearChatError
                            )
                        }
                        NotebookTab.STUDIO -> {
                            StudioTabContent(
                                state = state,
                                onGenerateArtifact = viewModel::generateStudioArtifact,
                                onDeleteArtifact = viewModel::deleteStudioArtifact,
                                onRunLexicalSearch = viewModel::runOfflineLexicalSearch,
                                onReindexAll = viewModel::reindexAllSources,
                                onOpenReaderAtChunk = { srcId, page, chunkId ->
                                    onOpenReader(srcId, page, chunkId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConfigSheet && state.activeConversation != null) {
        ConversationConfigSheet(
            conversation = state.activeConversation!!,
            onDismiss = { showConfigSheet = false },
            onSave = viewModel::updateConversationConfig
        )
    }

    if (showHistorySheet) {
        ModalBottomSheet(onDismissRequest = { showHistorySheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Conversas do notebook", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        showHistorySheet = false
                        viewModel.startNewConversation()
                    }) {
                        Text("+ Nova")
                    }
                }
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(state.conversations, key = { it.id }) { conv ->
                        val active = conv.id == state.activeConversation?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.switchConversation(conv.id)
                                    showHistorySheet = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conv.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    FormatUtils.formatDate(conv.updatedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (active) Icon(FolioIcons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showBudgetDialog) {
        val r = state.chatRuntime.lastBudgetReport
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            title = { Text("Context Engine — Orçamento") },
            text = {
                if (r == null) {
                    Text("Envie uma mensagem no chat para visualizar o orçamento calculado em tempo real para o modelo ativo.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Janela total do modelo: ${r.contextWindow} tokens")
                        Text("Reserva de saída: ${r.reservedOutputTokens} tokens")
                        Text("Margem de segurança: ${r.safetyMarginTokens} tokens")
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Instruções de sistema: ${r.systemTokens} tok.")
                        Text("Resumo acumulado: ${r.summaryTokens} tok.")
                        Text("Histórico recente: ${r.historyTokens} tok. (${r.includedHistoryCount} msgs, ${r.droppedHistoryCount} resumidas/omitidas)")
                        Text("Trechos RAG: ${r.ragTokens} tok. (${r.includedRagCount} incluídos, ${r.droppedRagCount} descartados)")
                        Text("Ferramentas: ${r.toolsTokens} tok.")
                        Text("Pergunta atual: ${r.currentQueryTokens} tok.")
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            "Total usado: ${r.totalUsedInputTokens} / ${r.availableInputBudget} disponíveis",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showBudgetDialog = false }) { Text("Fechar") }
            }
        )
    }

    if (showClearHistoryDialog) {
        DestructiveConfirmDialog(
            title = "Excluir histórico desta conversa?",
            message = "Todas as mensagens da conversa ativa serão removidas. Fontes, notas do Studio e o caderno não são afetados. Esta ação não pode ser desfeita.",
            confirmLabel = "Excluir histórico",
            confirmTag = "confirm_clear_history",
            onConfirm = {
                viewModel.clearActiveConversationHistory()
                showClearHistoryDialog = false
            },
            onDismiss = { showClearHistoryDialog = false }
        )
    }

    if (showCustomizeDialog && state.notebook != null) {
        var title by remember(state.notebook) { mutableStateOf(state.notebook!!.title) }
        var desc by remember(state.notebook) { mutableStateOf(state.notebook!!.description) }
        var iconKey by remember(state.notebook) { mutableStateOf(state.notebook!!.iconKey) }
        AlertDialog(
            onDismissRequest = { showCustomizeDialog = false },
            title = { Text("Personalizar notebook") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descrição") }, maxLines = 3)
                    Text("Ícone", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(NotebookIcons.all, key = { it.key }) { spec ->
                            val selected = spec.key == iconKey
                            Surface(
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(44.dp).clickable { iconKey = spec.key }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(spec.icon, contentDescription = spec.label, tint = spec.tint)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateNotebookDetails(title, desc, iconKey)
                    showCustomizeDialog = false
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomizeDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showGoogleDriveSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.cancelDriveImport()
                showGoogleDriveSheet = false
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Importar do Google Drive", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Importe documentos do seu Drive para este notebook. Isso é independente dos backups privados (appDataFolder).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        showGoogleDriveSheet = false
                        driveSafPickerLauncher.launch(supportedMimeTypes)
                    },
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("drive_saf_picker_button")
                ) {
                    Icon(FolioIcons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Selecionar pelo Seletor do Android")
                }

                OutlinedButton(
                    onClick = {
                        val activity = context.findWorkspaceActivity() ?: return@OutlinedButton
                        viewModel.prepareDriveBrowser(activity) { req ->
                            driveImportAuthLauncher.launch(req)
                        }
                    },
                    enabled = !state.isLoadingDriveFiles && !state.isImportingFromDrive,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("drive_inapp_browser_button")
                ) {
                    Icon(FolioIcons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Navegar no Google Drive (somente leitura)")
                }

                if (state.isLoadingDriveFiles || state.isImportingFromDrive) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.isImportingFromDrive) "Baixando e importando..." else "Carregando arquivos...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { viewModel.cancelDriveImport() }) {
                            Text("Cancelar")
                        }
                    }
                }

                if (!state.driveImportError.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.driveImportError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (state.driveImportFiles.isNotEmpty() || state.driveImportSearchQuery.isNotBlank()) {
                    OutlinedTextField(
                        value = state.driveImportSearchQuery,
                        onValueChange = viewModel::searchDriveImportFiles,
                        placeholder = { Text("Filtrar arquivos no Google Drive...") },
                        leadingIcon = { Icon(FolioIcons.Rounded.Search, null) },
                        trailingIcon = {
                            if (state.driveImportSearchQuery.isNotBlank()) {
                                IconButton(onClick = { viewModel.searchDriveImportFiles("") }) {
                                    Icon(FolioIcons.Rounded.Close, "Limpar")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.driveImportFiles, key = { it.id }) { item ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.isImportingFromDrive) {
                                        viewModel.importDriveDocument(item) {
                                            showGoogleDriveSheet = false
                                        }
                                    }
                                    .testTag("drive_import_item_${item.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        FolioIcons.Outlined.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val sizeLabel = if (item.sizeBytes > 0L) {
                                            FormatUtils.formatBytes(item.sizeBytes)
                                        } else {
                                            "Documento Google"
                                        }
                                        val dateLabel = if (item.modifiedEpochMs > 0L) {
                                            FormatUtils.formatDate(item.modifiedEpochMs)
                                        } else {
                                            item.mimeType
                                        }
                                        Text(
                                            text = "$sizeLabel • $dateLabel",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private tailrec fun Context.findWorkspaceActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findWorkspaceActivity()
    else -> null
}
