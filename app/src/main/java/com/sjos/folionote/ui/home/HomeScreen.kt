package com.sjos.folionote.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sjos.folionote.data.local.CollectionEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.ui.components.CollectionGeometricIcon
import com.sjos.folionote.ui.components.FolioLogoMark
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.theme.NotebookIcons
import com.sjos.folionote.ui.theme.ThemePreference

import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import com.sjos.folionote.ui.settings.GoogleAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenNotebook: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDriveSync: () -> Unit = {},
    pendingSharedUrl: String? = null,
    onClearSharedUrl: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showFilterMenu by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var showCreateNotebookDialog by remember { mutableStateOf(false) }
    var editingNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var movingNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!state.isSearchActive && state.filterMode != HomeFilterMode.COLLECTIONS) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateNotebookDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("create_notebook_fab")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Novo notebook")
                }
            }
        }
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
                if (state.isSearchActive) {
                    SearchHeaderBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onClose = { viewModel.setSearchActive(false) }
                    )
                } else {
                    HomeTopBar(
                        filterMode = state.filterMode,
                        showFilterMenu = showFilterMenu,
                        onToggleFilterMenu = { showFilterMenu = !showFilterMenu },
                        onSelectFilter = {
                            viewModel.setFilterMode(it)
                            showFilterMenu = false
                        },
                        onOpenSearch = { viewModel.setSearchActive(true) },
                        onOpenProviders = onOpenProviders,
                        showQuickMenu = showQuickMenu,
                        onToggleQuickMenu = { showQuickMenu = !showQuickMenu },
                        currentTheme = state.settings.themePreference,
                        isDriveConnected = state.settings.isGoogleDriveConnected,
                        drivePhotoUrl = state.settings.driveAccountPhotoUrl,
                        driveDisplayName = state.settings.driveAccountName,
                        driveEmail = state.settings.driveAccountEmail.orEmpty(),
                        onSelectTheme = {
                            viewModel.setThemePreference(it)
                            showQuickMenu = false
                        },
                        onOpenSettings = {
                            showQuickMenu = false
                            onOpenSettings()
                        },
                        onOpenDriveSync = {
                            showQuickMenu = false
                            onOpenDriveSync()
                        }
                    )
                }

                if (state.isSearchActive && state.searchQuery.isNotBlank()) {
                    GlobalSearchResultsList(
                        state = state,
                        onOpenNotebook = onOpenNotebook
                    )
                } else {
                    if (state.storageStats.isLowStorage) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Armazenamento do dispositivo baixo (${FormatUtils.formatBytes(state.storageStats.deviceAvailableBytes)} livres).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    when (state.filterMode) {
                        HomeFilterMode.MY_NOTEBOOKS -> {
                            MyNotebooksBody(
                                state = state,
                                onToggleSort = viewModel::toggleSortMode,
                                onClearCollectionFilter = viewModel::clearCollectionFilter,
                                onOpenNotebook = onOpenNotebook,
                                onTogglePin = viewModel::togglePin,
                                onEditNotebook = { editingNotebook = it },
                                onMoveToCollection = { movingNotebook = it },
                                onDeleteNotebook = { viewModel.deleteNotebook(it.id) },
                                onCreateFirstNotebook = { showCreateNotebookDialog = true },
                                onOpenProviders = onOpenProviders
                            )
                        }
                        HomeFilterMode.COLLECTIONS -> {
                            CollectionsBody(
                                collections = state.collections,
                                notebookCountByCollection = state.notebookCountByCollection,
                                onCreateCollection = { showCreateCollectionDialog = true },
                                onOpenCollection = viewModel::openCollection,
                                onDeleteCollection = viewModel::deleteCollection
                            )
                        }
                        HomeFilterMode.ALL -> {
                            AllOverviewBody(
                                state = state,
                                onOpenNotebook = onOpenNotebook,
                                onSeeAllNotebooks = { viewModel.setFilterMode(HomeFilterMode.MY_NOTEBOOKS) },
                                onTogglePin = viewModel::togglePin,
                                onEditNotebook = { editingNotebook = it },
                                onMoveToCollection = { movingNotebook = it },
                                onDeleteNotebook = { viewModel.deleteNotebook(it.id) },
                                onCreateFirstNotebook = { showCreateNotebookDialog = true },
                                onOpenProviders = onOpenProviders
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateNotebookDialog || editingNotebook != null) {
        NotebookEditorDialog(
            initial = editingNotebook,
            collections = state.collections,
            defaultCollectionId = state.selectedCollectionId,
            onDismiss = {
                showCreateNotebookDialog = false
                editingNotebook = null
            },
            onSave = { title, desc, iconKey, colId ->
                val target = editingNotebook
                if (target == null) {
                    viewModel.createNotebook(title, desc, iconKey, colId) { createdId ->
                        showCreateNotebookDialog = false
                        onOpenNotebook(createdId)
                    }
                } else {
                    viewModel.updateNotebook(
                        target.copy(
                            title = title,
                            description = desc,
                            iconKey = iconKey,
                            collectionId = colId
                        )
                    )
                    editingNotebook = null
                }
            }
        )
    }

    if (movingNotebook != null) {
        MoveCollectionDialog(
            notebook = movingNotebook!!,
            collections = state.collections,
            onDismiss = { movingNotebook = null },
            onSelectCollection = { colId ->
                viewModel.assignNotebookToCollection(movingNotebook!!.id, colId)
                movingNotebook = null
            }
        )
    }

    if (showCreateCollectionDialog) {
        CreateCollectionDialog(
            onDismiss = { showCreateCollectionDialog = false },
            onCreate = { name, desc ->
                viewModel.createCollection(name, desc)
                showCreateCollectionDialog = false
            }
        )
    }

    if (!pendingSharedUrl.isNullOrBlank()) {
        ShareWebClipDialog(
            sharedUrl = pendingSharedUrl,
            notebooks = state.notebooks,
            isLoading = state.isClippingSharedUrl,
            onDismiss = onClearSharedUrl,
            onConfirm = { targetNotebookId, newNotebookTitle ->
                viewModel.clipSharedUrlToNotebook(
                    notebookId = targetNotebookId,
                    newNotebookTitle = newNotebookTitle,
                    rawUrl = pendingSharedUrl
                ) { nbId ->
                    onClearSharedUrl()
                    onOpenNotebook(nbId)
                }
            }
        )
    }
}

@Composable
private fun HomeTopBar(
    filterMode: HomeFilterMode,
    showFilterMenu: Boolean,
    onToggleFilterMenu: () -> Unit,
    onSelectFilter: (HomeFilterMode) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenProviders: () -> Unit,
    showQuickMenu: Boolean,
    onToggleQuickMenu: () -> Unit,
    currentTheme: ThemePreference,
    isDriveConnected: Boolean,
    drivePhotoUrl: String?,
    driveDisplayName: String?,
    driveEmail: String,
    onSelectTheme: (ThemePreference) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDriveSync: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolioLogoMark(modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))

        Box {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = CircleShape,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFilterMenu)
                    .minimumInteractiveComponentSize()
                    .testTag("home_filter_dropdown")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = filterMode.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Alterar visualização",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DropdownMenu(
                expanded = showFilterMenu,
                onDismissRequest = onToggleFilterMenu
            ) {
                HomeFilterMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        trailingIcon = {
                            if (mode == filterMode) {
                                Icon(Icons.Rounded.Check, contentDescription = null)
                            }
                        },
                        onClick = { onSelectFilter(mode) }
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        IconButton(
            onClick = onOpenSearch,
            modifier = Modifier.testTag("home_search_button")
        ) {
            Icon(Icons.Rounded.Search, contentDescription = "Pesquisar")
        }

        IconButton(
            onClick = onOpenProviders,
            modifier = Modifier.testTag("home_providers_button")
        ) {
            Icon(Icons.Outlined.Apps, contentDescription = "Provedores e Modelos de IA")
        }

        Box {
            IconButton(
                onClick = onToggleQuickMenu,
                modifier = Modifier.testTag("home_settings_menu_button")
            ) {
                if (isDriveConnected) {
                    GoogleAvatar(
                        photoUrl = drivePhotoUrl,
                        displayName = driveDisplayName,
                        email = driveEmail,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(Icons.Outlined.Settings, contentDescription = "Configurações e Tema")
                }
            }

            DropdownMenu(
                expanded = showQuickMenu,
                onDismissRequest = onToggleQuickMenu
            ) {
                DropdownMenuItem(
                    text = { Text("Provedores e Modelos") },
                    leadingIcon = { Icon(Icons.Rounded.SmartToy, contentDescription = null) },
                    onClick = {
                        onToggleQuickMenu()
                        onOpenProviders()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Google Drive (Backups e Sync)") },
                    leadingIcon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
                    onClick = onOpenDriveSync
                )
                DropdownMenuItem(
                    text = { Text("Configurações avançadas") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = onOpenSettings
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text("Modo claro") },
                    leadingIcon = { Icon(Icons.Outlined.LightMode, contentDescription = null) },
                    trailingIcon = { if (currentTheme == ThemePreference.LIGHT) Icon(Icons.Rounded.Check, null) },
                    onClick = { onSelectTheme(ThemePreference.LIGHT) }
                )
                DropdownMenuItem(
                    text = { Text("Modo escuro") },
                    leadingIcon = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
                    trailingIcon = { if (currentTheme == ThemePreference.DARK) Icon(Icons.Rounded.Check, null) },
                    onClick = { onSelectTheme(ThemePreference.DARK) }
                )
                DropdownMenuItem(
                    text = { Text("Dispositivo") },
                    leadingIcon = { Icon(Icons.Outlined.Smartphone, contentDescription = null) },
                    trailingIcon = { if (currentTheme == ThemePreference.SYSTEM) Icon(Icons.Rounded.Check, null) },
                    onClick = { onSelectTheme(ThemePreference.SYSTEM) }
                )
            }
        }
    }
}

@Composable
private fun SearchHeaderBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Pesquisar notebooks, fontes e conversas...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Fechar busca")
                }
            },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("global_search_input")
        )
    }
}

