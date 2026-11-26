package com.sjos.folionote.engine.ai

import com.sjos.folionote.core.network.RequestTimeoutSpec
import com.sjos.folionote.core.network.SharedHttpClients
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.ModelEntity
import com.sjos.folionote.data.local.ProviderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

interface AiProviderClient {
    suspend fun testConnection(provider: ProviderEntity): ConnectionTestResult
    suspend fun fetchModels(provider: ProviderEntity): Result<List<DiscoveredModel>>
    fun streamChatCompletion(
        provider: ProviderEntity,
        model: ModelEntity,
        request: ChatCompletionRequest
    ): Flow<ChatStreamEvent>
}

class OpenAiCompatibleClient(
    private val cryptoManager: KeystoreCryptoManager,
    baseHttpClient: OkHttpClient = SharedHttpClients.defaultClient
) : AiProviderClient {

    private val sharedClient: OkHttpClient = baseHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun timeoutSpecForProvider(provider: ProviderEntity): RequestTimeoutSpec {
        val timeout = provider.timeoutSeconds.coerceIn(5, 300)
        return RequestTimeoutSpec(
            connectTimeoutSeconds = timeout.coerceAtMost(30),
            readTimeoutSeconds = timeout,
            writeTimeoutSeconds = timeout
        )
    }

    internal fun resolveUrl(baseUrl: String, endpointPath: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanPath = endpointPath.trim()
        if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) {
            return cleanPath
        }
        val normalizedPath = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        return "$cleanBase$normalizedPath"
    }

    private fun buildRequestWithHeaders(
        url: String,
        provider: ProviderEntity
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .tag(RequestTimeoutSpec::class.java, timeoutSpecForProvider(provider))
        val decryptedKey = cryptoManager.decrypt(provider.encryptedApiKey).trim()
        if (decryptedKey.isNotEmpty() && provider.authHeaderName.isNotBlank()) {
            val headerVal = if (provider.authPrefix.isNotBlank()) {
                "${provider.authPrefix.trim()} $decryptedKey"
            } else {
                decryptedKey
            }
            builder.addHeader(provider.authHeaderName.trim(), headerVal)
        }
        if (provider.extraHeadersJson.isNotBlank()) {
            runCatching {
                val headersObj = JSONObject(provider.extraHeadersJson)
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headersObj.optString(key, "")
                    if (key.isNotBlank() && value.isNotBlank()) {
                        builder.header(key, value)
                    }
                }
            }
        }
        return builder
    }

    override suspend fun testConnection(provider: ProviderEntity): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val url = resolveUrl(provider.baseUrl, provider.modelsPath)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ConnectionTestResult(
                success = false,
                latencyMs = 0,
                message = "URL inválida. Informe http:// ou https://."
            )
        }

        val request = buildRequestWithHeaders(url, provider).get().build()

        runCatching {
            sharedClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errDetail = extractApiErrorMessage(body)
                    ConnectionTestResult(
                        success = false,
                        latencyMs = latency,
                        message = formatHttpError(response.code, errDetail)
                    )
                } else {
                    val parsed = parseModelsResponse(body)
                    ConnectionTestResult(
                        success = true,
                        latencyMs = latency,
                        message = "Conectado (${latency}ms) • ${parsed.size} modelo(s) retornado(s)",
                        discoveredCount = parsed.size
                    )
                }
            }
        }.getOrElse { ex ->
            val latency = System.currentTimeMillis() - start
            ConnectionTestResult(
                success = false,
                latencyMs = latency,
                message = "Falha de conexão: ${ex.localizedMessage ?: ex::class.java.simpleName}"
            )
        }
    }

    override suspend fun fetchModels(provider: ProviderEntity): Result<List<DiscoveredModel>> = withContext(Dispatchers.IO) {
        val url = resolveUrl(provider.baseUrl, provider.modelsPath)
        val request = buildRequestWithHeaders(url, provider).get().build()

        runCatching {
            sharedClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errDetail = extractApiErrorMessage(body)
                    throw IllegalStateException(formatHttpError(response.code, errDetail))
                }
                val models = parseModelsResponse(body)
                if (models.isEmpty()) {
                    throw IllegalStateException("O endpoint respondeu com sucesso, mas nenhum modelo foi encontrado.")
                }
                models
            }
        }
    }

    internal fun parseModelsResponse(body: String): List<DiscoveredModel> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val collected = linkedMapOf<String, DiscoveredModel>()
        fun addItem(item: Any?) {
            when (item) {
                is String -> if (item.isNotBlank()) {
                    collected.putIfAbsent(item, DiscoveredModel(modelId = item, displayName = item))
                }
                is JSONObject -> {
                    val rawId = item.optString("id").ifBlank {
                        item.optString("name").removePrefix("models/")
                    }
                    if (rawId.isNotBlank()) {
                        val display = item.optString("display_name")
                            .ifBlank { item.optString("displayName") }
                            .ifBlank { rawId }
                        val ownedBy = item.optString("owned_by").takeIf { it.isNotBlank() }
                        val contextLen = listOf("context_length", "context_window", "max_context_length", "input_token_limit", "inputTokenLimit")
                            .firstNotNullOfOrNull { key ->
                                val v = item.optInt(key, -1)
                                if (v > 0) v else null
                            }
                        collected.putIfAbsent(
                            rawId,
                            DiscoveredModel(
                                modelId = rawId,
                                displayName = display,
                                ownedBy = ownedBy,
                                contextLengthHint = contextLen
                            )
                        )
                    }
                }
            }
        }

        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) addItem(arr.opt(i))
        } else {
            val root = JSONObject(trimmed)
            val dataArr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: root.optJSONArray("items")
            if (dataArr != null) {
                for (i in 0 until dataArr.length()) addItem(dataArr.opt(i))
            }
        }
        return collected.values.toList()
    }

    override fun streamChatCompletion(
        provider: ProviderEntity,
        model: ModelEntity,
        request: ChatCompletionRequest
    ): Flow<ChatStreamEvent> = callbackFlow {
        val url = resolveUrl(provider.baseUrl, provider.chatPath)
        val useStream = request.stream && model.supportsStreaming && provider.supportsStreamingDefault
        val payloadJson = buildChatRequestJson(provider, model, request, useStream)
        val okRequest = buildRequestWithHeaders(url, provider)
            .post(payloadJson.toString().toRequestBody(jsonMediaType))
            .header("Accept", if (useStream) "text/event-stream" else "application/json")
            .build()

        val call: Call = sharedClient.newCall(okRequest)

        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string().orEmpty()
                        val detail = extractApiErrorMessage(errBody)
                        trySend(ChatStreamEvent.Error(formatHttpError(resp.code, detail), resp.code))
                        return@use
                    }

                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    val body = resp.body
                    if (body == null) {
                        trySend(ChatStreamEvent.Error("Resposta vazia do provedor."))
                        return@use
                    }

                    if (useStream && (contentType.contains("event-stream") || contentType.contains("text/plain") || contentType.isEmpty())) {
                        BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                            parseSseStream(
                                reader = reader,
                                onEvent = { event -> trySend(event) }
                            )
                        }
                    } else {
                        val rawJson = body.string()
                        parseNonStreamingCompletion(rawJson).forEach { trySend(it) }
                    }
                }
            } catch (t: Throwable) {
                if (!call.isCanceled()) {
                    trySend(ChatStreamEvent.Error("Erro na requisição: ${t.localizedMessage ?: t::class.java.simpleName}"))
                }
            } finally {
                close()
            }
        }

        awaitClose {
            job.cancel()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { call.cancel() }
            }
        }
    }

    internal fun buildChatRequestJson(
        provider: ProviderEntity,
        model: ModelEntity,
        request: ChatCompletionRequest,
        stream: Boolean
    ): JSONObject {
        val root = JSONObject()
        root.put("model", model.modelId)
        root.put("stream", stream)
        root.put("max_tokens", request.maxOutputTokens.coerceAtLeast(16))
        root.put("temperature", request.temperature.toDouble())
        if (request.topP < 0.999f) root.put("top_p", request.topP.toDouble())
        if (request.frequencyPenalty != 0f) root.put("frequency_penalty", request.frequencyPenalty.toDouble())
        if (request.presencePenalty != 0f) root.put("presence_penalty", request.presencePenalty.toDouble())

        val messagesArr = JSONArray()
        for (msg in request.messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            if (msg.content != null) {
                msgObj.put("content", msg.content)
            } else if (msg.toolCalls.isNotEmpty()) {
                msgObj.put("content", JSONObject.NULL)
            } else {
                msgObj.put("content", "")
            }
            if (msg.toolCallId != null) {
                msgObj.put("tool_call_id", msg.toolCallId)
            }
            if (msg.name != null) {
                msgObj.put("name", msg.name)
            }
            if (msg.toolCalls.isNotEmpty()) {
                val tcArr = JSONArray()
                msg.toolCalls.forEach { tc ->
                    tcArr.put(
                        JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.argumentsJson)
                            })
                        }
                    )
                }
                msgObj.put("tool_calls", tcArr)
            }
            messagesArr.put(msgObj)
        }
        root.put("messages", messagesArr)

        if (request.tools.isNotEmpty() && model.supportsTools) {
            val toolsArr = JSONArray()
            for (tool in request.tools) {
                val schema = runCatching { JSONObject(tool.parametersSchemaJson) }
                    .getOrElse { JSONObject().put("type", "object") }
                toolsArr.put(
                    JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", schema)
                        })
                    }
                )
            }
            root.put("tools", toolsArr)
        }

        mergeCustomJson(root, provider.extraParamsJson)
        mergeCustomJson(root, model.customParamsJson)
        return root
    }

    private fun mergeCustomJson(target: JSONObject, jsonString: String) {
        if (jsonString.isBlank() || jsonString.trim() == "{}") return
        runCatching {
            val extra = JSONObject(jsonString)
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k != "model" && k != "messages" && k != "stream") {
                    target.put(k, extra.get(k))
                }
            }
        }
    }

    internal fun parseSseStream(
        reader: BufferedReader,
        onEvent: (ChatStreamEvent) -> Unit
    ) {
        val toolAccumulators = sortedMapOf<Int, MutableToolAccumulator>()
        var lastFinishReason: String? = null
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var emittedCompleted = false

        val dataBuffer = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) {
                if (dataBuffer.isNotEmpty()) {
                    val payload = dataBuffer.toString().trim()
                    dataBuffer.clear()
                    if (payload == "[DONE]") {
                        flushToolAccumulators(toolAccumulators, onEvent)
                        onEvent(ChatStreamEvent.Completed(lastFinishReason, promptTokens, completionTokens))
                        emittedCompleted = true
                        break
                    }
                    processSseJsonChunk(
                        payload = payload,
                        toolAccumulators = toolAccumulators,
                        onText = { onEvent(ChatStreamEvent.TextDelta(it)) },
                        onMeta = { finish, pTok, cTok ->
                            if (finish != null) lastFinishReason = finish
                            if (pTok != null) promptTokens = pTok
                            if (cTok != null) completionTokens = cTok
                        },
                        onError = { onEvent(ChatStreamEvent.Error(it)) }
                    )
                }
                continue
            }
            if (line.startsWith(":")) continue
            if (line.startsWith("data:")) {
                val chunk = line.removePrefix("data:").trimStart()
                if (dataBuffer.isNotEmpty()) dataBuffer.append("\n")
                dataBuffer.append(chunk)
            } else if (line.trimStart().startsWith("{") && dataBuffer.isEmpty()) {
                parseNonStreamingCompletion(line).forEach(onEvent)
                emittedCompleted = true
                break
            }
        }

        if (!emittedCompleted) {
            if (dataBuffer.isNotEmpty()) {
                val remaining = dataBuffer.toString().trim()
                if (remaining != "[DONE]") {
                    processSseJsonChunk(
                        payload = remaining,
                        toolAccumulators = toolAccumulators,
                        onText = { onEvent(ChatStreamEvent.TextDelta(it)) },
                        onMeta = { finish, pTok, cTok ->
                            if (finish != null) lastFinishReason = finish
                            if (pTok != null) promptTokens = pTok
                            if (cTok != null) completionTokens = cTok
                        },
                        onError = { onEvent(ChatStreamEvent.Error(it)) }
                    )
                }
            }
            flushToolAccumulators(toolAccumulators, onEvent)
            onEvent(ChatStreamEvent.Completed(lastFinishReason, promptTokens, completionTokens))
        }
    }

    private fun processSseJsonChunk(
        payload: String,
        toolAccumulators: MutableMap<Int, MutableToolAccumulator>,
        onText: (String) -> Unit,
        onMeta: (String?, Int?, Int?) -> Unit,
        onError: (String) -> Unit
    ) {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
        if (obj.has("error")) {
            val msg = extractApiErrorMessage(payload)
            onError(msg.ifBlank { "Erro retornado durante o streaming." })
            return
        }

        val usage = obj.optJSONObject("usage")
        val pTokens = usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 }
        val cTokens = usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }

        val choices = obj.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            onMeta(null, pTokens, cTokens)
            return
        }

        val choice = choices.optJSONObject(0) ?: return
        val finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
        onMeta(finishReason, pTokens, cTokens)

        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
        val content = delta.optString("content", "")
        if (content.isNotEmpty() && content != "null") {
            onText(content)
        }

        val toolCalls = delta.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                val idx = tc.optInt("index", i)
                val acc = toolAccumulators.getOrPut(idx) { MutableToolAccumulator() }
                tc.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { acc.id = it }
                val fn = tc.optJSONObject("function")
                if (fn != null) {
                    fn.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { acc.name += it }
                    fn.optString("arguments").takeIf { it != "null" }?.let { acc.arguments.append(it) }
                }
            }
        }
    }

    private fun flushToolAccumulators(
        accumulators: MutableMap<Int, MutableToolAccumulator>,
        onEvent: (ChatStreamEvent) -> Unit
    ) {
        if (accumulators.isEmpty()) return
        val calls = accumulators.values.mapIndexed { i, acc ->
            ToolCallRequest(
                id = acc.id.ifBlank { "call_$i" },
                name = acc.name,
                argumentsJson = acc.arguments.toString().ifBlank { "{}" }
            )
        }.filter { it.name.isNotBlank() }
        accumulators.clear()
        if (calls.isNotEmpty()) {
            onEvent(ChatStreamEvent.ToolCallsReady(calls))
        }
    }

    internal fun parseNonStreamingCompletion(rawJson: String): List<ChatStreamEvent> {
        val events = mutableListOf<ChatStreamEvent>()
        val obj = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return listOf(ChatStreamEvent.Error("Resposta JSON inválida do provedor."))

        if (obj.has("error")) {
            return listOf(ChatStreamEvent.Error(extractApiErrorMessage(rawJson)))
        }

        val usage = obj.optJSONObject("usage")
        val pTokens = usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 }
        val cTokens = usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }

        val choices = obj.optJSONArray("choices")
        val choice = choices?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = message?.optString("content", "")?.takeIf { it != "null" }.orEmpty()
        if (content.isNotEmpty()) {
            events.add(ChatStreamEvent.TextDelta(content))
        }

        val tcArr = message?.optJSONArray("tool_calls")
        if (tcArr != null && tcArr.length() > 0) {
            val calls = mutableListOf<ToolCallRequest>()
            for (i in 0 until tcArr.length()) {
                val tc = tcArr.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                calls.add(
                    ToolCallRequest(
                        id = tc.optString("id").ifBlank { "call_$i" },
                        name = fn.optString("name"),
                        argumentsJson = fn.optString("arguments", "{}")
                    )
                )
            }
            if (calls.isNotEmpty()) {
                events.add(ChatStreamEvent.ToolCallsReady(calls))
            }
        }

        val finishReason = choice?.optString("finish_reason")?.takeIf { it.isNotBlank() && it != "null" }
        events.add(ChatStreamEvent.Completed(finishReason, pTokens, cTokens))
        return events
    }

    private fun extractApiErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val json = JSONObject(body)
            val errObj = json.optJSONObject("error")
            errObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: body.take(240)
        }.getOrElse { body.take(240) }
    }

    private fun formatHttpError(code: Int, detail: String): String {
        val hint = when (code) {
            401 -> "Chave de API inválida ou ausente (HTTP 401)."
            403 -> "Acesso negado pelo provedor (HTTP 403)."
            404 -> "Endpoint não encontrado (HTTP 404). Verifique URL-base e caminho."
            429 -> "Limite de requisições atingido (HTTP 429)."
            in 500..599 -> "Erro interno do servidor do provedor (HTTP $code)."
            else -> "Erro HTTP $code."
        }
        return if (detail.isNotBlank()) "$hint $detail" else hint
    }

    private class MutableToolAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )
}
