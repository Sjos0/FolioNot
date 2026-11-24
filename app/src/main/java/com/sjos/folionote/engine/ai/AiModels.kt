package com.sjos.folionote.engine.ai

import com.sjos.folionote.data.local.ProviderType

data class ChatMessagePayload(
    val role: String,
    val content: String?,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null
)

data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class ToolSchemaPayload(
    val name: String,
    val description: String,
    val parametersSchemaJson: String
)

data class ChatCompletionRequest(
    val messages: List<ChatMessagePayload>,
    val tools: List<ToolSchemaPayload> = emptyList(),
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val maxOutputTokens: Int = 2048,
    val stream: Boolean = true
)

sealed interface ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent
    data class ToolCallsReady(val calls: List<ToolCallRequest>) : ChatStreamEvent
    data class Completed(
        val finishReason: String? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null
    ) : ChatStreamEvent
    data class Error(val message: String, val httpCode: Int? = null) : ChatStreamEvent
}

data class DiscoveredModel(
    val modelId: String,
    val displayName: String,
    val ownedBy: String? = null,
    val contextLengthHint: Int? = null
)

data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val discoveredCount: Int? = null
)

data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val chatPath: String,
    val modelsPath: String,
    val embeddingsPath: String,
    val authHeaderName: String,
    val authPrefix: String,
    val providerType: ProviderType,
    val notes: String
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            notes = "Padrão oficial OpenAI"
        ),
        ProviderPreset(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            notes = "Agregador multi-modelo"
        ),
        ProviderPreset(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            notes = "Inferência rápida OpenAI-compatible"
        ),
        ProviderPreset(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            notes = "API DeepSeek compatível com OpenAI"
        ),
        ProviderPreset(
            id = "mistral",
            name = "Mistral AI",
            baseUrl = "https://api.mistral.ai/v1",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            notes = "Endpoints Mistral"
        ),
        ProviderPreset(
            id = "together",
            name = "Together AI",
            baseUrl = "https://api.together.xyz/v1",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            notes = "Modelos abertos via OpenAI-compatible"
        ),
        ProviderPreset(
            id = "gemini_openai",
            name = "Google Gemini (OpenAI Compat)",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.GEMINI_OPENAI_COMPAT,
            notes = "Endpoint OpenAI-compatible do Gemini para chat e embeddings"
        ),
        ProviderPreset(
            id = "gemini_embedding_native",
            name = "Gemini Embedding 2 (Nativo)",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            chatPath = "/openai/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/models/{model}:batchEmbedContents",
            authHeaderName = "x-goog-api-key",
            authPrefix = "",
            providerType = ProviderType.GEMINI_NATIVE_EMBEDDING,
            notes = "API nativa Gemini :embedContent / :batchEmbedContents (taskType + dimensões)"
        ),
        ProviderPreset(
            id = "ollama_local",
            name = "Ollama / Servidor Local",
            baseUrl = "http://10.0.2.2:11434/v1",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.OLLAMA_LOCAL,
            notes = "Servidor local (10.0.2.2 para emulador ou localhost)"
        ),
        ProviderPreset(
            id = "custom",
            name = "Personalizado",
            baseUrl = "https://",
            chatPath = "/chat/completions",
            modelsPath = "/models",
            embeddingsPath = "/embeddings",
            authHeaderName = "Authorization",
            authPrefix = "Bearer",
            providerType = ProviderType.CUSTOM,
            notes = "Qualquer API compatível com OpenAI"
        )
    )
}