@Composable
private fun MyNotebooksBody(
    state: HomeUiState,
    onToggleSort: () -> Unit,
    onClearCollectionFilter: () -> Unit,
    onOpenNotebook: (String) -> Unit,
    onTogglePin: (NotebookEntity) -> Unit,
    onEditNotebook: (NotebookEntity) -> Unit,
    onMoveToCollection: (NotebookEntity) -> Unit,
    onDeleteNotebook: (NotebookEntity) -> Unit,
    onCreateFirstNotebook: () -> Unit,
    onOpenProviders: () -> Unit
) {
    val activeCollection = state.collections.firstOrNull { it.id == state.selectedCollectionId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activeCollection?.name ?: "Meus notebooks",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(10.dp))
                IconButton(
                    onClick = onToggleSort,
                    modifier = Modifier.testTag("sort_notebooks_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = "Ordenar notebooks (${state.sortMode.label})",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${state.providerCount} prov. • ${state.modelCount} mod.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenProviders)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (activeCollection != null) {
            item {
                AssistChip(
                    onClick = onClearCollectionFilter,
                    label = { Text("Coleção: ${activeCollection.name} (Limpar filtro)") },
                    trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        }

        if (state.notebooks.isEmpty()) {
            item {
                EmptyNotebooksCard(
                    hasProviders = state.modelCount > 0,
                    onCreateNotebook = onCreateFirstNotebook,
                    onConfigureProviders = onOpenProviders
                )
            }
        } else {
            items(state.notebooks, key = { it.id }) { notebook ->
                NotebookRowItem(
                    notebook = notebook,
                    sourceCount = state.sourceCountsByNotebook[notebook.id] ?: 0,
                    onClick = { onOpenNotebook(notebook.id) },
                    onTogglePin = { onTogglePin(notebook) },
                    onEdit = { onEditNotebook(notebook) },
                    onMoveCollection = { onMoveToCollection(notebook) },
                    onDelete = { onDeleteNotebook(notebook) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun CollectionsBody(
    collections: List<CollectionEntity>,
    notebookCountByCollection: Map<String, Int>,
    onCreateCollection: () -> Unit,
    onOpenCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Minhas coleções",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onCreateCollection,
                    shape = CircleShape,
                    modifier = Modifier.testTag("create_collection_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nova coleção")
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(38.dp))
                Text(
                    text = "Nome",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Notebooks",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.8.dp)
        }

        if (collections.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CollectionGeometricIcon(modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Nenhuma coleção criada",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Organize seus notebooks por disciplina, projeto ou área de pesquisa.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(collections, key = { it.id }) { collection ->
                val count = notebookCountByCollection[collection.id] ?: 0
                var menuOpen by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCollection(collection.id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CollectionGeometricIcon(modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = collection.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (count == 1) "1 notebook" else "$count notebooks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Opções da coleção")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Excluir coleção") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                onClick = {
                                    menuOpen = false
                                    onDeleteCollection(collection.id)
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun AllOverviewBody(
    state: HomeUiState,
    onOpenNotebook: (String) -> Unit,
    onSeeAllNotebooks: () -> Unit,
    onTogglePin: (NotebookEntity) -> Unit,
    onEditNotebook: (NotebookEntity) -> Unit,
    onMoveToCollection: (NotebookEntity) -> Unit,
    onDeleteNotebook: (NotebookEntity) -> Unit,
    onCreateFirstNotebook: () -> Unit,
    onOpenProviders: () -> Unit
) {
    val pinned = state.notebooks.filter { it.isPinned }
    val recent = state.notebooks.take(8)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notebooks recentes",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSeeAllNotebooks) {
                    Text("Ver mais")
                }
            }
        }

        if (recent.isEmpty()) {
            item {
                EmptyNotebooksCard(
                    hasProviders = state.modelCount > 0,
                    onCreateNotebook = onCreateFirstNotebook,
                    onConfigureProviders = onOpenProviders
                )
            }
        } else {
            items(recent, key = { "recent_${it.id}" }) { notebook ->
                NotebookRowItem(
                    notebook = notebook,
                    sourceCount = state.sourceCountsByNotebook[notebook.id] ?: 0,
                    onClick = { onOpenNotebook(notebook.id) },
                    onTogglePin = { onTogglePin(notebook) },
                    onEdit = { onEditNotebook(notebook) },
                    onMoveCollection = { onMoveToCollection(notebook) },
                    onDelete = { onDeleteNotebook(notebook) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        if (pinned.isNotEmpty()) {
            item {
                Text(
                    text = "Fixados",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp)
                )
            }
            items(pinned, key = { "pinned_${it.id}" }) { notebook ->
                NotebookRowItem(
                    notebook = notebook,
                    sourceCount = state.sourceCountsByNotebook[notebook.id] ?: 0,
                    onClick = { onOpenNotebook(notebook.id) },
                    onTogglePin = { onTogglePin(notebook) },
                    onEdit = { onEditNotebook(notebook) },
                    onMoveCollection = { onMoveToCollection(notebook) },
                    onDelete = { onDeleteNotebook(notebook) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun NotebookRowItem(
    notebook: NotebookEntity,
    sourceCount: Int,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onMoveCollection: () -> Unit,
    onDelete: () -> Unit
) {
    val iconSpec = remember(notebook.iconKey) { NotebookIcons.resolve(notebook.iconKey) }
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .minimumInteractiveComponentSize()
            .testTag("notebook_row_${notebook.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = iconSpec.icon,
            contentDescription = iconSpec.label,
            tint = iconSpec.tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = notebook.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (notebook.isPinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "Fixado",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                text = "$sourceCount fonte(s) • ${FormatUtils.formatDate(notebook.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Menu do notebook",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (notebook.isPinned) "Desafixar" else "Fixar") },
                    leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                    onClick = {
                        menuExpanded = false
                        onTogglePin()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Personalizar") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Mover para coleção") },
                    leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) },
                    onClick = {
                        menuExpanded = false
                        onMoveCollection()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun GlobalSearchResultsList(
    state: HomeUiState,
    onOpenNotebook: (String) -> Unit
) {
    val res = state.searchResults
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (res.notebooks.isEmpty() && res.sources.isEmpty() && res.messages.isEmpty()) {
            item {
                Text(
                    text = "Nenhum resultado encontrado para \"${state.searchQuery}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        if (res.notebooks.isNotEmpty()) {
            item {
                Text("Notebooks", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            }
            items(res.notebooks, key = { "sr_nb_${it.id}" }) { nb ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNotebook(nb.id) }
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = NotebookIcons.resolve(nb.iconKey)
                        Icon(icon.icon, null, tint = icon.tint)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(nb.title, style = MaterialTheme.typography.titleMedium)
                            if (nb.description.isNotBlank()) {
                                Text(nb.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (res.sources.isNotEmpty()) {
            item {
                Text("Fontes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            }
            items(res.sources, key = { "sr_src_${it.id}" }) { src ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNotebook(src.notebookId) }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(src.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            src.extractedText.take(140).ifBlank { src.sourceType },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
            }
        }

        if (res.messages.isNotEmpty()) {
            item {
                Text("Conversas", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            }
            items(res.messages, key = { "sr_msg_${it.id}" }) { msg ->
                val targetNotebookId = res.notebookIdByConversationId[msg.conversationId]
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (targetNotebookId != null) {
                                Modifier.clickable { onOpenNotebook(targetNotebookId) }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = if (msg.role == "USER") "Você" else (msg.modelDisplayName ?: "Assistente"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(msg.content.take(180), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNotebooksCard(
    hasProviders: Boolean,
    onCreateNotebook: () -> Unit,
    onConfigureProviders: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FolioLogoMark(modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Seu espaço de conhecimento local-first",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Crie notebooks ilimitados, importe PDFs, DOCX, Markdown ou notas e conecte qualquer provedor compatível com OpenAI Chat Completions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCreateNotebook, shape = CircleShape) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Criar notebook")
                }
                if (!hasProviders) {
                    OutlinedButton(onClick = onConfigureProviders, shape = CircleShape) {
                        Text("Configurar IA")
                    }
                }
            }
        }
    }
}

@Composable
private fun NotebookEditorDialog(
    initial: NotebookEntity?,
    collections: List<CollectionEntity>,
    defaultCollectionId: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String?) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var iconKey by remember { mutableStateOf(initial?.iconKey ?: "book") }
    var selectedColId by remember { mutableStateOf(initial?.collectionId ?: defaultCollectionId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Novo notebook" else "Personalizar notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nome do notebook") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("notebook_title_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descrição opcional") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Ícone", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(NotebookIcons.all, key = { it.key }) { spec ->
                        val selected = spec.key == iconKey
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { iconKey = spec.key }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(spec.icon, contentDescription = spec.label, tint = spec.tint)
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = collections.isNotEmpty()) {
                    Column {
                        Text("Coleção", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                AssistChip(
                                    onClick = { selectedColId = null },
                                    label = { Text("Nenhuma") },
                                    leadingIcon = if (selectedColId == null) {
                                        { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                            items(collections, key = { it.id }) { col ->
                                AssistChip(
                                    onClick = { selectedColId = col.id },
                                    label = { Text(col.name) },
                                    leadingIcon = if (selectedColId == col.id) {
                                        { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.ifBlank { "Notebook sem título" }, description, iconKey, selectedColId) },
                modifier = Modifier.testTag("save_notebook_button")
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun MoveCollectionDialog(
    notebook: NotebookEntity,
    collections: List<CollectionEntity>,
    onDismiss: () -> Unit,
    onSelectCollection: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover \"${notebook.title}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { onSelectCollection(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sem coleção", modifier = Modifier.weight(1f))
                    if (notebook.collectionId == null) Icon(Icons.Rounded.Check, null)
                }
                collections.forEach { col ->
                    TextButton(
                        onClick = { onSelectCollection(col.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(col.name, modifier = Modifier.weight(1f))
                        if (notebook.collectionId == col.id) Icon(Icons.Rounded.Check, null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
private fun CreateCollectionDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova coleção") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome da coleção") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Descrição opcional") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name.ifBlank { "Nova coleção" }, desc) }) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun ShareWebClipDialog(
    sharedUrl: String,
    notebooks: List<NotebookEntity>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (notebookId: String?, newNotebookTitle: String?) -> Unit
) {
    var selectedNotebookId by remember(notebooks) {
        mutableStateOf(notebooks.firstOrNull()?.id)
    }
    var createNew by remember(notebooks) { mutableStateOf(notebooks.isEmpty()) }
    var newTitle by remember { mutableStateOf("Web Clips") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Salvar link no FolioNote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = sharedUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (notebooks.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoading) { createNew = false },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = !createNew, onClick = { createNew = false })
                        Text("Notebook existente", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!createNew) {
                        LazyColumn(modifier = Modifier.height(140.dp)) {
                            items(notebooks, key = { it.id }) { nb ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isLoading) { selectedNotebookId = nb.id }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedNotebookId == nb.id,
                                        onClick = { selectedNotebookId = nb.id }
                                    )
                                    Text(nb.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { createNew = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = createNew, onClick = { createNew = true })
                    Text("Criar novo notebook", style = MaterialTheme.typography.bodyMedium)
                }
                if (createNew) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Nome do novo notebook") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (createNew) onConfirm(null, newTitle)
                    else onConfirm(selectedNotebookId, null)
                },
                enabled = !isLoading && (createNew || selectedNotebookId != null)
            ) {
                Text("Capturar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancelar") }
        }
    )
}
