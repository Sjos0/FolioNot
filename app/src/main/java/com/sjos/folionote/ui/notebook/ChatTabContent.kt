package com.sjos.folionote.ui.notebook

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sjos.folionote.data.local.CitationEntity
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.MessageRole
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.ui.components.MarkdownLiteText
import com.sjos.folionote.ui.theme.FolioIcons
import com.sjos.folionote.ui.theme.JetBrainsMonoFontFamily
import com.sjos.folionote.ui.theme.NotebookIcons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatTabContent(
    state: NotebookUiState,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerate: () -> Unit,
    onTogglePin: (MessageEntity) -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenConfigSheet: () -> Unit,
    onOpenSourcesTab: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSourceAtCitation: (sourceId: String, page: Int, chunkId: String) -> Unit,
    onResolveToolApproval: (Boolean) -> Unit,
    onAcknowledgeExternalDisclosure: () -> Unit,
    onOpenContextBudget: () -> Unit,
    onDismissError: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var inspectingCitation by remember { mutableStateOf<CitationEntity?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val selectedSourcesCount = state.sources.count { it.isSelectedForChat }

    LaunchedEffect(state.messages.size, state.chatRuntime.streamingText.length) {
        if (state.messages.isNotEmpty() || state.chatRuntime.isStreaming) {
            val targetIdx = (state.messages.size + if (state.chatRuntime.isStreaming) 1 else 0).coerceAtLeast(0)
            if (targetIdx > 0) listState.animateScrollToItem(targetIdx - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = CircleShape,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { showModelPicker = true }
                    .testTag("chat_model_selector")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        FolioIcons.Rounded.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    val modelLabel = state.selectedModel?.let { m ->
                        val pName = state.providersById[m.providerId]?.name
                        if (pName != null) "${m.displayName} • $pName" else m.displayName
                    } ?: "Selecionar modelo de IA"
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(FolioIcons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.weight(1f))

            if (state.chatRuntime.lastBudgetReport != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onOpenContextBudget)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(FolioIcons.Rounded.DataUsage, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        val r = state.chatRuntime.lastBudgetReport
                        Text(
                            text = "${r.totalUsedInputTokens}/${r.contextWindow}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = !state.settings.externalDisclosureAck && state.selectedModel != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(FolioIcons.Rounded.Security, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Ao conversar, apenas os trechos recuperados e o histórico do orçamento são enviados ao provedor selecionado.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onAcknowledgeExternalDisclosure) { Text("OK") }
                }
            }
        }

        AnimatedVisibility(visible = state.chatRuntime.errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("chat_error_banner")
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.chatRuntime.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissError,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chat_dismiss_error_button")
                        ) {
                            Icon(
                                FolioIcons.Rounded.Close,
                                contentDescription = "Fechar erro",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                onDismissError()
                                onRegenerate()
                            },
                            enabled = !state.chatRuntime.isStreaming && state.selectedModel != null,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chat_retry_button")
                        ) {
                            Icon(
                                imageVector = FolioIcons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Tentar novamente",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.messages.isEmpty() && !state.chatRuntime.isStreaming) {
                item {
                    ChatWelcomeHeader(
                        notebookTitle = state.notebook?.title ?: "Notebook",
                        notebookIconKey = state.notebook?.iconKey ?: "book",
                        sourceCount = state.sources.size,
                        selectedCount = selectedSourcesCount,
                        hasModel = state.selectedModel != null,
                        onOpenProviders = onOpenProviders,
                        onOpenSources = onOpenSourcesTab,
                        onStarterClick = { starter ->
                            if (state.selectedModel != null) onSendMessage(starter)
                        }
                    )
                }
            }

            items(state.messages, key = { it.id }) { msg ->
                val isUser = msg.role == MessageRole.USER.name
                val citations = state.citationsByMessage[msg.id].orEmpty()
                val isLastAssistant = !isUser && state.messages.lastOrNull { it.role == MessageRole.ASSISTANT.name }?.id == msg.id

                if (isUser) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                text = msg.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                } else {
                    AssistantMessageBlock(
                        message = msg,
                        citations = citations,
                        showRegenerate = isLastAssistant && !state.chatRuntime.isStreaming,
                        onCitationClick = { inspectingCitation = it },
                        onPinClick = { onTogglePin(msg) },
                        onCopyClick = { clipboard.setText(AnnotatedString(msg.content)) },
                        onRegenerateClick = onRegenerate
                    )
                }
            }

            if (state.chatRuntime.isStreaming) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = state.chatRuntime.activeStatusText.ifBlank { "Gerando resposta..." },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.height(6.dp))
                        if (state.chatRuntime.streamingText.isNotEmpty()) {
                            MarkdownLiteText(text = state.chatRuntime.streamingText)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            val pendingApproval = state.chatRuntime.pendingToolApproval
            if (pendingApproval != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Autorização de ferramenta",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = pendingApproval.humanDescription,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${pendingApproval.call.name}(${pendingApproval.call.argumentsJson})",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { onResolveToolApproval(true) }) {
                                    Text("Autorizar")
                                }
                                OutlinedButton(onClick = { onResolveToolApproval(false) }) {
                                    Text("Recusar")
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenConfigSheet,
                        modifier = Modifier.testTag("chat_config_button")
                    ) {
                        Icon(
                            imageVector = FolioIcons.Rounded.Tune,
                            contentDescription = "Configurar conversas",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                "Faça uma pergunta ou crie algo",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field")
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onOpenSourcesTab)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = FolioIcons.Outlined.Description,
                            contentDescription = "Fontes selecionadas",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "($selectedSourcesCount)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    val isStreaming = state.chatRuntime.isStreaming
                    val canSend = inputText.isNotBlank() && state.selectedModel != null
                    Surface(
                        color = if (isStreaming || canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(enabled = isStreaming || canSend) {
                                if (isStreaming) {
                                    onStopGeneration()
                                } else {
                                    val outgoing = inputText
                                    inputText = ""
                                    onSendMessage(outgoing)
                                }
                            }
                            .testTag("chat_send_stop_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isStreaming) FolioIcons.Rounded.Stop else FolioIcons.Rounded.ArrowUpward,
                                contentDescription = if (isStreaming) "Interromper geração" else "Enviar mensagem",
                                tint = if (isStreaming || canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = "${state.chatRuntime.retrievalModeLabel} • ${state.selectedModel?.displayName ?: "sem modelo"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp)
            )
        }
    }

    if (showModelPicker) {
        ModalBottomSheet(onDismissRequest = { showModelPicker = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Selecionar modelo",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        showModelPicker = false
                        onOpenProviders()
                    }) {
                        Text("Gerenciar")
                    }
                }
                Text(
                    text = "A troca de modelo não altera os embeddings ou índices do notebook.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                if (state.enabledChatModels.isEmpty()) {
                    Text(
                        text = "Nenhum modelo habilitado. Cadastre um provedor e busque ou adicione modelos manualmente.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Button(
                        onClick = {
                            showModelPicker = false
                            onOpenProviders()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Configurar Provedores e Modelos")
                    }
                } else {
                    state.enabledChatModels.forEach { model ->
                        val providerName = state.providersById[model.providerId]?.name ?: "Provedor"
                        val isSelected = model.id == state.selectedModel?.id
                        ModelPickerRow(
                            model = model,
                            providerName = providerName,
                            isSelected = isSelected,
                            onClick = {
                                onSelectModel(model.id)
                                showModelPicker = false
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (inspectingCitation != null) {
        val cit = inspectingCitation!!
        ModalBottomSheet(onDismissRequest = { inspectingCitation = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(cit.sourceName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = buildString {
                        append("Página ${cit.pageNumber}")
                        if (cit.sectionTitle.isNotBlank()) append(" • ${cit.sectionTitle}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = cit.excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Button(
                    onClick = {
                        inspectingCitation = null
                        onOpenSourceAtCitation(cit.sourceId, cit.pageNumber, cit.chunkId)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abrir na fonte")
                    Spacer(Modifier.width(6.dp))
                    Icon(FolioIcons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    model: ModelEntity,
    providerName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "$providerName • ctx ${model.contextWindow} • out ${model.maxOutputTokens}" +
                    if (model.supportsTools) " • tools" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(FolioIcons.Rounded.Check, contentDescription = "Selecionado", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantMessageBlock(
    message: MessageEntity,
    citations: List<CitationEntity>,
    showRegenerate: Boolean,
    onCitationClick: (CitationEntity) -> Unit,
    onPinClick: () -> Unit,
    onCopyClick: () -> Unit,
    onRegenerateClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!message.modelDisplayName.isNullOrBlank() || message.isPartial) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!message.modelDisplayName.isNullOrBlank()) {
                    Text(
                        text = message.modelDisplayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (message.isPartial) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.testTag("partial_response_badge")
                    ) {
                        Text(
                            text = "Resposta parcial",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        MarkdownLiteText(text = message.content)

        if (citations.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                citations.forEachIndexed { idx, cit ->
                    AssistChip(
                        onClick = { onCitationClick(cit) },
                        label = {
                            Text(
                                text = "[${idx + 1}] ${cit.sourceName.take(18)} p.${cit.pageNumber}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPinClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (message.isPinned) FolioIcons.Rounded.PushPin else FolioIcons.Outlined.PushPin,
                    contentDescription = "Fixar como nota",
                    tint = if (message.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onCopyClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = FolioIcons.Outlined.ContentCopy,
                    contentDescription = "Copiar resposta",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (showRegenerate) {
                IconButton(onClick = onRegenerateClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = FolioIcons.Rounded.Refresh,
                        contentDescription = "Regenerar resposta",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (message.contextTokensUsed > 0) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${message.contextTokensUsed} tok. entrada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChatWelcomeHeader(
    notebookTitle: String,
    notebookIconKey: String,
    sourceCount: Int,
    selectedCount: Int,
    hasModel: Boolean,
    onOpenProviders: () -> Unit,
    onOpenSources: () -> Unit,
    onStarterClick: (String) -> Unit
) {
    val iconSpec = remember(notebookIconKey) { NotebookIcons.resolve(notebookIconKey) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(iconSpec.icon, contentDescription = null, tint = iconSpec.tint, modifier = Modifier.size(40.dp))
        Text(
            text = notebookTitle,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "$sourceCount fonte(s) importada(s) • $selectedCount selecionada(s) para o chat",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!hasModel) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Configure um provedor de IA", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Cadastre qualquer endpoint compatível com OpenAI Chat Completions (OpenAI, OpenRouter, Groq, DeepSeek, Gemini, Ollama ou personalizado) para iniciar o chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onOpenProviders, shape = CircleShape) {
                        Text("Adicionar Provedor e Modelos")
                    }
                }
            }
        } else if (sourceCount == 0) {
            OutlinedButton(onClick = onOpenSources, shape = CircleShape) {
                Text("Importar primeira fonte")
            }
        } else {
            val starters = listOf(
                "Faça um resumo dos principais pontos das fontes selecionadas.",
                "Quais são os conceitos-chave e definições apresentados?",
                "Liste possíveis lacunas ou perguntas em aberto nos documentos."
            )
            starters.forEach { prompt ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onStarterClick(prompt) }
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }
}
