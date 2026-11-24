package com.sjos.folionote.ui.settings

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.theme.ThemePreference

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var chunkSizeText by remember(state.settings.chunkSizeChars) {
        mutableStateOf(state.settings.chunkSizeChars.toString())
    }
    var overlapText by remember(state.settings.chunkOverlapChars) {
        mutableStateOf(state.settings.chunkOverlapChars.toString())
    }
    var topKText by remember(state.settings.ragTopK) {
        mutableStateOf(state.settings.ragTopK.toString())
    }

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
                    Text("Configurações", style = MaterialTheme.typography.titleLarge)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text("Aparência", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
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
                                            selected = state.settings.themePreference == pref,
                                            onClick = { viewModel.setTheme(pref) }
                                        )
                                        Text(label, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("Motor de Documentos e RAG", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
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

                    item {
                        Text("Armazenamento Local e Privacidade", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
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
                                    text = "Chaves de API são criptografadas com Android Keystore (AES-256-GCM), excluídas de backups na nuvem e nunca gravadas em logs.",
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
                }
            }
        }
    }
}
