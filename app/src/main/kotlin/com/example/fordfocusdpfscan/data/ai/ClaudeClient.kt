package com.example.fordfocusdpfscan.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════════════════
// ClaudeClient.kt — Minimal Anthropic Messages API client for the AI features.
//
// One-shot, non-streaming POST to /v1/messages via OkHttp + org.json (no SDK).
// Records token usage into AiSettings for the in-app cost estimate. Never sends
// temperature/top_p/thinking — keeping the request valid across Opus 4.8 / Sonnet 5
// / Haiku 4.5 (Opus/Sonnet reject sampling params).
// ═══════════════════════════════════════════════════════════════════════════════

/** One chat turn. [role] is "user" or "assistant". */
data class AiMessage(val role: String, val content: String)

class ClaudeClient(private val settings: AiSettings) {

    companion object {
        private const val URL     = "https://api.anthropic.com/v1/messages"
        private const val VERSION = "2023-06-01"
        private const val MAX_TOKENS = 2048
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Sends [userText] with the given [system] prompt. Returns the assistant text
     * on success, or a [Result.failure] with a user-facing Italian message.
     */
    /** One-shot convenience wrapper over [chat]. */
    suspend fun ask(system: String, userText: String): Result<String> =
        chat(system, listOf(AiMessage("user", userText)))

    /** Multi-turn conversation. [messages] roles are "user"/"assistant", oldest first. */
    suspend fun chat(system: String, messages: List<AiMessage>): Result<String> = withContext(Dispatchers.IO) {
        val key = settings.apiKey
        if (key.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Nie ustawiono klucza API. Wpisz go w Ustawieniach AI.")
            )
        }
        val model = settings.model

        val msgArray = JSONArray()
        for (m in messages) {
            msgArray.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
            })
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", system)
            put("messages", msgArray)
        }.toString()

        val request = Request.Builder()
            .url(URL)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", VERSION)
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        try {
            http.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = parseErrorMessage(raw) ?: "Błąd HTTP ${resp.code}"
                    return@withContext Result.failure(RuntimeException(msg))
                }
                val json = JSONObject(raw)

                // Record token usage for the local cost estimate.
                json.optJSONObject("usage")?.let { u ->
                    settings.addUsage(
                        model        = model,
                        inputTokens  = u.optLong("input_tokens"),
                        outputTokens = u.optLong("output_tokens"),
                        cacheRead    = u.optLong("cache_read_input_tokens"),
                        cacheWrite   = u.optLong("cache_creation_input_tokens")
                    )
                }

                if (json.optString("stop_reason") == "refusal") {
                    return@withContext Result.failure(
                        RuntimeException("Richiesta rifiutata dai filtri di sicurezza del modello.")
                    )
                }

                val text = extractText(json)
                if (text.isBlank()) Result.failure(RuntimeException("Risposta vuota dal modello."))
                else Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("Błąd sieci: ${e.message ?: "połączenie nieudane"}"))
        }
    }

    private fun extractText(json: JSONObject): String {
        val content = json.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().trim()
    }

    private fun parseErrorMessage(raw: String): String? = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}
