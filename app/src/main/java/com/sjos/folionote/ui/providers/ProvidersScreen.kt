package com.sjos.folionote.ui.providers

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.ProviderType
import com.sjos.folionote.data.local.ToolDefinitionEntity

@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showProviderEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var showModelEditor by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ModelEntity?>(null) }

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                    Text(
                        text = "Provedores e Modelos",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (selectedTab == 1) {
                                editingModel = null
                                showModelEditor = true
                            } else {
                                editingProvider = null
                                showProviderEditor = true
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier.testTag("add_provider_or_model_button")
                    ) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (selectedTab == 1) "Modelo" else "Provedor")
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Provedores", "Modelos", "Embeddings & Tools").forEachIndexed { idx, label ->
                            val selected = selectedTab == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                    .clickable { selectedTab = idx }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                when (selectedTab) {
                    0 -> ProvidersListTab(
                        state = state,
                        onTestConnection = viewModel::testConnection,
                        onFetchModels = viewModel::openFetchModels,
                        onEdit = {
                            editingProvider = it
                            showProviderEditor = true
                        },
                        onDuplicate = viewModel::duplicateProvider,
                        onToggleEnabled = viewModel::toggleProviderEnabled,
                        onDelete = { viewModel.deleteProvider(it.id) },
                        onAddProvider = {
                            editingProvider = null
                            showProviderEditor = true
                        }
                    )
                    1 -> ModelsLibraryTab(
                        state = state,
                        onEditModel = {
                            editingModel = it
                            showModelEditor = true
                        },
                        onToggleEnabled = { viewModel.saveModel(it.copy(enabled = !it.enabled)) },
                        onDeleteModel = { viewModel.deleteModel(it.id) },
                        onAddManualModel = {
                            editingModel = null
                            showModelEditor = true
                        }
                    )
                    else -> EmbeddingsAndToolsTab(
                        state = state,
                        onSelectEmbeddingModel = viewModel::setActiveEmbeddingModel,
                        onToggleTool = viewModel::toggleToolEnabled
                    )
                }
            }
        }
    }

    if (showProviderEditor) {
        val masked = editingProvider?.let { viewModel.getMaskedKey(it) }.orEmpty()
        ProviderEditorSheet(
            initial = editingProvider,
            maskedExistingKey = masked,
            onDismiss = {
                showProviderEditor = false
                editingProvider = null
            },
            onSave = viewModel::saveProvider
        )
    }

    if (showModelEditor) {
        ModelEditorSheet(
            initial = editingModel,
            providers = state.providers,
            onDismiss = {
                showModelEditor = false
                editingModel = null
            },
            onSave = viewModel::saveModel
        )
    }

    state.discoveryState?.let { disc ->
        ModelDiscoveryDialog(
            state = disc,
            onRefresh = { viewModel.refreshDiscoveryModels(disc.provider) },
            onToggleModel = viewModel::toggleDiscoveredSelection,
            onSelectAll = viewModel::selectAllDiscovered,
            onConfirm = viewModel::confirmAddDiscoveredModels,
            onDismiss = viewModel::dismissDiscovery
        )
    }
}

