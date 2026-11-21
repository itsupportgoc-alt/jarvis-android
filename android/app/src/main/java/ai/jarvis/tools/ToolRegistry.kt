package ai.jarvis.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every capability JARVIS can reach, with its JSON-schema contract, permission
 * requirement and confirmation policy.
 *
 * Adding a tool is one entry in `buildTools`; the schema is sent to Qwen and the
 * same class executes the call, so the two can never drift.
 */
class ToolRegistry private constructor(
    private val appContext: Context?,
    notesFile: java.io.File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        java.io.File(context.applicationContext.filesDir, "notes.txt"),
    )

    /** Schema-only construction for host unit tests. */
    internal constructor(notesFile: java.io.File) : this(null, notesFile)

    private val tools: Map<String, Tool> = buildTools(notesFile).associateBy { it.spec.name }

    val all: List<Tool> get() = tools.values.toList()

    fun find(name: String): Tool? = tools[name]

    fun schemasJson(): JSONArray =
        JSONArray().apply {
            tools.values.sortedBy { it.spec.name }.forEach { put(it.openAiSchema()) }
        }

    fun needsConfirmation(name: String): Boolean = tools[name]?.spec?.confirmation == Confirmation.REQUIRED

    fun restrictedTools(): List<Tool> = tools.values.filter { it.spec.restricted }

    fun execute(
        name: String,
        args: JSONObject,
    ): ToolResult {
        val tool = tools[name] ?: return ToolResult.fail("Unknown tool \"$name\".")
        val context = appContext ?: return ToolResult.fail("No Android context available.")
        return runCatching { tool.execute(context, args) }.getOrElse { error ->
            ToolResult.fail("${tool.spec.name} failed: ${error.message ?: "unknown error"}")
        }
    }

    /** Human sentence for the confirmation sheet, e.g. "call Maria". */
    fun describe(
        name: String,
        args: JSONObject,
    ): String =
        when (name) {
            "call_contact" -> "call ${args.optString("name", "this contact")}"
            "send_sms" -> "message ${args.optString("to", "this contact")}"
            "send_whatsapp" -> "message ${args.optString("to", "this contact")} on WhatsApp"
            "create_event" -> "add \"${args.optString("title", "an event")}\" to your calendar"
            "set_dnd" -> if (args.optBoolean("enabled", true)) "turn on Do Not Disturb" else "turn off Do Not Disturb"
            "end_call" -> "end the current call"
            "set_brightness" -> "change screen brightness to ${args.optInt("percent", 50)} percent"
            "set_volume" -> "change media volume to ${args.optInt("percent", 50)} percent"
            "write_note" -> "save a note"
            "control_device" -> "control ${args.optString("device", "a smart device")}"
            else -> tools[name]?.spec?.description?.lowercase() ?: name.replace('_', ' ')
        }

    private fun buildTools(notesFile: java.io.File): List<Tool> =
        listOf(
            // Calls
            CallContactTool(),
            EndCallTool(),
            AnswerCallTool(),
            ReadCallLogTool(),
            // Messaging
            SendSmsTool(),
            ReadSmsTool(),
            SendWhatsAppTool(),
            // Contacts
            SearchContactsTool(),
            GetContactNumberTool(),
            // Alarms, time, calendar
            SetAlarmTool(),
            SetTimerTool(),
            TimeNowTool(),
            CreateEventTool(),
            TodayEventsTool(),
            // Apps and device state
            OpenAppTool(),
            CloseAppTool(),
            ListAppsTool(),
            BatteryTool(),
            NetworkStatusTool(),
            BrightnessTool(),
            VolumeTool(),
            TorchTool(),
            DndTool(),
            // Media
            MediaPlayTool(),
            MediaPauseTool(),
            MediaNextTool(),
            // Navigation and web
            NavigateTool(),
            NearbyTool(),
            SearchWebTool(),
            OpenUrlTool(),
            // Notifications and screen
            ReadNotificationsTool(),
            DismissNotificationTool(),
            ReadScreenTool(),
            TapElementTool(),
            ScrollTool(),
            // Smart home and notes
            ControlDeviceTool(),
            WriteNoteTool(notesFile),
        )
}
