package com.hermes.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fallback command understanding for phrasing CommandParser's simple
 * rules don't cover — "hey can you ring up dad real quick" instead of
 * the exact "Hermes call Dad". Only called when the rule-based parser
 * returns Command.Unknown, so the common case never touches the network
 * and keeps working even with no API key or no signal.
 *
 * Sends only the single heard phrase to OpenRouter — never contacts,
 * call history, or anything else on the device — and asks for one of
 * the same actions ActionExecutor already knows how to run. The model's
 * reply is parsed as strict JSON; anything that doesn't parse cleanly
 * is treated as "couldn't understand it" rather than guessed at.
 */
object LlmIntentParser {

    // Swap this for any model you have access to on openrouter.ai/models —
    // including one of their free-tier ones if you'd rather not spend credits.
    private const val MODEL = "openai/gpt-4o-mini"
    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        You turn one spoken sentence into a single JSON command for a phone
        assistant called Hermes. Reply with ONLY a JSON object, no prose,
        matching exactly one of these shapes:

        {"action":"call","contact":"<name>"}
        {"action":"message","contact":"<name>","body":"<text>"}
        {"action":"open_app","app_name":"<name>"}
        {"action":"timer","seconds":<integer>,"label":"<short label or empty>"}
        {"action":"search","query":"<text>"}
        {"action":"flashlight","on":<true or false>}
        {"action":"time"}
        {"action":"battery"}
        {"action":"unknown"}

        Pick "unknown" if the sentence isn't clearly one of these things —
        never invent a contact name or app that wasn't said.
    """.trimIndent()

    suspend fun parse(heardText: String): Command? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.OPENROUTER_API_KEY
        if (apiKey.isBlank() || heardText.isBlank()) return@withContext null

        try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", heardText) })
                })
                put("temperature", 0)
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val raw = response.body?.string() ?: return@withContext null
                val content = JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```")
                    .trim()

                toCommand(JSONObject(content))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toCommand(json: JSONObject): Command? {
        return when (json.optString("action")) {
            "call" -> {
                val contact = json.optString("contact")
                if (contact.isBlank()) null else Command.Call(contact)
            }
            "message" -> {
                val contact = json.optString("contact")
                val body = json.optString("body")
                if (contact.isBlank() || body.isBlank()) null else Command.Message(contact, body)
            }
            "open_app" -> {
                val app = json.optString("app_name")
                if (app.isBlank()) null else Command.OpenApp(app)
            }
            "timer" -> {
                val seconds = json.optInt("seconds", 300)
                Command.Timer(seconds, json.optString("label", "Hermes timer").ifBlank { "Hermes timer" })
            }
            "search" -> {
                val query = json.optString("query")
                if (query.isBlank()) null else Command.Search(query)
            }
            "flashlight" -> Command.Flashlight(json.optBoolean("on", true))
            "time" -> Command.Time
            "battery" -> Command.Battery
            else -> null
        }
    }
}
