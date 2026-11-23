package ai.jarvis.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A read-only check against an OpenAI-compatible endpoint.
 *
 * `GET {base}/models` is the only call that both proves a key is accepted and
 * reveals which model IDs that key is allowed to use, so the settings screen can
 * show real errors ("HTTP 401: invalid api key") instead of guessing.
 */
object ProviderProbe {
    private const val TIMEOUT_MS = 15_000
    private const val KIND = "application/json"

    data class Result(
        val ok: Boolean,
        val message: String,
        val models: List<String> = emptyList(),
    )

    /** Joins a base URL and a path without doubling or dropping the slash. */
    fun endpoint(
        baseUrl: String,
        path: String,
    ): String = baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')

    /** Removes characters a paste or a soft keyboard can smuggle into a key. */
    fun sanitizeKey(raw: String): String =
        raw
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .replace("\u00A0", " ")
            .filterNot { it.isWhitespace() || it == '\u201C' || it == '\u201D' }
            .trim()

    /** 401 and 403 are the only statuses that mean "this key is not accepted". */
    fun isAuthFailure(status: Int): Boolean = status == 401 || status == 403

    /**
     * Checks a key against `GET {base}/models` and, when a `model` is supplied,
     * against one minimal completion.
     *
     * The completion matters because some hosts (Freebuff among them) publish
     * `/models` without authentication: a 200 there proves nothing about the
     * key, while a 401 from `/chat/completions` proves it is rejected.
     */
    fun listModels(
        baseUrl: String,
        apiKey: String,
        model: String = "",
    ): Result {
        val key = sanitizeKey(apiKey)
        if (key.isEmpty()) return Result(false, "No key to test.")
        if (!baseUrl.trim().startsWith("http")) return Result(false, "Base URL must start with https://")
        val url = endpoint(baseUrl, "models")
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Accept", KIND)
                }
            val status = connection.responseCode
            val body =
                (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            if (status !in 200..299) {
                Result(false, "HTTP $status: ${detail(body)}")
            } else {
                val models =
                    runCatching {
                        val array = JSONObject(body).optJSONArray("data") ?: return@runCatching emptyList()
                        (0 until array.length())
                            .mapNotNull { array.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
                            .sorted()
                    }.getOrDefault(emptyList())
                val rejected = if (model.isBlank()) null else chatAuthFailure(baseUrl, key, model)
                when {
                    rejected != null -> Result(false, rejected, models)
                    models.isEmpty() ->
                        Result(true, "Key accepted; the endpoint returned no model list.", emptyList())
                    else -> Result(true, "Key accepted. ${models.size} models available.", models)
                }
            }
        } catch (error: Exception) {
            Result(false, "Cannot reach the endpoint: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * One-token completion to find out whether the key is really accepted.
     * Returns the rejection message, or null when the key was not refused
     * (a 400 from a strict host is not an authentication failure).
     */
    private fun chatAuthFailure(
        baseUrl: String,
        key: String,
        model: String,
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            val payload =
                JSONObject()
                    .put("model", model)
                    .put("max_tokens", 1)
                    .put("stream", false)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
                    ).toString()
            connection =
                (URL(endpoint(baseUrl, "chat/completions")).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", KIND)
                    setRequestProperty("Accept", KIND)
                }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (!isAuthFailure(status)) return null
            val body =
                connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            "HTTP $status: ${detail(body)}"
        } catch (error: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Pulls the useful sentence out of an OpenAI-style error body. */
    private fun detail(body: String): String {
        val fromJson =
            runCatching {
                val error = JSONObject(body).opt("error")
                when (error) {
                    is JSONObject ->
                        error.optString("message").ifBlank { error.optString("code") }
                    is String -> error
                    else -> ""
                }
            }.getOrDefault("")
        return (fromJson.ifBlank { body }).take(220).ifBlank { "no response body" }
    }
}
