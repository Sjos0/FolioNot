package com.sjos.folionote.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sjos.folionote.engine.updater.AvailableReleaseUpdate
import com.sjos.folionote.engine.updater.UpdateUiState
import com.sjos.folionote.ui.components.FormatUtils
import com.sjos.folionote.ui.theme.FolioIcons

@Composable
fun AppUpdateDialog(
    updateState: UpdateUiState,
    installedVersionLabel: String,
    activity: Activity?,
    onStartOrResumeDownload: (AvailableReleaseUpdate) -> Unit,
    onCancelDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onLaunchInstaller: () -> Unit,
    onRefreshAfterExternalReturn: () -> Unit,
    onDismiss: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshAfterExternalReturn()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isDownloading = updateState is UpdateUiState.Downloading ||
        updateState is UpdateUiState.PreparingDownload ||
        updateState is UpdateUiState.VerifyingIntegrity

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) {
                onDismiss()
            }
        },
        icon = {
            val (icon, tint) = when (updateState) {
                is UpdateUiState.UpToDate, is UpdateUiState.Installed ->
                    FolioIcons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
                is UpdateUiState.Failed ->
                    FolioIcons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
                is UpdateUiState.Paused ->
                    FolioIcons.Rounded.PauseCircle to MaterialTheme.colorScheme.tertiary
                is UpdateUiState.ReadyToInstall ->
                    FolioIcons.Outlined.VerifiedUser to MaterialTheme.colorScheme.primary
                is UpdateUiState.AwaitingInstallPermission ->
                    FolioIcons.Outlined.Settings to MaterialTheme.colorScheme.secondary
                else ->
                    FolioIcons.Outlined.SystemUpdate to MaterialTheme.colorScheme.primary
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        },
        title = {
            Text(
                text = when (updateState) {
                    UpdateUiState.Idle, UpdateUiState.Checking -> "Verificando atualizações..."
                    is UpdateUiState.UpToDate -> "Aplicativo atualizado"
                    is UpdateUiState.UpdateAvailable -> "Atualização disponível (${updateState.update.tagName})"
                    is UpdateUiState.PreparingDownload -> "Preparando download..."
                    is UpdateUiState.Downloading -> "Baixando atualização..."
                    is UpdateUiState.Paused -> "Download pausado"
                    is UpdateUiState.VerifyingIntegrity -> "Verificando integridade..."
                    is UpdateUiState.AwaitingInstallPermission -> "Permissão necessária"
                    is UpdateUiState.ReadyToInstall -> "Pronto para instalar"
                    is UpdateUiState.AwaitingSystemInstaller -> "Instalador aberto"
                    is UpdateUiState.Installed -> "Atualização concluída"
                    is UpdateUiState.Cancelled -> "Download cancelado"
                    is UpdateUiState.Failed -> "Não foi possível atualizar"
                },
                modifier = Modifier.testTag("update_dialog_title")
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (updateState) {
                    UpdateUiState.Idle, UpdateUiState.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            Text(
                                text = "Consultando o repositório oficial do FolioNote no GitHub...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    is UpdateUiState.UpToDate -> {
                        Text(
                            text = "Você já está usando a versão mais recente ($installedVersionLabel).",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("update_uptodate_message")
                        )
                    }

                    is UpdateUiState.UpdateAvailable -> {
                        ReleaseMetadataCard(
                            installedVersionLabel = "v${updateState.installedVersionName} (${updateState.installedVersionCode})",
                            update = updateState.update
                        )
                    }

                    is UpdateUiState.PreparingDownload -> {
                        Text(
                            text = "Preparando armazenamento privado seguro para ${updateState.update.manifest.apkFileName}...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    is UpdateUiState.Downloading -> {
                        Text(
                            text = "Versão ${updateState.update.manifest.versionName} • ${updateState.percent}%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("update_download_percent")
                        )
                        LinearProgressIndicator(
                            progress = { (updateState.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${FormatUtils.formatBytes(updateState.bytesDownloaded)} de ${FormatUtils.formatBytes(updateState.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is UpdateUiState.Paused -> {
                        Text(
                            text = updateState.reason,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Progresso preservado: ${FormatUtils.formatBytes(updateState.bytesDownloaded)} de ${FormatUtils.formatBytes(updateState.totalBytes)}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is UpdateUiState.VerifyingIntegrity -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            Text(
                                text = "Validando SHA-256, pacote e certificado de assinatura oficial do APK...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    is UpdateUiState.AwaitingInstallPermission -> {
                        Text(
                            text = if (updateState.isLegacyPreOreo) {
                                "O APK foi validado com sucesso. Para instalar, habilite \"Fontes desconhecidas\" nas configurações de segurança do dispositivo e volte para o FolioNote."
                            } else {
                                "O APK foi validado com sucesso. Para prosseguir, autorize \"Permitir desta fonte\" para o FolioNote nas configurações do Android e retorne ao app."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("update_permission_explanation")
                        )
                    }

                    is UpdateUiState.ReadyToInstall -> {
                        Text(
                            text = "Integridade SHA-256 e assinatura verificadas com sucesso para FolioNote v${updateState.update.manifest.versionName}.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Toque em \"Instalar atualização\" para abrir o instalador nativo do Android.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is UpdateUiState.AwaitingSystemInstaller -> {
                        Text(
                            text = "O instalador do Android foi iniciado para a versão v${updateState.update.manifest.versionName}. Caso tenha cancelado na tela do sistema, toque em instalar novamente.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is UpdateUiState.Installed -> {
                        Text(
                            text = "FolioNote v${updateState.versionName} já está instalado e ativo.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is UpdateUiState.Cancelled -> {
                        Text(
                            text = updateState.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is UpdateUiState.Failed -> {
                        Text(
                            text = updateState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("update_error_message")
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (updateState) {
                is UpdateUiState.UpdateAvailable -> {
                    Button(
                        onClick = { onStartOrResumeDownload(updateState.update) },
                        shape = CircleShape,
                        modifier = Modifier.testTag("update_download_install_button")
                    ) {
                        Text("Baixar e instalar")
                    }
                }
                is UpdateUiState.Paused -> {
                    Button(
                        onClick = { onStartOrResumeDownload(updateState.update) },
                        shape = CircleShape,
                        modifier = Modifier.testTag("update_resume_button")
                    ) {
                        Text("Retomar download")
                    }
                }
                is UpdateUiState.AwaitingInstallPermission -> {
                    Button(
                        onClick = onOpenInstallPermissionSettings,
                        shape = CircleShape,
                        modifier = Modifier.testTag("update_open_permission_settings_button")
                    ) {
                        Text("Abrir configurações")
                    }
                }
                is UpdateUiState.ReadyToInstall, is UpdateUiState.AwaitingSystemInstaller -> {
                    Button(
                        onClick = onLaunchInstaller,
                        enabled = activity != null,
                        shape = CircleShape,
                        modifier = Modifier.testTag("update_launch_installer_button")
                    ) {
                        Text("Instalar atualização")
                    }
                }
                is UpdateUiState.Cancelled -> {
                    val pendingUpdate = updateState.update
                    if (pendingUpdate != null) {
                        Button(
                            onClick = { onStartOrResumeDownload(pendingUpdate) },
                            shape = CircleShape
                        ) {
                            Text("Baixar novamente")
                        }
                    } else {
                        Button(onClick = onDismiss, shape = CircleShape) { Text("Fechar") }
                    }
                }
                is UpdateUiState.Failed -> {
                    Button(
                        onClick = {
                            val pendingUpdate = updateState.update
                            if (pendingUpdate != null) {
                                onStartOrResumeDownload(pendingUpdate)
                            } else {
                                onRetryCheck()
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier.testTag("update_retry_button")
                    ) {
                        Icon(FolioIcons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tentar novamente")
                    }
                }
                is UpdateUiState.Downloading,
                is UpdateUiState.PreparingDownload,
                is UpdateUiState.VerifyingIntegrity,
                UpdateUiState.Checking,
                UpdateUiState.Idle -> Unit
                is UpdateUiState.UpToDate, is UpdateUiState.Installed -> {
                    Button(onClick = onDismiss, shape = CircleShape) {
                        Text("Concluído")
                    }
                }
            }
        },
        dismissButton = {
            when (updateState) {
                is UpdateUiState.Downloading, is UpdateUiState.PreparingDownload -> {
                    OutlinedButton(
                        onClick = onCancelDownload,
                        shape = CircleShape,
                        modifier = Modifier.testTag("update_cancel_download_button")
                    ) {
                        Text("Cancelar download")
                    }
                }
                UpdateUiState.Checking, is UpdateUiState.VerifyingIntegrity -> Unit
                is UpdateUiState.UpToDate, is UpdateUiState.Installed -> Unit
                else -> {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("update_dismiss_button")
                    ) {
                        Text("Fechar")
                    }
                }
            }
        }
    )
}

@Composable
private fun ReleaseMetadataCard(
    installedVersionLabel: String,
    update: AvailableReleaseUpdate
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Nova versão: v${update.manifest.versionName} (${update.manifest.versionCode})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Instalada: $installedVersionLabel • APK: ${FormatUtils.formatBytes(update.manifest.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (update.releaseNotes.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                ) {
                    Text(
                        text = update.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}
