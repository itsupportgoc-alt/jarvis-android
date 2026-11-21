package ai.jarvis.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every tool's contract is checked without an Android runtime: the registry's
 * tool construction only needs a notes file path, and schemas are pure JSON.
 *
 * These assertions guard the boundary Qwen sees: a malformed schema means the
 * model will emit arguments the handler cannot read.
 */
class ToolSchemaTest {
    private val tools: List<Tool> by lazy {
        ToolRegistry(File("build/tmp/jarvis-test-notes.txt")).all
    }

    @Test
    fun everyToolHasAUniqueNameAndDescription() {
        val names = mutableSetOf<String>()
        assertTrue("expected the full registry", tools.size >= 35)
        for (tool in tools) {
            assertTrue("duplicate tool name: ${tool.spec.name}", names.add(tool.spec.name))
            assertTrue("${tool.spec.name} has no description", tool.spec.description.isNotBlank())
            assertTrue(
                "${tool.spec.name} should be snake_case",
                tool.spec.name.matches(Regex("[a-z][a-z0-9_]*")),
            )
        }
    }

    @Test
    fun everySchemaIsAWellFormedObject() {
        for (tool in tools) {
            val function = tool.openAiSchema()
            assertEquals(tool.spec.name, function.getString("name"))
            val parameters: JSONObject = function.getJSONObject("parameters")
            assertEquals("object", parameters.getString("type"))
            assertEquals(false, parameters.getBoolean("additionalProperties"))

            val properties = parameters.getJSONObject("properties")
            val required = parameters.getJSONArray("required")
            for (index in 0 until required.length()) {
                val field = required.getString(index)
                assertTrue(
                    "${tool.spec.name}: required field \"$field\" is not declared",
                    properties.has(field),
                )
                val definition = properties.getJSONObject(field)
                assertTrue(
                    "${tool.spec.name}: \"$field\" has no type",
                    definition.has("type"),
                )
            }
        }
    }

    @Test
    fun outgoingActionsAreGatedAndRestrictedToolsAreMarked() {
        val byName = tools.associateBy { it.spec.name }
        for (name in listOf("call_contact", "send_sms", "send_whatsapp", "create_event")) {
            assertEquals(
                "$name must ask first",
                Confirmation.REQUIRED,
                byName.getValue(name).spec.confirmation,
            )
        }
        for (name in listOf("read_sms", "read_call_log", "read_screen_text")) {
            assertTrue("$name must be marked Play-restricted", byName.getValue(name).spec.restricted)
        }
        assertFalse(
            "get_battery should not ask",
            byName.getValue("get_battery").spec.confirmation == Confirmation.REQUIRED,
        )
    }

    @Test
    fun permissionStringsAreFullyQualified() {
        for (tool in tools) {
            for (permission in tool.spec.permissions) {
                assertTrue(
                    "${tool.spec.name}: $permission is not an Android permission",
                    permission.startsWith("android.permission."),
                )
            }
        }
    }
}
