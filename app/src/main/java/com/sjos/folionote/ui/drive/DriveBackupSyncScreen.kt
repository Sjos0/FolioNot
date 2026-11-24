package com.sjos.folionote.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sjos.folionote.core.preferences.DriveConflictPolicy
import com.sjos.folionote.data.local.SyncConflictEntity
import com.sjos.folionote.engine.drive.ConflictResolutionChoice
import com.sjos.folionote.engine.drive.DriveFileItem
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.settings.GoogleAvatar

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriveBackupSyncScreen(
    viewModel: DriveViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val settings = state.settings
    val runtime = state.runtime

    var showCreateBackupDialog by remember { mutableStateOf(false) }
    var renamingItem by remember { mutableStateOf<DriveFileItem?>(null) }
    var restoringItem by remember { mutableStateOf<DriveFileItem?>(null) }
    var deletingItem by remember { mutableStateOf<DriveFileItem?>(null) }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
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
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("drive_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                    Text(
                        text = "Google Drive — Backups e Sync",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (settings.isGoogleDriveConnected) {
                        IconButton(
                            onClick = viewModel::refreshBackups,
                            enabled = !runtime.isBusy
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar lista de backups")
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (!settings.isGoogleDriveConnected) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Outlined.CloudOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Text("Nenhuma conta Google conectada", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "Conecte sua conta Google nas Configurações para gerenciar backups em ZIP e sincronização bidirecional.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(onClick = onOpenSettings, shape = CircleShape) {
                                        Text("Ir para Configurações")
                                    }
                                }
                            }
                        }
                    } else {
                        // Profile & Sync Header
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        GoogleAvatar(
                                            photoUrl = settings.driveAccountPhotoUrl,
                                            displayName = settings.driveAccountName,
                                            email = settings.driveAccountEmail.orEmpty()
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = settings.driveAccountName ?: "Conta Google",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = settings.driveAccountEmail.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = viewModel::refreshProfile) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar perfil")
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Auto-sync periódico", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = settings.driveLastSyncAt?.let {
                                                    "Última sync: ${FormatUtils.formatDate(it)} • ${settings.driveLastSyncStatus}"
                                                } ?: "Nenhuma sincronização executada ainda",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = settings.driveSyncEnabled,
                                            onCheckedChange = viewModel::setAutoSync
                                        )
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        DriveConflictPolicy.entries.forEach { policy ->
                                            FilterChip(
                                                selected = settings.driveConflictPolicy == policy,
                                                onClick = { viewModel.setConflictPolicy(policy) },
                                                label = { Text(policy.label) }
                                            )
                                        }
                                    }

                                    if (runtime.isBusy) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = runtime.activeOperationLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            LinearProgressIndicator(
                                                progress = { runtime.progress.coerceIn(0.05f, 1f) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }

                                    runtime.lastError?.let { err ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = err,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(onClick = viewModel::clearError, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Rounded.Close, contentDescription = "Fechar erro")
                                                }
                                            }
                                        }
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = viewModel::syncNow,
                                            enabled = !runtime.isBusy,
                                            shape = CircleShape,
                                            modifier = Modifier.testTag("drive_sync_now_button")
                                        ) {
                                            Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Sincronizar agora")
                                        }

                                        OutlinedButton(
                                            onClick = { showCreateBackupDialog = true },
                                            enabled = !runtime.isBusy,
                                            shape = CircleShape,
                                            modifier = Modifier.testTag("drive_create_backup_button")
                                        ) {
                                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Criar Backup")
                                        }

                                        if (runtime.isBusy) {
                                            OutlinedButton(
                                                onClick = viewModel::cancelActiveOperation,
                                                shape = CircleShape
                                            ) {
                                                Text("Cancelar")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Pending Conflicts Section
                        if (state.pendingConflicts.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Conflitos de Sincronização (${state.pendingConflicts.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            items(state.pendingConflicts, key = { it.id }) { conflict ->
                                ConflictResolutionCard(
                                    conflict = conflict,
                                    onResolve = { choice -> viewModel.resolveConflict(conflict.id, choice) }
                                )
                            }
                        }

                        // Backups CRUD Section
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Backups no Google Drive (${runtime.backups.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = "Arquivos ZIP contendo notebooks, fontes, chunks e conversas. Chaves de API, tokens e senhas são sempre excluídos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (runtime.backups.isEmpty()) {
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Outlined.Backup, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(34.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("Nenhum backup salvo no Google Drive", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "Toque em \"Criar Backup\" para enviar uma cópia completa dos seus notebooks.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(runtime.backups, key = { it.id }) { item ->
                                BackupRowCard(
                                    item = item,
                                    busy = runtime.isBusy,
                                    onRestore = { restoringItem = item },
                                    onRename = { renamingItem = item },
                                    onDelete = { deletingItem = item }
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showCreateBackupDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateBackupDialog = false },
            title = { Text("Criar backup no Google Drive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "O arquivo ZIP incluirá notebooks, fontes anexadas e conversas, excluindo todas as chaves de API e tokens.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome personalizado (opcional)") },
                        placeholder = { Text("Ex.: backup_estudos_enem") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCreateBackupDialog = false
                    viewModel.createBackup(name.trim().ifBlank { null })
                }) {
                    Text("Enviar backup")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateBackupDialog = false }) { Text("Cancelar") }
            }
        )
    }

    renamingItem?.let { item ->
        var nextName by remember(item) { mutableStateOf(item.name.removeSuffix(".zip")) }
        AlertDialog(
            onDismissRequest = { renamingItem = null },
            title = { Text("Renomear backup") },
            text = {
                OutlinedTextField(
                    value = nextName,
                    onValueChange = { nextName = it },
                    label = { Text("Novo nome do arquivo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameBackup(item.id, nextName)
                        renamingItem = null
                    },
                    enabled = nextName.isNotBlank()
                ) {
                    Text("Renomear")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingItem = null }) { Text("Cancelar") }
            }
        )
    }

    restoringItem?.let { item ->
        AlertDialog(
            onDismissRequest = { restoringItem = null },
            title = { Text("Restaurar backup?") },
            text = {
                Text("Restaurar '${item.name}' (${FormatUtils.formatBytes(item.sizeBytes)}) importará os notebooks, fontes e conversas salvos para este dispositivo sem sobrescrever suas chaves de API locais.")
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.restoreBackup(item)
                    restoringItem = null
                }) {
                    Text("Restaurar")
                }
            },
            dismissButton = {
                TextButton(onClick = { restoringItem = null }) { Text("Cancelar") }
            }
        )
    }

    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text("Excluir backup?") },
            text = { Text("Deseja remover permanentemente '${item.name}' do Google Drive?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteBackup(item.id)
                        deletingItem = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) { Text("Cancelar") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictResolutionCard(
    conflict: SyncConflictEntity,
    onResolve: (ConflictResolutionChoice) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("conflict_card_${conflict.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${conflict.entityType}: ${conflict.title}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = "Local (${FormatUtils.formatDate(conflict.localUpdatedAt)}): ${conflict.localSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Nuvem (${FormatUtils.formatDate(conflict.remoteUpdatedAt)}): ${conflict.remoteSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = { onResolve(ConflictResolutionChoice.KEEP_LOCAL) }, shape = CircleShape) {
                    Text("Manter local")
                }
                OutlinedButton(onClick = { onResolve(ConflictResolutionChoice.KEEP_REMOTE) }, shape = CircleShape) {
                    Text("Usar nuvem")
                }
                Button(onClick = { onResolve(ConflictResolutionChoice.DUPLICATE_BOTH) }, shape = CircleShape) {
                    Text("Duplicar ambos")
                }
            }
        }
    }
}

@Composable
private fun BackupRowCard(
    item: DriveFileItem,
    busy: Boolean,
    onRestore: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backup_row_${item.id}")
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Backup, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${FormatUtils.formatBytes(item.sizeBytes)} • ${FormatUtils.formatDate(item.modifiedTimeMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = !busy,
                shape = CircleShape
            ) {
                Text("Restaurar")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Ações do backup")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Restaurar") },
                        leadingIcon = { Icon(Icons.Outlined.Restore, null) },
                        onClick = {
                            menuOpen = false
                            onRestore()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Renomear") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
