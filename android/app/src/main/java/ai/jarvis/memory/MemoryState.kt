package ai.jarvis.memory

import org.json.JSONObject

/** One record as the UI needs it; mirrors `core/src/memory.rs`. */
data class MemoryEntry(
    val id: String,
    val kind: String,
    val text: String,
    val source: String,
    val importance: Float,
    val createdAt: Long,
    val expiresAt: Long?,
    val approved: Boolean,
)

data class MemoryState(
    val entries: List<MemoryEntry> = emptyList(),
    val total: Int = 0,
    val active: Int = 0,
    val corrupt: Int = 0,
    val byKind: Map<String, Int> = emptyMap(),
    val query: String = "",
    val error: String? = null,
    val loading: Boolean = false,
)

/**
 * Parses the Rust memory responses. Kept pure so it is unit-testable without a
 * native library or an Android runtime.
 */
internal object MemoryJson {
    fun parse(
        response: String,
        query: String = "",
        loading: Boolean = false,
    ): MemoryState =
        runCatching {
            val root = JSONObject(response)
            if (!root.optBoolean("ok", false)) {
                val error = root.optJSONObject("error")
                return MemoryState(
                    query = query,
                    loading = loading,
                    error =
                        error?.optString("detail")?.takeIf { it.isNotBlank() }
                            ?: "The memory request failed.",
                )
            }
            val data = root.optJSONObject("data") ?: JSONObject()
            val stats = data.optJSONObject("stats") ?: JSONObject()
            val byKind = LinkedHashMap<String, Int>()
            stats.optJSONObject("byKind")?.let { kinds ->
                for (key in kinds.keys()) byKind[key] = kinds.optInt(key)
            }
            val records = data.optJSONArray("records")
            val entries =
                if (records == null) {
                    emptyList()
                } else {
                    (0 until records.length()).mapNotNull { index ->
                        records.optJSONObject(index)?.toEntry()
                    }
                }
            MemoryState(
                entries = entries,
                total = stats.optInt("total"),
                active = stats.optInt("active", entries.size),
                corrupt = stats.optInt("corrupt"),
                byKind = byKind,
                query = query,
                loading = loading,
            )
        }.getOrElse { error ->
            MemoryState(
                query = query,
                loading = loading,
                error = "Could not read the memory response: ${error.message ?: "malformed JSON"}",
            )
        }

    private fun JSONObject.toEntry(): MemoryEntry? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val text = optString("text")
        if (text.isBlank()) return null
        return MemoryEntry(
            id = id,
            kind = optString("kind", "unknown"),
            text = text,
            source = optString("source", "unknown"),
            importance = optDouble("importance", 0.0).toFloat(),
            createdAt = optLong("createdAt"),
            expiresAt = if (has("expiresAt") && !isNull("expiresAt")) optLong("expiresAt") else null,
            approved = optBoolean("userApproved", false),
        )
    }
}
