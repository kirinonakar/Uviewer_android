package com.uviewer_android.data.llm

import com.uviewer_android.data.repository.CredentialsManager
import com.uviewer_android.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Small REST client for the providers used by the selection action.
 *
 * The client deliberately builds provider-specific JSON instead of sharing a
 * generic request object. This keeps each provider's thinking option precise
 * and makes it possible to omit the option completely for `default`.
 */
class LlmClient(
    private val credentialsManager: CredentialsManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    suspend fun complete(selectedText: String): String {
        require(selectedText.isNotBlank()) { "No text was selected." }

        val provider = userPreferencesRepository.llmProvider.value
        val apiKey = apiKeyFor(provider)
        val model = userPreferencesRepository.getLlmModelName(provider).trim()
            .ifBlank { provider.defaultModel }
        val thinkingLevel = userPreferencesRepository.getLlmThinkingLevel(provider)
        val systemPrompt = userPreferencesRepository.llmSystemPrompt.value
            .ifBlank { UserPreferencesRepository.DEFAULT_LLM_SYSTEM_PROMPT }

        return withContext(Dispatchers.IO) {
            when (provider) {
                LlmProvider.GOOGLE -> requestGoogle(
                    apiKey = requireNotNull(apiKey),
                    model = model,
                    thinkingLevel = thinkingLevel,
                    systemPrompt = systemPrompt,
                    selectedText = selectedText
                )

                LlmProvider.OLLAMA_CLOUD -> requestOllamaCloud(
                    apiKey = requireNotNull(apiKey),
                    model = model,
                    thinkingLevel = thinkingLevel,
                    systemPrompt = systemPrompt,
                    selectedText = selectedText
                )

                LlmProvider.OPENCODE_GO,
                LlmProvider.ZEN -> requestOpenCode(
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    thinkingLevel = thinkingLevel,
                    systemPrompt = systemPrompt,
                    selectedText = selectedText
                )
            }
        }
    }

    suspend fun listModels(): List<LlmModelOption> {
        val provider = userPreferencesRepository.llmProvider.value
        val apiKey = apiKeyFor(provider)

        return withContext(Dispatchers.IO) {
            when (provider) {
                LlmProvider.GOOGLE -> listGoogleModels(requireNotNull(apiKey))
                LlmProvider.OLLAMA_CLOUD -> listOllamaCloudModels(requireNotNull(apiKey))
                LlmProvider.OPENCODE_GO,
                LlmProvider.ZEN -> listOpenCodeModels(provider, apiKey)
            }
        }
    }

    private fun apiKeyFor(provider: LlmProvider): String? {
        val configuredApiKey = credentialsManager.getLlmApiKey(provider)
        if (configuredApiKey != null || provider == LlmProvider.ZEN) return configuredApiKey
        throw IllegalStateException("Configure an API key for ${provider.displayName()} in Settings.")
    }

    private fun listGoogleModels(apiKey: String): List<LlmModelOption> {
        val models = mutableListOf<LlmModelOption>()
        var pageToken: String? = null
        var pageCount = 0

        do {
            val url = buildString {
                append("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                pageToken?.let {
                    append("&pageToken=")
                    append(encodeQueryParameter(it))
                }
            }
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .header("x-goog-api-client", "uviewer-android/1.0")
                .get()
                .build()
            val page = execute(request) { it }
            val modelArray = page.optJSONArray("models")
            for (index in 0 until (modelArray?.length() ?: 0)) {
                val model = modelArray?.optJSONObject(index) ?: continue
                val supportedActions = model.optJSONArray("supportedGenerationMethods")
                val supportsGenerateContent = (0 until (supportedActions?.length() ?: 0))
                    .any { actionIndex -> supportedActions?.optString(actionIndex) == "generateContent" }
                if (!supportsGenerateContent) continue

                val id = model.optString("baseModelId").ifBlank {
                    model.optString("name").removePrefix("models/")
                }.trim()
                if (id.isNotBlank()) {
                    models += LlmModelOption(
                        id = id,
                        displayName = model.optString("displayName").ifBlank { id }
                    )
                }
            }
            pageToken = page.optString("nextPageToken").takeIf { it.isNotBlank() }
            pageCount++
        } while (pageToken != null && pageCount < MAX_MODEL_PAGES)

        return models.distinctBy { it.id }
    }

    private fun listOllamaCloudModels(apiKey: String): List<LlmModelOption> {
        val request = Request.Builder()
            .url("https://ollama.com/api/tags")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return execute(request) { response ->
            val modelArray = response.optJSONArray("models")
            buildList {
                for (index in 0 until (modelArray?.length() ?: 0)) {
                    val model = modelArray?.optJSONObject(index) ?: continue
                    val id = model.optString("name").ifBlank { model.optString("model") }.trim()
                    if (id.isNotBlank()) add(LlmModelOption(id))
                }
            }.distinctBy { it.id }
        }
    }

    private fun listOpenCodeModels(
        provider: LlmProvider,
        apiKey: String?
    ): List<LlmModelOption> {
        val baseUrl = when (provider) {
            LlmProvider.OPENCODE_GO -> "https://opencode.ai/zen/go/v1"
            LlmProvider.ZEN -> "https://opencode.ai/zen/v1"
            else -> error("Unsupported OpenCode provider")
        }
        val request = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return execute(request) { response ->
            val modelArray = response.optJSONArray("data") ?: response.optJSONArray("models")
            buildList {
                for (index in 0 until (modelArray?.length() ?: 0)) {
                    val model = modelArray?.optJSONObject(index) ?: continue
                    val id = model.optString("id")
                        .ifBlank { model.optString("name") }
                        .ifBlank { model.optString("model") }
                        .trim()
                    if (id.isNotBlank()) {
                        add(
                            LlmModelOption(
                                id = id,
                                displayName = model.optString("name").ifBlank { id }
                            )
                        )
                    }
                }
            }.distinctBy { it.id }
        }
    }

    private fun requestGoogle(
        apiKey: String,
        model: String,
        thinkingLevel: LlmThinkingLevel,
        systemPrompt: String,
        selectedText: String
    ): String {
        val requestJson = JSONObject()
            .put(
                "systemInstruction",
                JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", selectedText)))
                )
            )

        // `default` intentionally does not add generationConfig or any
        // thinking field. Gemini 2.5 uses the legacy budget field, while
        // Gemini 3/Gemma use thinkingConfig.thinkingLevel.
        if (thinkingLevel != LlmThinkingLevel.DEFAULT) {
            val generationConfig = JSONObject()
            if (model.lowercase(Locale.US).startsWith("gemini-2.5")) {
                generationConfig.put("thinkingBudget", googleThinkingBudget(thinkingLevel))
            } else {
                generationConfig.put(
                    "thinkingConfig",
                    JSONObject().put(
                        "thinkingLevel",
                        thinkingLevel.storageKey
                    )
                )
            }
            requestJson.put("generationConfig", generationConfig)
        }

        val googleModel = model.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "${encodePathSegment(googleModel)}:generateContent"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("x-goog-api-client", "uviewer-android/1.0")
            .post(requestJson.toString().jsonRequestBody())
            .build()

        return execute(request) { response ->
            val candidates = response.optJSONArray("candidates")
                ?: throw IOException("Google returned no candidates.")
            val text = buildString {
                for (i in 0 until candidates.length()) {
                    val parts = candidates.optJSONObject(i)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?: continue
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (!part.optBoolean("thought", false)) {
                            append(part.optString("text", ""))
                        }
                    }
                }
            }
            text.requireResponseText()
        }
    }

    private fun requestOllamaCloud(
        apiKey: String,
        model: String,
        thinkingLevel: LlmThinkingLevel,
        systemPrompt: String,
        selectedText: String
    ): String {
        val requestJson = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", selectedText))
            )
            .put("stream", false)

        // Ollama calls this field `think`. Do not put it in the body when the
        // user selected Default; this lets the server/model choose its own
        // default behavior.
        if (thinkingLevel != LlmThinkingLevel.DEFAULT) {
            requestJson.put(
                "think",
                if (thinkingLevel == LlmThinkingLevel.DISABLE) {
                    false
                } else {
                    thinkingLevel.storageKey
                }
            )
        }

        val request = Request.Builder()
            .url("https://ollama.com/api/chat")
            .header("Authorization", "Bearer $apiKey")
            .post(requestJson.toString().jsonRequestBody())
            .build()

        return execute(request) { response ->
            response.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
                .requireResponseText()
        }
    }

    private fun requestOpenCode(
        provider: LlmProvider,
        apiKey: String?,
        model: String,
        thinkingLevel: LlmThinkingLevel,
        systemPrompt: String,
        selectedText: String
    ): String {
        val apiModel = normalizeOpenCodeModel(model)
        val baseUrl = when (provider) {
            LlmProvider.OPENCODE_GO -> "https://opencode.ai/zen/go/v1"
            LlmProvider.ZEN -> "https://opencode.ai/zen/v1"
            else -> error("Unsupported OpenCode provider")
        }

        return when (openCodeProtocol(apiModel)) {
            OpenCodeProtocol.RESPONSES -> requestOpenCodeResponses(
                url = "$baseUrl/responses",
                apiKey = apiKey,
                model = apiModel,
                thinkingLevel = thinkingLevel,
                systemPrompt = systemPrompt,
                selectedText = selectedText
            )

            OpenCodeProtocol.CHAT_COMPLETIONS -> requestOpenCodeChatCompletions(
                url = "$baseUrl/chat/completions",
                apiKey = apiKey,
                model = apiModel,
                thinkingLevel = thinkingLevel,
                systemPrompt = systemPrompt,
                selectedText = selectedText
            )

            OpenCodeProtocol.MESSAGES -> requestOpenCodeMessages(
                url = "$baseUrl/messages",
                apiKey = apiKey,
                model = apiModel,
                thinkingLevel = thinkingLevel,
                systemPrompt = systemPrompt,
                selectedText = selectedText
            )
        }
    }

    private fun requestOpenCodeResponses(
        url: String,
        apiKey: String?,
        model: String,
        thinkingLevel: LlmThinkingLevel,
        systemPrompt: String,
        selectedText: String
    ): String {
        val requestJson = JSONObject()
            .put("model", model)
            .put("instructions", systemPrompt)
            .put("input", selectedText)
            .put("store", false)

        // OpenAI-compatible Responses APIs use reasoning.effort. Default is
        // deliberately omitted for the same reason as the other providers.
        if (thinkingLevel != LlmThinkingLevel.DEFAULT) {
            requestJson.put(
                "reasoning",
                JSONObject().put("effort", thinkingLevel.storageKey)
            )
        }

        val request = openCodeRequest(url, apiKey, requestJson)
        return execute(request) { response ->
            val directText = response.optString("output_text", "")
            if (directText.isNotBlank()) return@execute directText

            val output = response.optJSONArray("output")
            val text = buildString {
                for (i in 0 until (output?.length() ?: 0)) {
                    val item = output?.optJSONObject(i) ?: continue
                    append(item.optString("text", ""))
                    val content = item.optJSONArray("content")
                    for (j in 0 until (content?.length() ?: 0)) {
                        val part = content?.optJSONObject(j) ?: continue
                        append(part.optString("text", ""))
                    }
                }
            }
            text.requireResponseText()
        }
    }

    private fun requestOpenCodeChatCompletions(
        url: String,
        apiKey: String?,
        model: String,
        thinkingLevel: LlmThinkingLevel,
        systemPrompt: String,
        selectedText: String
    ): String {
        val requestJson = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", selectedText))
            )
            .put("stream", false)

        if (thinkingLevel != LlmThinkingLevel.DEFAULT) {
            requestJson.put("reasoning_effort", thinkingLevel.storageKey)
        }

        val request = openCodeRequest(url, apiKey, requestJson)
        return execute(request) { response ->
            val choices = response.optJSONArray("choices")
                ?: throw IOException("OpenCode returned no choices.")
            val text = buildString {
                for (i in 0 until choices.length()) {
                    val message = choices.optJSONObject(i)?.optJSONObject("message") ?: continue
                    append(jsonTextValue(message.opt("content")))
                }
            }
            text.requireResponseText()
        }
    }

    private fun requestOpenCodeMessages(
        url: String,
        apiKey: String?,
        model: String,
        thinkingLevel: LlmThinkingLevel,
        systemPrompt: String,
        selectedText: String
    ): String {
        val requestJson = JSONObject()
            .put("model", model)
            .put("max_tokens", 16384)
            .put("system", systemPrompt)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", selectedText))
            )

        if (thinkingLevel != LlmThinkingLevel.DEFAULT) {
            requestJson.put(
                "thinking",
                JSONObject()
                    .put("type", "enabled")
                    .put("budget_tokens", anthropicThinkingBudget(thinkingLevel))
            )
        }

        val request = openCodeRequest(url, apiKey, requestJson)
        return execute(request) { response ->
            val content = response.optJSONArray("content")
                ?: throw IOException("OpenCode returned no message content.")
            val text = buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") {
                        append(part.optString("text", ""))
                    }
                }
            }
            text.requireResponseText()
        }
    }

    private fun openCodeRequest(url: String, apiKey: String?, body: JSONObject): Request {
        val builder = Request.Builder()
            .url(url)
        if (apiKey != null) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return builder
            .post(body.toString().jsonRequestBody())
            .build()
    }

    private fun <T> execute(request: Request, parse: (JSONObject) -> T): T {
        try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val body = try {
                    JSONObject(rawBody)
                } catch (_: Exception) {
                    throw IOException("Provider returned an invalid response.")
                }
                if (!response.isSuccessful) {
                    throw IOException(providerError(body, response.code))
                }
                return parse(body)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "LLM request failed.", e)
        }
    }

    private fun providerError(body: JSONObject, statusCode: Int): String {
        val error = body.opt("error")
        val message = when (error) {
            is JSONObject -> error.optString("message", "")
            is String -> error
            else -> body.optString("message", "")
        }.trim()
        return if (message.isNotBlank()) {
            message.take(500)
        } else {
            "LLM request failed (HTTP $statusCode)."
        }
    }

    private fun String.requireResponseText(): String {
        val result = trim()
        if (result.isBlank()) throw IOException("The provider returned an empty response.")
        return result
    }

    private fun googleThinkingBudget(level: LlmThinkingLevel): Int {
        return when (level) {
            LlmThinkingLevel.MINIMAL,
            LlmThinkingLevel.LOW -> 1024
            LlmThinkingLevel.MEDIUM -> 8192
            LlmThinkingLevel.HIGH -> 24576
            else -> 0
        }
    }

    private fun anthropicThinkingBudget(level: LlmThinkingLevel): Int {
        return when (level) {
            LlmThinkingLevel.LOW -> 2048
            LlmThinkingLevel.MEDIUM -> 4096
            LlmThinkingLevel.HIGH -> 8192
            else -> 2048
        }
    }

    private fun jsonTextValue(value: Any?): String {
        return when (value) {
            is String -> value
            is JSONArray -> buildString {
                for (i in 0 until value.length()) append(jsonTextValue(value.opt(i)))
            }
            is JSONObject -> value.optString("text", "")
            else -> ""
        }
    }

    private fun normalizeOpenCodeModel(model: String): String {
        return model.trim()
            .removePrefix("opencode-go/")
            .removePrefix("opencode-zen/")
            .removePrefix("zen/")
    }

    private fun openCodeProtocol(model: String): OpenCodeProtocol {
        val normalized = model.lowercase(Locale.US)
        return when {
            normalized.startsWith("gpt-") ||
                normalized.startsWith("grok-") ||
                normalized.startsWith("muse-") -> OpenCodeProtocol.RESPONSES

            normalized.startsWith("minimax-") ||
                normalized.startsWith("qwen3.") -> OpenCodeProtocol.MESSAGES

            else -> OpenCodeProtocol.CHAT_COMPLETIONS
        }
    }

    private fun encodePathSegment(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
    }

    private fun encodeQueryParameter(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun JSONObject.jsonRequestBody() =
        toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun String.jsonRequestBody() =
        toRequestBody(JSON_MEDIA_TYPE)

    private enum class OpenCodeProtocol {
        RESPONSES,
        CHAT_COMPLETIONS,
        MESSAGES
    }

    private companion object {
        const val MAX_MODEL_PAGES = 10

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(150, TimeUnit.SECONDS)
                .build()
        }
    }
}

private fun LlmProvider.displayName(): String {
    return when (this) {
        LlmProvider.GOOGLE -> "Google"
        LlmProvider.OLLAMA_CLOUD -> "Ollama Cloud"
        LlmProvider.OPENCODE_GO -> "OpenCode Go"
        LlmProvider.ZEN -> "OpenCode Zen"
    }
}
