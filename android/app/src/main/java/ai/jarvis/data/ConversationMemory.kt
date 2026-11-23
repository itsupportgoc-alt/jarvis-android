package ai.jarvis.data

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Local conversation memory that survives app restarts.
 * Stores messages in a transcript log file, with a separate JSON index for quick access.
 */
class ConversationMemory(private val context: Context) {
    private val logFile: File
        get() = File(context.filesDir, "jarvis/transcript.log")

    private val memoryIndexFile: File
        get() = File(context.filesDir, "jarvis/memory_index.json")

    data class MemoryEntry(
        val id: String,
        val speaker: String, // "user" or "assistant"
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val sessionId: String
    )

    companion object {
        private const val TAG = "ConversationMemory"
        private const val MAX_ENTRIES = 200
        private const val SESSION_FILE = "jarvis/current_session.txt"

        @JvmStatic
        fun currentSessionId(ctx: Context): String {
            val sessionFile = File(ctx.filesDir, SESSION_FILE)
            if (sessionFile.exists()) {
                return sessionFile.readText().trim()
            }
            val newSession = UUID.randomUUID().toString()
            sessionFile.parentFile?.mkdirs()
            sessionFile.writeText(newSession)
            return newSession
        }

        @JvmStatic
        fun newSession(ctx: Context): String {
            val sessionId = UUID.randomUUID().toString()
            val sessionFile = File(ctx.filesDir, SESSION_FILE)
            sessionFile.writeText(sessionId)
            return sessionId
        }
    }

    fun addUserMessage(text: String): MemoryEntry {
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            speaker = "user",
            text = text,
            sessionId = currentSessionId(context)
        )
        appendToFile(entry)
        return entry
    }

    fun addAssistantMessage(text: String): MemoryEntry {
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            speaker = "assistant",
            text = text,
            sessionId = currentSessionId(context)
        )
        appendToFile(entry)
        return entry
    }

    fun getRecentMessages(limit: Int = MAX_ENTRIES): List<MemoryEntry> {
        val entries = mutableListOf<MemoryEntry>()
        val sessionId = currentSessionId(context)

        val index = loadIndex()
        val sessionEntries = index[sessionId] ?: emptyList()

        for (entry in sessionEntries.reversed().take(limit)) {
            entries.add(entry)
        }

        if (entries.isEmpty() && logFile.exists()) {
            entries.clear()
            val lines = logFile.readLines()
            for (line in lines.reversed()) {
                val entry = parseLine(line)
                if (entry != null && entry.sessionId == sessionId) {
                    entries.add(entry)
                    if (entries.size >= limit) break
                }
            }
        }

        return entries
    }

    fun getConversationContext(maxExchanges: Int = 10): String {
        val messages = getRecentMessages(maxExchanges * 2)
        if (messages.isEmpty()) return ""

        val context = StringBuilder()
        context.append("Conversation history (most recent first):\n")
        for (msg in messages) {
            val speaker = if (msg.speaker == "user") "USER" else "ASSISTANT"
            context.append("[$speaker]: ${msg.text}\n")
        }
        return context.toString()
    }

    fun clearSession() {
        val sessionId = currentSessionId(context)
        val index = loadIndex()
        index.remove(sessionId)
        saveIndex(index)
    }

    fun clearAll() {
        logFile.delete()
        memoryIndexFile.delete()
        newSession(context)
    }

    private fun appendToFile(entry: MemoryEntry) {
        logFile.parentFile?.mkdirs()
        val json = """{"id":"${entry.id}","speaker":"${entry.speaker}","text":"${escapeJson(entry.text)}","timestamp":${entry.timestamp},"sessionId":"${entry.sessionId}"}"""
        logFile.appendText("$json\n")
        val index = loadIndex()
        index.getOrPut(entry.sessionId) { mutableListOf() }.add(entry)
        trimIndex(index)
        saveIndex(index)
    }

    private fun trimIndex(index: MutableMap<String, MutableList<MemoryEntry>>) {
        val currentSession = currentSessionId(context)
        for ((sid, list) in index) {
            if (list.isNotEmpty()) {
                if (sid == currentSession && list.size > MAX_ENTRIES) {
                    list.removeAt(0)
                }
                if (sid != currentSession && list.size > 10) {
                    list.removeAt(0)
                }
            }
        }
    }

    private fun loadIndex(): MutableMap<String, MutableList<MemoryEntry>> {
        if (!memoryIndexFile.exists()) return mutableMapOf()
        return try {
            val json = memoryIndexFile.readText()
            val decoded = Base64.decode(json, Base64.NO_WRAP)
            val obj = org.json.JSONObject(String(decoded))
            val map = mutableMapOf<String, MutableList<MemoryEntry>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val list = mutableListOf<MemoryEntry>()
                val arr = obj.optJSONArray(key)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        list.add(
                            MemoryEntry(
                                id = item.getString("id"),
                                speaker = item.getString("speaker"),
                                text = item.getString("text"),
                                timestamp = item.getLong("timestamp"),
                                sessionId = item.getString("sessionId")
                            )
                        )
                    }
                }
                map[key] = list
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load memory index", e)
            mutableMapOf()
        }
    }

    private fun saveIndex(index: Map<String, MutableList<MemoryEntry>>) {
        try {
            val json = org.json.JSONObject()
            for ((sessionId, entries) in index) {
                val arr = org.json.JSONArray()
                for (entry in entries) {
                    val obj = org.json.JSONObject()
                    obj.put("id", entry.id)
                    obj.put("speaker", entry.speaker)
                    obj.put("text", entry.text)
                    obj.put("timestamp", entry.timestamp)
                    obj.put("sessionId", entry.sessionId)
                    arr.put(obj)
                }
                json.put(sessionId, arr)
            }
            memoryIndexFile.parentFile?.mkdirs()
            memoryIndexFile.writeText(Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save memory index", e)
        }
    }

    private fun parseLine(line: String): MemoryEntry? {
        return try {
            val obj = org.json.JSONObject(line)
            MemoryEntry(
                id = obj.getString("id"),
                speaker = obj.getString("speaker"),
                text = obj.getString("text"),
                timestamp = obj.getLong("timestamp"),
                sessionId = obj.getString("sessionId")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
