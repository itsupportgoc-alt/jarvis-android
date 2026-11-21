package ai.jarvis.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class Confirmation { NONE, REQUIRED }

/** Capability light shown in Settings: green / amber / red. */
enum class CapabilityStatus { GRANTED, NEEDS_GRANT, BLOCKED }

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
    /** Fields the model must supply; merged into the schema by [Tool.openAiSchema]. */
    val required: List<String> = emptyList(),
    val confirmation: Confirmation = Confirmation.NONE,
    val permissions: List<String> = emptyList(),
    /** Google Play restricted permission: SMS, call log, accessibility. */
    val restricted: Boolean = false,
)

data class UndoAction(
    val label: String,
    val undo: () -> Unit,
)

class ToolResult(
    val ok: Boolean,
    val speech: String,
    val data: JSONObject = JSONObject(),
    val undo: UndoAction? = null,
    val missingPermission: String? = null,
) {
    fun toModelJson(): String =
        JSONObject()
            .apply {
                put("ok", ok)
                put("result", speech)
                put("data", data)
            }.toString()

    companion object {
        fun ok(
            speech: String,
            data: JSONObject = JSONObject(),
        ) = ToolResult(true, speech, data)

        fun fail(
            speech: String,
            permission: String? = null,
        ) = ToolResult(false, speech, JSONObject(), null, permission)
    }
}

abstract class Tool(
    val spec: ToolSpec,
) {
    abstract fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult

    fun openAiSchema(): JSONObject {
        // Copy so the declared `required` never mutates the shared schema object.
        val parameters = JSONObject(spec.parameters.toString())
        if (spec.required.isNotEmpty()) {
            parameters.put("required", JSONArray().apply { spec.required.forEach { put(it) } })
        }
        return JSONObject().apply {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", parameters)
        }
    }
}

// ---------------------------------------------------------------------------
// Schema helpers. Keeping these tiny makes every tool's contract obvious and
// lets ToolRegistryTest assert that required fields actually exist.
// ---------------------------------------------------------------------------

fun schema(
    vararg properties: Pair<String, JSONObject>,
    required: List<String> = emptyList(),
): JSONObject =
    JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                properties.forEach { (name, definition) -> put(name, definition) }
            },
        )
        put("required", JSONArray().apply { required.forEach { put(it) } })
        put("additionalProperties", false)
    }

fun stringProp(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)

fun intProp(description: String): JSONObject = JSONObject().put("type", "integer").put("description", description)

fun numberProp(description: String): JSONObject = JSONObject().put("type", "number").put("description", description)

fun boolProp(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)

object ToolPermissions {
    fun granted(
        context: Context,
        permission: String,
    ): Boolean = context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
