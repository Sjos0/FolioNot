package com.sjos.folionote

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sjos.folionote.ui.components.DestructiveConfirmDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issues #2 (confirmação de ações destrutivas) e #5 (verificações automatizadas de acessibilidade).
 * Validações de runtime em dispositivo continuam no QA descrito em docs/qa/ux-runtime-qa.md.
 */
@RunWith(RobolectricTestRunner::class)
class UxSafetyComposeTests {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
        composeRule.setContent {
            DestructiveConfirmDialog(
                title = "Excluir histórico desta conversa?",
                message = "Esta ação não pode ser desfeita.",
                confirmLabel = "Excluir histórico",
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                confirmTag = "confirm_action"
            )
        }
    }

    @Test
    fun `destructive dialog shows what will be removed and that it cannot be undone`() {
        setDialog(onConfirm = {}, onDismiss = {})
        composeRule.onNodeWithText("Excluir histórico desta conversa?").assertIsDisplayed()
        composeRule.onNodeWithText("Esta ação não pode ser desfeita.").assertIsDisplayed()
    }

    @Test
    fun `cancel preserves data and never runs the destructive action`() {
        var confirmed = 0
        var dismissed = 0
        setDialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })

        composeRule.onNodeWithText("Cancelar").performClick()

        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `confirm runs the destructive action exactly once`() {
        var confirmed = 0
        var dismissed = 0
        setDialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })

        composeRule.onNodeWithTag("confirm_action").performClick()

        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `dialog buttons meet the 48dp Android touch target`() {
        setDialog(onConfirm = {}, onDismiss = {})
        val minPx = with(composeRule.density) { 48.dp.toPx() }
        listOf("Cancelar").forEach { label ->
            val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue("$label altura ${bounds.height}px < ${minPx}px", bounds.height >= minPx)
            assertTrue("$label largura ${bounds.width}px < ${minPx}px", bounds.width >= minPx)
        }
        val confirm = composeRule.onNodeWithTag("confirm_action").fetchSemanticsNode().boundsInRoot
        assertTrue("confirmar altura ${confirm.height}px < ${minPx}px", confirm.height >= minPx)
    }
}
