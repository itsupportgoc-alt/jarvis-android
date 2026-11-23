package ai.jarvis.core

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

    fun listModels(
        baseUrl: String,
        apiKey: String,
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
                if (models.isEmpty()) {
                    Result(true, "Key accepted; the endpoint returned no model list.", emptyList())
                } else {
                    Result(true, "Key accepted. ${models.size} models available.", models)
                }
            }
        } catch (error: Exception) {
            Result(false, "Cannot reach the endpoint: ${error.message ?: error.javaClass.simpleName}")
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
