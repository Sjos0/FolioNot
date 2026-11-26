package com.sjos.folionote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.MessageRole
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.repository.ChatRuntimeState
import com.sjos.folionote.ui.notebook.ChatTabContent
import com.sjos.folionote.ui.notebook.NotebookUiState
import com.sjos.folionote.ui.providers.ModelEditorSheet
import com.sjos.folionote.ui.theme.FolioNoteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatUiAndModelValidationTests {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `chat layout keeps topbar and model selector visible under compact ime height and separates error banner from partial message`() {
        val provider = ProviderEntity("p1", "OpenAI", "https://api.openai.com/v1")
        val model = ModelEntity("m1", "p1", "gpt-4o", "GPT-4o")
        val notebook = NotebookEntity("nb1", "Caderno Direito")
        val partialMessage = MessageEntity(
            id = "msg_partial",
            conversationId = "c1",
            role = MessageRole.ASSISTANT.name,
            content = "Trecho parcial salvo sem sufixo de erro",
            modelDisplayName = model.displayName,
            isPartial = true,
            isError = false,
            orderIndex = 1
        )

        var retryClicked = false
        var uiState by mutableStateOf(
            NotebookUiState(
                notebook = notebook,
                providersById = mapOf(provider.id to provider),
                selectedModel = model,
                enabledChatModels = listOf(model),
                messages = listOf(partialMessage),
                settings = AppSettings(externalDisclosureAck = true),
                chatRuntime = ChatRuntimeState(
                    isStreaming = false,
                    errorMessage = "HTTP 500: Erro temporário no servidor"
                )
            )
        )

        composeRule.setContent {
            FolioNoteTheme {
                // Simulate phone viewport with keyboard occupying bottom half (380.dp usable height)
                Box(modifier = Modifier.height(380.dp)) {
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("workspace_topbar")
                            ) {
                                Text("Caderno Direito")
                            }
                            ChatTabContent(
                                state = uiState,
                                onSendMessage = {},
                                onStopGeneration = {},
                                onRegenerate = { retryClicked = true },
                                onTogglePin = {},
                                onSelectModel = {},
                                onOpenConfigSheet = {},
                                onOpenSourcesTab = {},
                                onOpenProviders = {},
                                onOpenSourceAtCitation = { _, _, _ -> },
                                onResolveToolApproval = {},
                                onAcknowledgeExternalDisclosure = {},
                                onOpenContextBudget = {},
                                onDismissError = {
                                    uiState = uiState.copy(chatRuntime = uiState.chatRuntime.copy(errorMessage = null))
                                }
                            )
                        }
                    }
                }
            }
        }

        // TopBar and "modelo • provedor" chip remain visible even with reduced IME height
        composeRule.onNodeWithTag("workspace_topbar").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_model_selector").assertIsDisplayed()
        composeRule.onNodeWithText("GPT-4o • OpenAI", substring = true).assertIsDisplayed()

        // Error banner and "Tentar novamente" button appear separated from partial message
        composeRule.onNodeWithTag("chat_error_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_retry_button").assertIsDisplayed()
        composeRule.onNodeWithTag("partial_response_badge").assertIsDisplayed()
        composeRule.onNodeWithText("Gerando resposta...").assertDoesNotExist()

        composeRule.onNodeWithTag("chat_retry_button").performClick()
        assertTrue(retryClicked)
        composeRule.onNodeWithTag("chat_error_banner").assertDoesNotExist()
    }

    @Test
    fun `model editor sheet validates limits inline disables save on invalid inputs and saves valid edit`() {
        val provider = ProviderEntity("p1", "OpenAI", "https://api.openai.com/v1")
        val existingModel = ModelEntity(
            id = "m1",
            providerId = "p1",
            modelId = "gpt-4o",
            displayName = "GPT-4o",
            contextWindow = 8192,
            maxOutputTokens = 2048
        )
        var savedModel: ModelEntity? = null

        composeRule.setContent {
            FolioNoteTheme {
                ModelEditorSheet(
                    initial = existingModel,
                    providers = listOf(provider),
                    onDismiss = {},
                    onSave = { savedModel = it }
                )
            }
        }

        composeRule.onNodeWithTag("save_model_button").assertIsEnabled()

        // Empty contextWindow -> error + disabled save (no silent fallback)
        composeRule.onNodeWithTag("model_context_input").performScrollTo().performTextReplacement("")
        composeRule.onNodeWithTag("model_context_error", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("save_model_button").assertIsNotEnabled()

        // Zero -> error + disabled save
        composeRule.onNodeWithTag("model_context_input").performScrollTo().performTextReplacement("0")
        composeRule.onNodeWithTag("model_context_error", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("save_model_button").assertIsNotEnabled()

        // Negative -> error + disabled save
        composeRule.onNodeWithTag("model_context_input").performScrollTo().performTextReplacement("-100")
        composeRule.onNodeWithTag("model_context_error", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("save_model_button").assertIsNotEnabled()

        // Overflow / too large -> error + disabled save
        composeRule.onNodeWithTag("model_context_input").performScrollTo().performTextReplacement("99999999999")
        composeRule.onNodeWithTag("model_context_error", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("save_model_button").assertIsNotEnabled()

        // maxOutput > contextWindow -> error on maxOutput + disabled save
        composeRule.onNodeWithTag("model_context_input").performScrollTo().performTextReplacement("4096")
        composeRule.onNodeWithTag("model_max_output_input").performScrollTo().performTextReplacement("8192")
        composeRule.onNodeWithTag("model_max_output_error", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("save_model_button").assertIsNotEnabled()

        // Valid update of existing model contextWindow -> enables save and persists exact values
        composeRule.onNodeWithTag("model_context_input").performScrollTo().performTextReplacement("128000")
        composeRule.onNodeWithTag("model_max_output_input").performScrollTo().performTextReplacement("4096")
        composeRule.onNodeWithTag("save_model_button").performScrollTo().assertIsEnabled().performClick()

        assertEquals(128000, savedModel?.contextWindow)
        assertEquals(4096, savedModel?.maxOutputTokens)
    }
}