@Composable
private fun ProvidersListTab(
    state: ProvidersUiState,
    onTestConnection: (ProviderEntity) -> Unit,
    onFetchModels: (ProviderEntity) -> Unit,
    onEdit: (ProviderEntity) -> Unit,
    onDuplicate: (ProviderEntity) -> Unit,
    onToggleEnabled: (ProviderEntity) -> Unit,
    onDelete: (ProviderEntity) -> Unit,
    onAddProvider: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.providers.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nenhum provedor cadastrado", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Adicione quantos provedores desejar (OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Gemini OpenAI-compat, Gemini Embedding 2 Nativo, Ollama ou qualquer URL personalizada).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onAddProvider, shape = CircleShape) {
                            Text("Adicionar primeiro provedor")
                        }
                    }
                }
            }
        } else {
            items(state.providers, key = { it.id }) { provider ->
                val isTesting = state.testingProviderId == provider.id
                val testRes = state.testResultByProvider[provider.id]
                val modelCount = state.models.count { it.providerId == provider.id }
                var menuOpen by remember { mutableStateOf(false) }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${provider.baseUrl} • $modelCount modelo(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (provider.providerType == ProviderType.GEMINI_NATIVE_EMBEDDING.name) {
                                    Text(
                                        text = "Adaptador nativo Gemini Embedding (:embedContent / :batchEmbedContents)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Switch(checked = provider.isEnabled, onCheckedChange = { onToggleEnabled(provider) })
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "Ações do provedor")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Editar") },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                        onClick = { menuOpen = false; onEdit(provider) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Duplicar") },
                                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                        onClick = { menuOpen = false; onDuplicate(provider) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menuOpen = false; onDelete(provider) }
                                    )
                                }
                            }
                        }

                        if (testRes != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (testRes.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (testRes.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = testRes.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (testRes.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onTestConnection(provider) },
                                enabled = !isTesting,
                                shape = CircleShape,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.NetworkCheck, null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Testar conexão", maxLines = 1)
                            }
                            Button(
                                onClick = { onFetchModels(provider) },
                                shape = CircleShape,
                                modifier = Modifier.weight(1f).testTag("fetch_models_${provider.id}")
                            ) {
                                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Buscar modelos", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelsLibraryTab(
    state: ProvidersUiState,
    onEditModel: (ModelEntity) -> Unit,
    onToggleEnabled: (ModelEntity) -> Unit,
    onDeleteModel: (ModelEntity) -> Unit,
    onAddManualModel: () -> Unit
) {
    val providerNames = remember(state.providers) { state.providers.associate { it.id to it.name } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.models.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Biblioteca de modelos vazia", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Use \"Buscar modelos\" em um provedor ou cadastre um modelo manualmente com seu Context Window e Max Output Tokens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onAddManualModel,
                            enabled = state.providers.isNotEmpty(),
                            shape = CircleShape
                        ) {
                            Text("Cadastrar modelo manualmente")
                        }
                    }
                }
            }
        } else {
            items(state.models, key = { it.id }) { model ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditModel(model) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${providerNames[model.providerId] ?: "Provedor"} • ${model.modelId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "ctx ${model.contextWindow} • out ${model.maxOutputTokens}" +
                                    (if (model.supportsStreaming) " • SSE" else "") +
                                    (if (model.supportsTools) " • Tools" else "") +
                                    (if (model.isEmbeddingModel) " • Embedding" + (model.embeddingDimensions?.let { " (${it}d)" } ?: "") else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Switch(checked = model.enabled, onCheckedChange = { onToggleEnabled(model) })
                        IconButton(onClick = { onDeleteModel(model) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remover modelo")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbeddingsAndToolsTab(
    state: ProvidersUiState,
    onSelectEmbeddingModel: (String?) -> Unit,
    onToggleTool: (ToolDefinitionEntity) -> Unit
) {
    val embeddingModels = remember(state.models) {
        state.models.filter { it.isEmbeddingModel && it.enabled }
    }
    val providerNames = remember(state.providers) { state.providers.associate { it.id to it.name } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Provedor de Embeddings (desacoplado do Chat)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                text = "Escolha qual modelo gera vetores semânticos (Gemini Embedding 2 nativo ou OpenAI-compatível). Cada coleção registra modelo e dimensão; vetores incompatíveis são isolados e limpos ao reindexar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectEmbeddingModel(null) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.settings.activeEmbeddingModelId == null,
                            onClick = { onSelectEmbeddingModel(null) }
                        )
                        Column {
                            Text("Apenas Busca Lexical Offline (BM25)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Zero chamadas externas para indexação; funciona 100% offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    embeddingModels.forEach { emb ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectEmbeddingModel(emb.id) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.settings.activeEmbeddingModelId == emb.id,
                                onClick = { onSelectEmbeddingModel(emb.id) }
                            )
                            Column {
                                Text(emb.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${providerNames[emb.providerId] ?: "Provedor"} • ${emb.modelId}" +
                                        (emb.embeddingDimensions?.let { " • ${it}d" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.padding(top = 8.dp))
            Text("Tool Calling (Ferramentas Locais)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Apenas modelos com 'Suporta tool calling' ativado receberão estas ferramentas. Ações de escrita sempre exigem autorização na conversa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(state.tools, key = { it.id }) { tool ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tool.name, style = MaterialTheme.typography.titleSmall)
                        Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tool.requiresUserApproval) {
                            Text(
                                "Exige confirmação explícita do usuário",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Switch(checked = tool.enabled, onCheckedChange = { onToggleTool(tool) })
                }
            }
        }
    }
}
