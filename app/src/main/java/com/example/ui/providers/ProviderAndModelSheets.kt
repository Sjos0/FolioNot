package com.example.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.ModelEntity
import com.example.data.local.ProviderEntity
import com.example.data.local.ProviderType
import com.example.engine.ai.ProviderPresets
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorSheet(
    initial: ProviderEntity?,
    maskedExistingKey: String,
    onDismiss: () -> Unit,
    onSave: (
        existingId: String?,
        name: String,
        baseUrl: String,
        chatPath: String,
        modelsPath: String,
        embeddingsPath: String,
        authHeaderName: String,
        authPrefix: String,
        plainApiKeyInput: String,
        keepExistingKeyIfBlank: Boolean,
        extraHeadersJson: String,
        defaultModelId: String?,
        timeoutSeconds: Int,
        supportsStreamingDefault: Boolean,
        extraParamsJson: String,
        providerType: String,
        isEnabled: Boolean
    ) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember(initial) { mutableStateOf(initial?.baseUrl ?: "https://api.openai.com/v1") }
    var chatPath by remember(initial) { mutableStateOf(initial?.chatPath ?: "/chat/completions") }
    var modelsPath by remember(initial) { mutableStateOf(initial?.modelsPath ?: "/models") }
    var embeddingsPath by remember(initial) { mutableStateOf(initial?.embeddingsPath ?: "/embeddings") }
    var authHeaderName by remember(initial) { mutableStateOf(initial?.authHeaderName ?: "Authorization") }
    var authPrefix by remember(initial) { mutableStateOf(initial?.authPrefix ?: "Bearer") }
    var apiKeyInput by remember(initial) { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var clearStoredKey by remember { mutableStateOf(false) }
    var extraHeadersJson by remember(initial) { mutableStateOf(initial?.extraHeadersJson ?: "{}") }
    var defaultModelId by remember(initial) { mutableStateOf(initial?.defaultModelId ?: "") }
    var timeoutText by remember(initial) { mutableStateOf((initial?.timeoutSeconds ?: 60).toString()) }
    var supportsStreaming by remember(initial) { mutableStateOf(initial?.supportsStreamingDefault ?: true) }
    var extraParamsJson by remember(initial) { mutableStateOf(initial?.extraParamsJson ?: "{}") }
    var providerType by remember(initial) { mutableStateOf(initial?.providerType ?: ProviderType.OPENAI_COMPATIBLE.name) }
    var isEnabled by remember(initial) { mutableStateOf(initial?.isEnabled ?: true) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (initial == null) "Novo provedor" else "Editar provedor",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Fechar")
                }
            }

            if (initial == null) {
                Text("Modelos rápidos (todos os campos permanecem editáveis):", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ProviderPresets.all, key = { it.id }) { preset ->
                        AssistChip(
                            onClick = {
                                name = preset.name
                                baseUrl = preset.baseUrl
                                chatPath = preset.chatPath
                                modelsPath = preset.modelsPath
                                embeddingsPath = preset.embeddingsPath
                                authHeaderName = preset.authHeaderName
                                authPrefix = preset.authPrefix
                                providerType = preset.providerType.name
                            },
                            label = { Text(preset.name) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome do provedor") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("provider_name_input")
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("URL-base da API") },
                placeholder = { Text("https://api.exemplo.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("provider_baseurl_input")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = chatPath,
                    onValueChange = { chatPath = it },
                    label = { Text("Caminho Chat") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = modelsPath,
                    onValueChange = { modelsPath = it },
                    label = { Text("Caminho /models") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = embeddingsPath,
                onValueChange = { embeddingsPath = it },
                label = { Text("Caminho Embeddings (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = authHeaderName,
                    onValueChange = { authHeaderName = it },
                    label = { Text("Header Auth") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = authPrefix,
                    onValueChange = { authPrefix = it },
                    label = { Text("Prefixo (ex: Bearer)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it
                    clearStoredKey = false
                },
                label = { Text("Chave de API (armazenada via Android Keystore)") },
                placeholder = {
                    Text(
                        if (maskedExistingKey.isNotBlank() && !clearStoredKey) "Atual: $maskedExistingKey"
                        else "Cole sua API key (opcional para servidores locais)"
                    )
                },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showApiKey = !showApiKey }) {
                        Text(if (showApiKey) "Ocultar" else "Mostrar")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("provider_apikey_input")
            )

            if (maskedExistingKey.isNotBlank() && !clearStoredKey) {
                TextButton(onClick = { clearStoredKey = true; apiKeyInput = "" }) {
                    Text("Remover chave armazenada", color = MaterialTheme.colorScheme.error)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = defaultModelId,
                    onValueChange = { defaultModelId = it },
                    label = { Text("Modelo padrão (opcional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Timeout (s)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(0.6f)
                )
            }

            OutlinedTextField(
                value = extraHeadersJson,
                onValueChange = { extraHeadersJson = it },
                label = { Text("Headers HTTP extras (JSON)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = extraParamsJson,
                onValueChange = { extraParamsJson = it },
                label = { Text("Parâmetros extras na requisição (JSON)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Streaming SSE por padrão", modifier = Modifier.weight(1f))
                Switch(checked = supportsStreaming, onCheckedChange = { supportsStreaming = it })
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Provedor habilitado", modifier = Modifier.weight(1f))
                Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
            }

            Button(
                onClick = {
                    onSave(
                        initial?.id,
                        name,
                        baseUrl,
                        chatPath,
                        modelsPath,
                        embeddingsPath,
                        authHeaderName,
                        authPrefix,
                        apiKeyInput,
                        !clearStoredKey,
                        extraHeadersJson,
                        defaultModelId.ifBlank { null },
                        timeoutText.toIntOrNull() ?: 60,
                        supportsStreaming,
                        extraParamsJson,
                        providerType,
                        isEnabled
                    )
                    onDismiss()
                },
                enabled = baseUrl.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().testTag("save_provider_button")
            ) {
                Text("Salvar provedor")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelEditorSheet(
    initial: ModelEntity?,
    providers: List<ProviderEntity>,
    onDismiss: () -> Unit,
    onSave: (ModelEntity) -> Unit
) {
    var selectedProviderId by remember(initial, providers) {
        mutableStateOf(initial?.providerId ?: providers.firstOrNull()?.id.orEmpty())
    }
    var modelId by remember(initial) { mutableStateOf(initial?.modelId ?: "") }
    var displayName by remember(initial) { mutableStateOf(initial?.displayName ?: "") }
    var contextWindowText by remember(initial) { mutableStateOf((initial?.contextWindow ?: 8192).toString()) }
    var maxOutputText by remember(initial) { mutableStateOf((initial?.maxOutputTokens ?: 2048).toString()) }
    var temperatureText by remember(initial) { mutableStateOf((initial?.temperature ?: 0.7f).toString()) }
    var topPText by remember(initial) { mutableStateOf((initial?.topP ?: 1.0f).toString()) }
    var freqPenText by remember(initial) { mutableStateOf((initial?.frequencyPenalty ?: 0.0f).toString()) }
    var presPenText by remember(initial) { mutableStateOf((initial?.presencePenalty ?: 0.0f).toString()) }
    var supportsStreaming by remember(initial) { mutableStateOf(initial?.supportsStreaming ?: true) }
    var supportsTools by remember(initial) { mutableStateOf(initial?.supportsTools ?: false) }
    var supportsSystem by remember(initial) { mutableStateOf(initial?.supportsSystemMessage ?: true) }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    var isEmbedding by remember(initial) { mutableStateOf(initial?.isEmbeddingModel ?: false) }
    var embDimsText by remember(initial) { mutableStateOf(initial?.embeddingDimensions?.toString() ?: "") }
    var customParamsJson by remember(initial) { mutableStateOf(initial?.customParamsJson ?: "{}") }
    var notes by remember(initial) { mutableStateOf(initial?.notes ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (initial == null) "Cadastrar modelo manualmente" else "Configurar modelo",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fechar") }
            }

            if (providers.isNotEmpty()) {
                Text("Provedor", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    providers.forEach { p ->
                        AssistChip(
                            onClick = { selectedProviderId = p.id },
                            label = { Text(p.name) },
                            leadingIcon = if (p.id == selectedProviderId) {
                                { Icon(Icons.Rounded.Close, null, Modifier.size(0.dp)) }
                            } else null,
                            border = if (p.id == selectedProviderId) {
                                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else null
                        )
                    }
                }
            }

            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                label = { Text("Model ID na API (ex: gpt-4o-mini, llama3.3)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("model_id_input")
            )

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Nome de exibição") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = contextWindowText,
                    onValueChange = { contextWindowText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Context window") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("model_context_input")
                )
                OutlinedTextField(
                    value = maxOutputText,
                    onValueChange = { maxOutputText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max output tokens") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("model_max_output_input")
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = { temperatureText = it },
                    label = { Text("Temperatura") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = topPText,
                    onValueChange = { topPText = it },
                    label = { Text("Top P") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = freqPenText,
                    onValueChange = { freqPenText = it },
                    label = { Text("Freq. penalty") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = presPenText,
                    onValueChange = { presPenText = it },
                    label = { Text("Pres. penalty") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Suporta streaming (SSE)", modifier = Modifier.weight(1f))
                Switch(checked = supportsStreaming, onCheckedChange = { supportsStreaming = it })
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Suporta tool calling", modifier = Modifier.weight(1f))
                Switch(checked = supportsTools, onCheckedChange = { supportsTools = it })
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Suporta system message", modifier = Modifier.weight(1f))
                Switch(checked = supportsSystem, onCheckedChange = { supportsSystem = it })
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Modelo de Embeddings (não é chat)", modifier = Modifier.weight(1f))
                Switch(checked = isEmbedding, onCheckedChange = { isEmbedding = it })
            }
            if (isEmbedding) {
                OutlinedTextField(
                    value = embDimsText,
                    onValueChange = { embDimsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Dimensão do vetor (opcional, ex: 768, 1536)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Habilitado", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            OutlinedTextField(
                value = customParamsJson,
                onValueChange = { customParamsJson = it },
                label = { Text("Parâmetros customizados (JSON)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Observações do usuário") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val ctxWin = (contextWindowText.toIntOrNull() ?: 8192).coerceAtLeast(512)
                    val maxOut = (maxOutputText.toIntOrNull() ?: 2048).coerceIn(64, ctxWin)
                    val model = ModelEntity(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        providerId = selectedProviderId,
                        modelId = modelId.trim(),
                        displayName = displayName.trim().ifBlank { modelId.trim() },
                        contextWindow = ctxWin,
                        maxOutputTokens = maxOut,
                        supportsStreaming = supportsStreaming,
                        supportsTools = supportsTools,
                        supportsSystemMessage = supportsSystem,
                        enabled = enabled,
                        temperature = (temperatureText.toFloatOrNull() ?: 0.7f).coerceIn(0f, 2f),
                        topP = (topPText.toFloatOrNull() ?: 1.0f).coerceIn(0f, 1f),
                        frequencyPenalty = (freqPenText.toFloatOrNull() ?: 0f).coerceIn(-2f, 2f),
                        presencePenalty = (presPenText.toFloatOrNull() ?: 0f).coerceIn(-2f, 2f),
                        customParamsJson = customParamsJson.trim().ifBlank { "{}" },
                        notes = notes.trim(),
                        isEmbeddingModel = isEmbedding,
                        embeddingDimensions = if (isEmbedding) embDimsText.toIntOrNull() else null,
                        createdAt = initial?.createdAt ?: System.currentTimeMillis()
                    )
                    onSave(model)
                    onDismiss()
                },
                enabled = modelId.isNotBlank() && selectedProviderId.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().testTag("save_model_button")
            ) {
                Text("Salvar modelo")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ModelDiscoveryDialog(
    state: DiscoveryDialogState,
    onRefresh: () -> Unit,
    onToggleModel: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(state.discovered, search) {
        if (search.isBlank()) state.discovered
        else state.discovered.filter {
            it.modelId.contains(search, ignoreCase = true) ||
                it.displayName.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Modelos • ${state.provider.name}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar lista")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Consultando ${state.provider.modelsPath}...")
                        }
                    }
                    state.errorMessage != null -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Text(
                            text = "Dica: você ainda pode cadastrar modelos manualmente na aba Modelos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = { Text("Filtrar modelos...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val allChecked = filtered.isNotEmpty() && filtered.all { it.modelId in state.selectedModelIds }
                            Text("${filtered.size} encontrados", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onSelectAll(!allChecked) }) {
                                Text(if (allChecked) "Desmarcar" else "Selecionar todos")
                            }
                        }
                        LazyColumn(modifier = Modifier.height(260.dp)) {
                            items(filtered, key = { it.modelId }) { item ->
                                val checked = item.modelId in state.selectedModelIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleModel(item.modelId) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = { onToggleModel(item.modelId) })
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = buildString {
                                                append(item.modelId)
                                                item.contextLengthHint?.let { append(" • ctx $it") }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.isLoading && state.selectedModelIds.isNotEmpty()
            ) {
                Text("Adicionar (${state.selectedModelIds.size})")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}
