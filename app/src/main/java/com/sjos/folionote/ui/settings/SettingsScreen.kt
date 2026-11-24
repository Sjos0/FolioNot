package com.sjos.folionote.ui.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sjos.folionote.core.preferences.DriveConflictPolicy
import com.sjos.folionote.core.preferences.OcrProviderType
import com.sjos.folionote.core.preferences.TranscriptionProviderType
import com.sjos.folionote.engine.indexing.GeminiEmbeddingManager
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.theme.ThemePreference

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenDriveSync: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val settings = state.settings

    val googleConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (activity != null) {
            viewModel.completeGoogleConsent(activity, result.data)
        }
    }

    var chunkSizeText by remember(settings.chunkSizeChars) {
        mutableStateOf(settings.chunkSizeChars.toString())
    }
    var overlapText by remember(settings.chunkOverlapChars) {
        mutableStateOf(settings.chunkOverlapChars.toString())
    }
    var topKText by remember(settings.ragTopK) {
        mutableStateOf(settings.ragTopK.toString())
    }

    // Gemini Embedding 2 local form state
    var geminiKeyInput by remember { mutableStateOf("") }
    var geminiKeyVisible by remember { mutableStateOf(false) }
    var geminiModelId by remember(settings.geminiEmbeddingModelId) {
        mutableStateOf(settings.geminiEmbeddingModelId)
    }
    var geminiDimensions by remember(settings.geminiEmbeddingDimensions) {
        mutableStateOf(settings.geminiEmbeddingDimensions)
    }

    // Decoupled OCR local form state
    var ocrType by remember(settings.ocrProviderType) { mutableStateOf(settings.ocrProviderType) }
    var ocrEndpoint by remember(settings.ocrEndpointUrl) { mutableStateOf(settings.ocrEndpointUrl) }
    var ocrModel by remember(settings.ocrModelId) { mutableStateOf(settings.ocrModelId) }
    var ocrKeyInput by remember { mutableStateOf("") }
    var ocrKeyVisible by remember { mutableStateOf(false) }

    // Decoupled Transcription local form state
    var transType by remember(settings.transcriptionProviderType) { mutableStateOf(settings.transcriptionProviderType) }
    var transEndpoint by remember(settings.transcriptionEndpointUrl) { mutableStateOf(settings.transcriptionEndpointUrl) }
    var transModel by remember(settings.transcriptionModelId) { mutableStateOf(settings.transcriptionModelId) }
    var transLanguage by remember(settings.transcriptionLanguage) { mutableStateOf(settings.transcriptionLanguage) }
    var transKeyInput by remember { mutableStateOf("") }
    var transKeyVisible by remember { mutableStateOf(false) }

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
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                    Text("Configurações", style = MaterialTheme.typography.titleLarge)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 1. Gemini Embedding 2 (Dedicated Key ONLY)
                    item {
                        SectionTitle("Embeddings — Gemini Embedding 2")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Key,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Gemini Embedding 2 (chave própria)",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = if (settings.isGeminiEmbeddingActive) {
                                                "Ativo • ${settings.geminiEmbeddingModelId} (${settings.geminiEmbeddingDimensions}d)"
                                            } else if (settings.hasGeminiEmbeddingKey) {
                                                "Chave salva (${viewModel.getMaskedGeminiKey(settings)}) • Desativado"
                                            } else {
                                                "Sem chave configurada • Busca lexical BM25 offline"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = settings.geminiEmbeddingEnabled,
                                        enabled = settings.hasGeminiEmbeddingKey,
                                        onCheckedChange = viewModel::toggleGeminiEnabled,
                                        modifier = Modifier.testTag("gemini_embedding_switch")
                                    )
                                }

                                Text(
                                    text = "Chave dedicada exclusivamente para vetorização semântica no Google AI Studio. Ela não configura nem altera provedores de chat, não é sincronizada na nuvem e não é incluída em backups.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                OutlinedTextField(
                                    value = geminiKeyInput,
                                    onValueChange = { geminiKeyInput = it },
                                    label = {
                                        Text(
                                            if (settings.hasGeminiEmbeddingKey) {
                                                "Nova chave AI Studio (atual: ${viewModel.getMaskedGeminiKey(settings)})"
                                            } else {
                                                "Chave da API do Google AI Studio"
                                            }
                                        )
                                    },
                                    placeholder = { Text("AIza...") },
                                    visualTransformation = if (geminiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { geminiKeyVisible = !geminiKeyVisible }) {
                                            Icon(
                                                imageVector = if (geminiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                contentDescription = if (geminiKeyVisible) "Ocultar chave" else "Mostrar chave"
                                            )
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("gemini_embedding_key_input")
                                )

                                Text("Modelo de embedding", style = MaterialTheme.typography.labelMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GeminiEmbeddingManager.SUPPORTED_MODELS.forEach { model ->
                                        FilterChip(
                                            selected = geminiModelId == model,
                                            onClick = {
                                                geminiModelId = model
                                                if (settings.hasGeminiEmbeddingKey) {
                                                    viewModel.updateGeminiModelAndDimensions(model, geminiDimensions)
                                                }
                                            },
                                            label = { Text(model) }
                                        )
                                    }
                                }

                                Text("Dimensões do vetor", style = MaterialTheme.typography.labelMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GeminiEmbeddingManager.SUPPORTED_DIMENSIONS.forEach { dims ->
                                        FilterChip(
                                            selected = geminiDimensions == dims,
                                            onClick = {
                                                geminiDimensions = dims
                                                if (settings.hasGeminiEmbeddingKey) {
                                                    viewModel.updateGeminiModelAndDimensions(geminiModelId, dims)
                                                }
                                            },
                                            label = { Text("${dims}d") }
                                        )
                                    }
                                }

                                state.geminiValidationResult?.let { (ok, msg) ->
                                    Surface(
                                        color = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                                contentDescription = null,
                                                tint = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = msg,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            viewModel.saveAndValidateGeminiKey(
                                                rawKey = geminiKeyInput,
                                                modelId = geminiModelId,
                                                dimensions = geminiDimensions,
                                                enabled = true
                                            )
                                            geminiKeyInput = ""
                                        },
                                        enabled = !state.isTestingGemini && geminiKeyInput.isNotBlank(),
                                        shape = CircleShape,
                                        modifier = Modifier.testTag("gemini_validate_save_button")
                                    ) {
                                        if (state.isTestingGemini) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text("Validar e salvar")
                                    }

                                    if (settings.hasGeminiEmbeddingKey) {
                                        OutlinedButton(
                                            onClick = viewModel::testGeminiConnection,
                                            enabled = !state.isTestingGemini,
                                            shape = CircleShape,
                                            modifier = Modifier.testTag("gemini_test_connection_button")
                                        ) {
                                            Text("Testar conexão")
                                        }

                                        TextButton(
                                            onClick = viewModel::removeGeminiKey,
                                            enabled = !state.isTestingGemini,
                                            modifier = Modifier.testTag("gemini_remove_key_button")
                                        ) {
                                            Text("Remover chave", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Google Account & Google Drive
                    item {
                        SectionTitle("Conta Google e Google Drive")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (settings.isGoogleDriveConnected) {
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
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = settings.driveAccountEmail.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = viewModel::refreshGoogleProfile,
                                            enabled = !state.isConnectingGoogle
                                        ) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar perfil")
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Sincronização automática", style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                text = "Sincroniza em segundo plano quando houver conexão.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = settings.driveSyncEnabled,
                                            onCheckedChange = viewModel::setDriveSyncEnabled,
                                            modifier = Modifier.testTag("drive_autosync_switch")
                                        )
                                    }

                                    Text("Política de conflitos", style = MaterialTheme.typography.labelMedium)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        DriveConflictPolicy.entries.forEach { policy ->
                                            FilterChip(
                                                selected = settings.driveConflictPolicy == policy,
                                                onClick = { viewModel.setDriveConflictPolicy(policy) },
                                                label = { Text(policy.label) }
                                            )
                                        }
                                    }

                                    if (settings.driveLastSyncAt != null || settings.driveLastBackupAt != null) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            settings.driveLastSyncAt?.let { ts ->
                                                Text(
                                                    text = "Última sync: ${FormatUtils.formatDate(ts)} (${settings.driveLastSyncStatus})",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            settings.driveLastBackupAt?.let { ts ->
                                                Text(
                                                    text = "Último backup: ${FormatUtils.formatDate(ts)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    if (state.pendingConflictCount > 0) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "${state.pendingConflictCount} conflito(s) aguardando decisão explícita.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                    }

                                    if (settings.driveNeedsReauth) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "A autorização do Google Drive expirou ou foi revogada. Reconecte sua conta para retomar a sincronização.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                                Button(
                                                    onClick = {
                                                        if (activity != null) {
                                                            viewModel.connectGoogleDrive(activity, switchAccount = false) { req ->
                                                                googleConsentLauncher.launch(req)
                                                            }
                                                        }
                                                    },
                                                    enabled = !state.isConnectingGoogle && activity != null,
                                                    shape = CircleShape,
                                                    modifier = Modifier.testTag("reconnect_google_drive_button")
                                                ) {
                                                    Text("Reconectar Google Drive")
                                                }
                                            }
                                        }
                                    }

                                    state.driveRuntime.lastError?.let { err ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = err,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                    }

                                    if (state.driveRuntime.isBusy) {
                                        LinearProgressIndicator(
                                            progress = { state.driveRuntime.progress.coerceIn(0.05f, 1f) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = viewModel::syncDriveNow,
                                            enabled = !state.driveRuntime.isBusy,
                                            shape = CircleShape,
                                            modifier = Modifier.testTag("settings_drive_sync_now")
                                        ) {
                                            Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Sincronizar agora")
                                        }
                                        OutlinedButton(
                                            onClick = onOpenDriveSync,
                                            shape = CircleShape,
                                            modifier = Modifier.testTag("settings_open_drive_manager")
                                        ) {
                                            Icon(Icons.Outlined.CloudSync, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Gerenciar backups e sync")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                if (activity != null) {
                                                    viewModel.connectGoogleDrive(activity, switchAccount = true) { req ->
                                                        googleConsentLauncher.launch(req)
                                                    }
                                                }
                                            },
                                            enabled = !state.isConnectingGoogle && activity != null,
                                            shape = CircleShape,
                                            modifier = Modifier.testTag("switch_google_account_button")
                                        ) {
                                            Text("Trocar conta")
                                        }
                                        TextButton(
                                            onClick = viewModel::disconnectGoogleAccount,
                                            modifier = Modifier.testTag("disconnect_google_account_button")
                                        ) {
                                            Text("Desconectar", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Conecte sua conta Google com um toque para salvar backups privados em ZIP e sincronizar notebooks no seu Google Drive (AppData). Chaves de API, tokens e senhas nunca são incluídos.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                if (activity != null) {
                                                    viewModel.connectGoogleDrive(activity, switchAccount = false) { req ->
                                                        googleConsentLauncher.launch(req)
                                                    }
                                                }
                                            },
                                            enabled = !state.isConnectingGoogle && activity != null,
                                            shape = CircleShape,
                                            modifier = Modifier.testTag("connect_google_account_button")
                                        ) {
                                            if (state.isConnectingGoogle) {
                                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text("Conectar Google Drive")
                                        }

                                        OutlinedButton(
                                            onClick = onOpenDriveSync,
                                            shape = CircleShape
                                        ) {
                                            Text("Tela de Backups e Sync")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Decoupled OCR & External Transcription
                    item {
                        SectionTitle("OCR e Transcrição Externa")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.DocumentScanner, null, tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Motor de OCR (PDF digitalizado e Imagens)", style = MaterialTheme.typography.titleSmall)
                                }
                                Text(
                                    text = "Nenhum modelo local de IA é embutido no app. Configure um endpoint REST de OCR ou mantenha desativado para processamento local apenas de texto nativo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OcrProviderType.entries.forEach { t ->
                                        FilterChip(
                                            selected = ocrType == t,
                                            onClick = {
                                                ocrType = t
                                                if (ocrEndpoint.isBlank() || OcrProviderType.entries.any { it.defaultEndpoint == ocrEndpoint }) {
                                                    ocrEndpoint = t.defaultEndpoint
                                                }
                                            },
                                            label = { Text(t.label) }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = ocrEndpoint,
                                    onValueChange = { ocrEndpoint = it },
                                    label = { Text("Endpoint HTTP do OCR") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (ocrType == OcrProviderType.OPENAI_VISION_COMPAT) {
                                    OutlinedTextField(
                                        value = ocrModel,
                                        onValueChange = { ocrModel = it },
                                        label = { Text("Modelo de visão (ex.: gpt-4o-mini)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                OutlinedTextField(
                                    value = ocrKeyInput,
                                    onValueChange = { ocrKeyInput = it },
                                    label = {
                                        val masked = viewModel.getMaskedOcrKey(settings)
                                        Text(if (masked.isNotBlank()) "Chave API OCR (salva: $masked)" else "Chave API OCR")
                                    },
                                    visualTransformation = if (ocrKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { ocrKeyVisible = !ocrKeyVisible }) {
                                            Icon(if (ocrKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "Mostrar/ocultar chave OCR")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            viewModel.saveOcrConfig(
                                                providerType = ocrType,
                                                endpointUrl = ocrEndpoint,
                                                modelId = ocrModel,
                                                rawKey = ocrKeyInput,
                                                keepExisting = ocrKeyInput.isBlank()
                                            )
                                            ocrKeyInput = ""
                                        },
                                        shape = CircleShape
                                    ) {
                                        Text("Salvar OCR")
                                    }
                                    if (settings.ocrEncryptedApiKey.isNotBlank()) {
                                        TextButton(onClick = viewModel::clearOcrKey) {
                                            Text("Remover chave OCR", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Transcrição Externa (Áudio e Vídeo)", style = MaterialTheme.typography.titleSmall)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TranscriptionProviderType.entries.forEach { t ->
                                        FilterChip(
                                            selected = transType == t,
                                            onClick = {
                                                transType = t
                                                if (transEndpoint.isBlank() || TranscriptionProviderType.entries.any { it.defaultEndpoint == transEndpoint }) {
                                                    transEndpoint = t.defaultEndpoint
                                                }
                                            },
                                            label = { Text(t.label) }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = transEndpoint,
                                    onValueChange = { transEndpoint = it },
                                    label = { Text("Endpoint HTTP de transcrição") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = transModel,
                                        onValueChange = { transModel = it },
                                        label = { Text("Modelo") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = transLanguage,
                                        onValueChange = { transLanguage = it },
                                        label = { Text("Idioma (ex.: pt)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.6f)
                                    )
                                }
                                OutlinedTextField(
                                    value = transKeyInput,
                                    onValueChange = { transKeyInput = it },
                                    label = {
                                        val masked = viewModel.getMaskedTranscriptionKey(settings)
                                        Text(if (masked.isNotBlank()) "Chave API Transcrição (salva: $masked)" else "Chave API Transcrição")
                                    },
                                    visualTransformation = if (transKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { transKeyVisible = !transKeyVisible }) {
                                            Icon(if (transKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "Mostrar/ocultar chave transcrição")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            viewModel.saveTranscriptionConfig(
                                                providerType = transType,
                                                endpointUrl = transEndpoint,
                                                modelId = transModel,
                                                language = transLanguage,
                                                rawKey = transKeyInput,
                                                keepExisting = transKeyInput.isBlank()
                                            )
                                            transKeyInput = ""
                                        },
                                        shape = CircleShape
                                    ) {
                                        Text("Salvar Transcrição")
                                    }
                                    if (settings.transcriptionEncryptedApiKey.isNotBlank()) {
                                        TextButton(onClick = viewModel::clearTranscriptionKey) {
                                            Text("Remover chave", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Theme
                    item {
                        SectionTitle("Aparência")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                val options = listOf(
                                    ThemePreference.DARK to "Modo escuro",
                                    ThemePreference.LIGHT to "Modo claro",
                                    ThemePreference.SYSTEM to "Seguir dispositivo"
                                )
                                options.forEachIndexed { idx, (pref, label) ->
                                    if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.setTheme(pref) }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = settings.themePreference == pref,
                                            onClick = { viewModel.setTheme(pref) }
                                        )
                                        Text(label, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }

                    // 5. Document Engine & RAG
                    item {
                        SectionTitle("Motor de Documentos e RAG")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = chunkSizeText,
                                        onValueChange = { chunkSizeText = it.filter { c -> c.isDigit() } },
                                        label = { Text("Chunk (chars)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = overlapText,
                                        onValueChange = { overlapText = it.filter { c -> c.isDigit() } },
                                        label = { Text("Sobreposição") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = topKText,
                                        onValueChange = { topKText = it.filter { c -> c.isDigit() } },
                                        label = { Text("Top-K") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(0.8f)
                                    )
                                }
                                Button(
                                    onClick = {
                                        viewModel.updateChunkingConfig(
                                            chunkSize = chunkSizeText.toIntOrNull() ?: 900,
                                            overlap = overlapText.toIntOrNull() ?: 160,
                                            topK = topKText.toIntOrNull() ?: 6
                                        )
                                    },
                                    shape = CircleShape
                                ) {
                                    Text("Aplicar parâmetros")
                                }
                            }
                        }
                    }

                    // 6. Local Storage & Privacy
                    item {
                        SectionTitle("Armazenamento Local e Privacidade")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Notebooks locais: ${state.notebookCount}")
                                Text("Provedores: ${state.providerCount} • Modelos: ${state.modelCount}")
                                Text("Espaço usado por fontes: ${FormatUtils.formatBytes(state.storageStats.usedBySourcesBytes)}")
                                Text("Espaço livre no dispositivo: ${FormatUtils.formatBytes(state.storageStats.deviceAvailableBytes)}")
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Chaves de API e tokens OAuth são criptografados com Android Keystore (AES-256-GCM), excluídos de backups na nuvem e nunca gravados em logs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = onOpenProviders, shape = CircleShape) {
                                        Text("Gerenciar Provedores")
                                    }
                                    Button(
                                        onClick = viewModel::clearAllApiCredentials,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        ),
                                        shape = CircleShape
                                    ) {
                                        Text("Limpar chaves")
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
fun GoogleAvatar(
    photoUrl: String?,
    displayName: String?,
    email: String,
    modifier: Modifier = Modifier.size(48.dp)
) {
    val initial = (displayName?.firstOrNull() ?: email.firstOrNull() ?: 'G').uppercaseChar().toString()
    if (!photoUrl.isNullOrBlank()) {
        AsyncImage(
            model = photoUrl,
            contentDescription = "Foto da conta Google",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
