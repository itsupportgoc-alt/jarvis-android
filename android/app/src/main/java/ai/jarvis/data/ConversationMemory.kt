package ai.jarvis.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Cloud-backed conversation memory.
 *
 * Nothing is written to local storage. The transcript lives in RAM for the
 * running app and is persisted to GitHub (`conversations/memory.json`) as soon
 * as a token/owner/repo is configured, so JARVIS keeps its memory across
 * restarts and devices. With GitHub unconfigured the memory lasts for the
 * current session only.
 */
class ConversationMemory {
    data class MemoryEntry(
        val id: String,
        val speaker: String, // "user" or "assistant"
        val text: String,
        val timestamp: Long,
        val sessionId: String,
    )

    companion object {
        private const val TAG = "ConversationMemory"

        /** Entries kept in RAM and in the pushed file. */
        const val MAX_ENTRIES = 200

        /** Characters of history handed to the model: roughly 750 tokens. */
        private const val MAX_CONTEXT_CHARS = 3000

        /** Per-turn truncation, so one long reply cannot eat the budget. */
        private const val MAX_TURN_CHARS = 500

        /** One API call is made after a burst of turns, not per turn. */
        private const val PUSH_DEBOUNCE_MS = 1500L

        const val REMOTE_PATH = "conversations/memory.json"
    }

    private val entries = ArrayDeque<MemoryEntry>()
    private val lock = Any()

    @Volatile
    private var sessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var autoPush = true

    @Volatile
    private var pushQueued = false

    private var pusher: GithubPusher? = null
    private var configSignature: String = ""

    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "jarvis-memory-sync").apply { isDaemon = true }
        }

    /** Session id written alongside the transcript. */
    val session: String get() = sessionId

    /**
     * Point the memory at a GitHub repo. Blank values disable cloud storage and
     * leave the transcript in RAM. Returns whether cloud storage is active.
     */
    fun configure(
        token: String,
        owner: String,
        repo: String,
    ): Boolean {
        val usable = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()
        if (!usable) {
            pusher = null
            configSignature = ""
            return false
        }
        val signature = "${token.trim()}|${owner.trim()}|${repo.trim()}"
        if (signature == configSignature && pusher != null) return true
        configSignature = signature
        pusher = GithubPusher(token.trim(), owner.trim(), repo.trim())
        loadFromCloud()
        return true
    }

    /** Whether the transcript is being saved online. */
    val isCloudBacked: Boolean get() = pusher != null

    fun setAutoPush(enabled: Boolean) {
        autoPush = enabled
    }

    /** Pull the saved transcript from GitHub into this session. */
    fun loadFromCloud() {
        if (pusher == null) return
        worker.execute { pull() }
    }

    private fun pull() {
        val client = pusher ?: return
        val body = client.fetchContent(REMOTE_PATH)
        if (body.isNullOrBlank()) return
        val remote = parse(body)
        if (remote.isEmpty()) return
        synchronized(lock) {
            if (entries.isEmpty()) {
                entries.addAll(remote.takeLast(MAX_ENTRIES))
            } else {
                // Keep everything already said in this session and splice the
                // older cloud turns in front of it.
                val known = entries.mapTo(HashSet()) { it.id }
                entries.addAll(0, remote.filter { it.id !in known })
                while (entries.size > MAX_ENTRIES) entries.removeFirst()
            }
            remote.lastOrNull()?.sessionId?.takeIf { it.isNotBlank() }?.let { sessionId = it }
        }
        Log.i(TAG, "loaded ${remote.size} messages from GitHub")
    }

    fun addUserMessage(text: String): MemoryEntry = append("user", text)

    fun addAssistantMessage(text: String): MemoryEntry = append("assistant", text)

    private fun append(
        speaker: String,
        text: String,
    ): MemoryEntry {
        val entry =
            MemoryEntry(
                id = UUID.randomUUID().toString(),
                speaker = speaker,
                text = text,
                timestamp = System.currentTimeMillis(),
                sessionId = sessionId,
            )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        schedulePush()
        return entry
    }

    /** Newest-last copy of the transcript. */
    fun getRecentMessages(limit: Int = MAX_ENTRIES): List<MemoryEntry> =
        synchronized(lock) { entries.takeLast(limit.coerceAtLeast(1)) }

    /**
     * The history handed to the model, oldest first, trimmed to a token budget.
     * Empty when there is nothing remembered yet.
     */
    fun getConversationContext(maxExchanges: Int = 5): String {
        val recent = getRecentMessages(maxExchanges.coerceAtLeast(1) * 2)
        if (recent.isEmpty()) return ""
        val lines = ArrayDeque<String>()
        var used = 0
        for (entry in recent.asReversed()) {
            val who = if (entry.speaker == "user") "USER" else "JARVIS"
            val line = "$who: ${entry.text.take(MAX_TURN_CHARS).trim()}"
            if (used + line.length > MAX_CONTEXT_CHARS) break
            used += line.length + 1
            lines.addFirst(line)
        }
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine("Earlier in this conversation (oldest first):")
            for (line in lines) appendLine(line)
        }
    }

    fun clearSession() = clearAll()

    fun clearAll() {
        synchronized(lock) { entries.clear() }
        sessionId = UUID.randomUUID().toString()
        schedulePush(force = true)
    }

    /** Serialized transcript in the format stored on GitHub. */
    fun toJson(): String {
        val messages = JSONArray()
        synchronized(lock) {
            for (entry in entries) {
                messages.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("speaker", entry.speaker)
                        .put("text", entry.text)
                        .put("timestamp", entry.timestamp)
                        .put("sessionId", entry.sessionId),
                )
            }
        }
        return JSONObject()
            .put("app", "JARVIS")
            .put("sessionId", sessionId)
            .put("updatedAt", System.currentTimeMillis())
            .put("messages", messages)
            .toString()
    }

    /** Save now and wait for the result. Safe to call from a background thread. */
    fun syncNow(): GithubPusher.PushResult {
        val client = pusher ?: return GithubPusher.PushResult(false, "GitHub is not configured.")
        val result = client.pushConversation(toJson(), REMOTE_PATH)
        if (!result.success) Log.w(TAG, "cloud save failed: ${result.message}")
        return result
    }

    private fun schedulePush(force: Boolean = false) {
        if (pusher == null) return
        if (!autoPush && !force) return
        if (pushQueued) return
        pushQueued = true
        worker.execute {
            try {
                Thread.sleep(PUSH_DEBOUNCE_MS)
                syncNow()
            } finally {
                pushQueued = false
            }
        }
    }

    private fun parse(json: String): List<MemoryEntry> {
        return try {
            val root = JSONObject(json)
            val fallbackSession = root.optString("sessionId")
            val arr = root.optJSONArray("messages") ?: return emptyList()
            val out = ArrayList<MemoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val text = item.optString("text")
                if (text.isEmpty()) continue
                out.add(
                    MemoryEntry(
                        id = item.optString("id").ifEmpty { UUID.randomUUID().toString() },
                        speaker = item.optString("speaker", "user"),
                        text = text,
                        timestamp = item.optLong("timestamp", 0L),
                        sessionId = item.optString("sessionId").ifEmpty { fallbackSession },
                    ),
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "could not parse cloud memory", e)
            emptyList()
        }
    }
}
