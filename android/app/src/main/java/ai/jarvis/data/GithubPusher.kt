package ai.jarvis.data

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Simple GitHub integration to push conversation memory to a repo.
 * Uses GitHub's content API (no need for git commands on device).
 */
class GithubPusher(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val branch: String = "main",
) {
    data class PushResult(
        val success: Boolean,
        val message: String,
        val url: String? = null,
    )

    companion object {
        private const val TAG = "GithubPusher"
        private const val GITHUB_API = "https://api.github.com"
    }

    /**
     * Push conversation memory file to GitHub
     */
    fun pushConversation(memoryJson: String, path: String = "conversations/memory.json"): PushResult {
        return pushContent(path, memoryJson, "application/json")
    }

    /**
     * Push any content to GitHub
     */
    fun pushContent(path: String, content: String, contentType: String): PushResult {
        return try {
            // Step 1: Get current file SHA (if exists)
            val existingSha = getFileSha(path)

            // Step 2: Create or update the file
            val url = "$GITHUB_API/repos/$owner/$repo/contents/$path"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "JARVIS-Android")

            val jsonBody = """
                {
                    "message": "JARVIS: Update conversation memory",
                    "content": "${Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))}",
                    "branch": "$branch"
                    ${if (existingSha != null) ",\"sha\": \"$existingSha\"" else ""}
                }
            """.trimIndent()

            connection.doOutput = true
            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val response = if (responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                reader.readText()
            } else {
                val reader = BufferedReader(InputStreamReader(connection.errorStream))
                reader.readText()
            }

            if (responseCode in 200..299) {
                val sha = parseJsonField(response, "sha")
                val htmlUrl = parseJsonField(response, "html_url")
                PushResult(
                    success = true,
                    message = "Pushed successfully to GitHub",
                    url = htmlUrl,
                )
            } else {
                PushResult(
                    success = false,
                    message = "GitHub API error: $response",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub push failed", e)
            PushResult(
                success = false,
                message = "Push failed: ${e.message}",
            )
        }
    }

    /**
     * Get the SHA of an existing file
     */
    private fun getFileSha(path: String): String? {
        return try {
            val url = "$GITHUB_API/repos/$owner/$repo/contents/$path"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "JARVIS-Android")

            if (connection.responseCode == 404) {
                null // File doesn't exist yet
            } else if (connection.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                parseJsonField(response, "sha")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get file SHA", e)
            null
        }
    }

    private fun parseJsonField(json: String, field: String): String? {
        return try {
            val obj = org.json.JSONObject(json)
            obj.optString(field, null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verify the token works
     */
    fun verifyToken(): PushResult {
        return try {
            val url = "$GITHUB_API/user"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("User-Agent", "JARVIS-Android")

            if (connection.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                val username = parseJsonField(response, "login")
                PushResult(
                    success = true,
                    message = "Token valid (user: $username)",
                )
            } else {
                PushResult(
                    success = false,
                    message = "Token invalid (code: ${connection.responseCode})",
                )
            }
        } catch (e: Exception) {
            PushResult(success = false, message = "Token verification failed: ${e.message}")
        }
    }
}
