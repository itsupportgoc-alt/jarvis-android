package ai.jarvis.memory

import ai.jarvis.core.JarvisNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Thin client over the Rust memory engine.
 *
 * Retrieval, deduplication, expiry, encryption and approval rules live in
 * `core/src/memory.rs`. This class only turns UI intents into commands and
 * keeps a `StateFlow` for Compose. All calls run on a private IO scope: the UI
 * thread never touches the journal.
 */
class MemoryManager(
    private val handle: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MemoryState(loading = true))
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    fun refresh() = read(JSONObject().put("op", "memory.list").put("limit", LIST_LIMIT), query = "")

    fun search(query: String) {
        if (query.isBlank()) {
            refresh()
            return
        }
        read(
            JSONObject()
                .put("op", "memory.search")
                .put("query", query.trim())
                .put("limit", SEARCH_LIMIT),
            query = query.trim(),
        )
    }

    /** Saves a user-approved permanent fact. */
    fun add(
        text: String,
        kind: String,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        mutate(
            JSONObject()
                .put("op", "memory.add")
                .put("kind", kind)
                .put("text", trimmed)
                .put("source", "user")
                .put("userApproved", true),
        )
    }

    fun delete(id: String) = mutate(JSONObject().put("op", "memory.delete").put("id", id))

    fun clear() = mutate(JSONObject().put("op", "memory.clear"))

    /** Blocking; call from an IO coroutine (used by the export flow). */
    fun exportJson(): String {
        val current = handle()
        if (current == 0L) return """{"error":"The assistant engine is not running."}"""
        val response =
            runCatching {
                JarvisNative.nativeInvoke(
                    current,
                    JSONObject().put("op", "memory.export").toString(),
                )
            }.getOrNull() ?: return """{"error":"The memory export failed."}"""
        return runCatching {
            val root = JSONObject(response)
            if (root.optBoolean("ok")) {
                root.optJSONObject("data")?.toString(2) ?: "{}"
            } else {
                root.optJSONObject("error")?.optString("detail") ?: response
            }
        }.getOrDefault(response)
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun read(
        request: JSONObject,
        query: String,
    ) {
        scope.launch {
            _state.update { it.copy(loading = true, error = null, query = query) }
            val response = invoke(request.toString())
            _state.value = MemoryJson.parse(response, query = query)
        }
    }

    private fun mutate(request: JSONObject) {
        scope.launch {
            val response = invoke(request.toString())
            val parsed = MemoryJson.parse(response)
            if (parsed.error != null) {
                _state.update { it.copy(loading = false, error = parsed.error) }
                return@launch
            }
            // Re-read so the list and stats always come from the engine, not a
            // locally patched copy. Keep the active search filter.
            val query = _state.value.query
            val request =
                if (query.isBlank()) {
                    JSONObject().put("op", "memory.list").put("limit", LIST_LIMIT)
                } else {
                    JSONObject()
                        .put("op", "memory.search")
                        .put("query", query)
                        .put("limit", SEARCH_LIMIT)
                }
            val listed = invoke(request.toString())
            _state.value = MemoryJson.parse(listed, query = query)
        }
    }

    private fun invoke(json: String): String {
        val current = handle()
        if (current == 0L) {
            return errorResponse("notConfigured", "The assistant engine is not running.")
        }
        return runCatching { JarvisNative.nativeInvoke(current, json) }
            .getOrElse { error ->
                errorResponse("internal", error.message ?: "The native memory call failed.")
            }
    }

    private fun errorResponse(
        kind: String,
        detail: String,
    ): String =
        JSONObject()
            .put("ok", false)
            .put("error", JSONObject().put("kind", kind).put("detail", detail))
            .toString()

    private companion object {
        const val LIST_LIMIT = 50
        const val SEARCH_LIMIT = 30
    }
}
