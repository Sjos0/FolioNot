package com.sjos.folionote.ui.notebook

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sjos.folionote.data.local.ConversationEntity
import com.sjos.folionote.data.local.ConversationGoalPreset
import com.sjos.folionote.data.local.ResponseLengthPreset
import com.sjos.folionote.ui.theme.FolioIcons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversationConfigSheet(
    conversation: ConversationEntity,
    onDismiss: () -> Unit,
    onSave: (goalPreset: String, responseLength: String, customPrompt: String) -> Unit
) {
    var selectedGoal by remember(conversation.id) { mutableStateOf(conversation.systemRolePreset) }
    var selectedLength by remember(conversation.id) { mutableStateOf(conversation.responseLength) }
    var customPrompt by remember(conversation.id) { mutableStateOf(conversation.customSystemPrompt) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Configurar as conversas",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(FolioIcons.Rounded.Close, contentDescription = "Fechar")
                }
            }

            Text(
                text = "Personalize o estilo, papel e tamanho das respostas para este notebook sem alterar os índices das fontes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Defina sua meta, estilo ou papel na conversa",
                style = MaterialTheme.typography.titleSmall
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val goals = listOf(
                    ConversationGoalPreset.DEFAULT.name to "Padrão",
                    ConversationGoalPreset.STUDY_GUIDE.name to "Guia de aprendizado",
                    ConversationGoalPreset.ANALYTICAL.name to "Analítico",
                    ConversationGoalPreset.CUSTOM.name to "Personalizado"
                )
                goals.forEach { (key, label) ->
                    val active = selectedGoal == key
                    FilterChip(
                        selected = active,
                        onClick = { selectedGoal = key },
                        label = { Text(label) },
                        leadingIcon = if (active) {
                            { Icon(FolioIcons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            AnimatedVisibility(visible = selectedGoal == ConversationGoalPreset.CUSTOM.name) {
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    label = { Text("Instruções personalizadas de sistema") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = "Escolha o tamanho da resposta",
                style = MaterialTheme.typography.titleSmall
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val lengths = listOf(
                    ResponseLengthPreset.DEFAULT.name to "Padrão",
                    ResponseLengthPreset.LONG.name to "Mais longa",
                    ResponseLengthPreset.SHORT.name to "Mais curta"
                )
                lengths.forEach { (key, label) ->
                    val active = selectedLength == key
                    FilterChip(
                        selected = active,
                        onClick = { selectedLength = key },
                        label = { Text(label) },
                        leadingIcon = if (active) {
                            { Icon(FolioIcons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        onSave(selectedGoal, selectedLength, customPrompt)
                        onDismiss()
                    },
                    shape = CircleShape
                ) {
                    Text("Salvar")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
