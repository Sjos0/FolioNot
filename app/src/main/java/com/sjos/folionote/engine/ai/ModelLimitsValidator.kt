package com.sjos.folionote.engine.ai

data class ModelLimitsValidation(
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val contextWindowError: String? = null,
    val maxOutputTokensError: String? = null
) {
    val isValid: Boolean
        get() = contextWindow != null &&
            maxOutputTokens != null &&
            contextWindowError == null &&
            maxOutputTokensError == null
}

/**
 * Validates user-supplied Context Window and Max Output Tokens without silent coercion or fallback.
 *
 * Bounds rationale:
 * - MIN_CONTEXT_WINDOW = 512: smallest practical budget to fit system instructions, query, and output reservation.
 * - MAX_CONTEXT_WINDOW = 2_000_000: covers up to 2M-token frontier models while preventing 32-bit Int overflow
 *   in downstream token/character budget calculations (`tokens * 3` etc.) and OOM prompt assembly.
 * - MIN_MAX_OUTPUT_TOKENS = 64: smallest useful completion budget.
 * - maxOutputTokens <= contextWindow: output reservation cannot exceed the model's total context window.
 */
object ModelLimitsValidator {
    const val MIN_CONTEXT_WINDOW = 512
    const val MAX_CONTEXT_WINDOW = 2_000_000
    const val MIN_MAX_OUTPUT_TOKENS = 64
    const val MAX_STUDIO_CONTEXT_CHARS = 1_200_000

    fun validate(contextWindowInput: String, maxOutputTokensInput: String): ModelLimitsValidation {
        val ctxTrimmed = contextWindowInput.trim()
        val maxOutTrimmed = maxOutputTokensInput.trim()

        val ctxLong = ctxTrimmed.toLongOrNull()
        var ctxValue: Int? = null
        val ctxError = when {
            ctxTrimmed.isEmpty() -> "Informe o Context Window."
            ctxLong == null -> "Use apenas dígitos entre $MIN_CONTEXT_WINDOW e $MAX_CONTEXT_WINDOW."
            ctxLong < MIN_CONTEXT_WINDOW -> "Context Window mínimo é $MIN_CONTEXT_WINDOW tokens."
            ctxLong > MAX_CONTEXT_WINDOW -> "Context Window máximo é $MAX_CONTEXT_WINDOW tokens."
            else -> {
                ctxValue = ctxLong.toInt()
                null
            }
        }

        val maxOutLong = maxOutTrimmed.toLongOrNull()
        var maxOutValue: Int? = null
        val maxOutError = when {
            maxOutTrimmed.isEmpty() -> "Informe o Max Output Tokens."
            maxOutLong == null -> "Use apenas dígitos a partir de $MIN_MAX_OUTPUT_TOKENS."
            maxOutLong < MIN_MAX_OUTPUT_TOKENS -> "Max Output mínimo é $MIN_MAX_OUTPUT_TOKENS tokens."
            ctxValue != null && maxOutLong > ctxValue ->
                "Max Output não pode ser maior que Context Window ($ctxValue)."
            ctxValue == null && maxOutLong > MAX_CONTEXT_WINDOW ->
                "Max Output não pode exceder $MAX_CONTEXT_WINDOW tokens."
            else -> {
                maxOutValue = maxOutLong.toInt()
                null
            }
        }

        return ModelLimitsValidation(
            contextWindow = ctxValue,
            maxOutputTokens = maxOutValue,
            contextWindowError = ctxError,
            maxOutputTokensError = maxOutError
        )
    }

    fun computeStudioBudgetChars(contextWindow: Int, maxOutputTokens: Int): Int {
        val safeCtx = contextWindow.toLong().coerceIn(MIN_CONTEXT_WINDOW.toLong(), MAX_CONTEXT_WINDOW.toLong())
        val safeMaxOut = maxOutputTokens.toLong().coerceIn(MIN_MAX_OUTPUT_TOKENS.toLong(), safeCtx)
        val remainingTokens = (safeCtx - safeMaxOut - 512L).coerceAtLeast(1000L)
        return (remainingTokens * 3L)
            .coerceIn(3000L, MAX_STUDIO_CONTEXT_CHARS.toLong())
            .toInt()
    }
}
