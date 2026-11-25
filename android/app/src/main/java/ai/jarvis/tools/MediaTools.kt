package ai.jarvis.tools

import ai.jarvis.service.JarvisAccessibilityService
import ai.jarvis.service.JarvisNotificationListener
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun mediaKey(
    context: Context,
    keyCode: Int,
): Boolean {
    val audio = context.getSystemService(AudioManager::class.java) ?: return false
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    return true
}

class MediaPlayTool :
    Tool(
        ToolSpec(
            name = "play_music",
            description = "Resume playback in the active media app.",
            parameters = schema(),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val ok = mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        return if (ok) ToolResult.ok("Playing.") else ToolResult.fail("No media session available.")
    }
}

class MediaPauseTool :
    Tool(
        ToolSpec(
            name = "pause_music",
            description = "Pause the active media app.",
            parameters = schema(),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val ok = mediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        return if (ok) ToolResult.ok("Paused.") else ToolResult.fail("No media session available.")
    }
}

class MediaNextTool :
    Tool(
        ToolSpec(
            name = "next_track",
            description = "Skip to the next track.",
            parameters = schema(),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val ok = mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        return if (ok) ToolResult.ok("Next track.") else ToolResult.fail("No media session available.")
    }
}

class ReadNotificationsTool :
    Tool(
        ToolSpec(
            name = "read_notifications",
            description = "Read currently visible notifications. Requires notification access.",
            parameters = schema("limit" to intProp("Maximum notifications, 1 to 20")),
            permissions = emptyList(),
            restricted = true,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!hasNotificationAccess(context)) {
            return ToolResult.fail("I need notification access. Grant it in Settings, then try again.")
        }
        val limit = args.optInt("limit", 5).coerceIn(1, 20)
        val entries = JarvisNotificationListener.snapshot(limit)
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("app", entry.app)
                    put("title", entry.title)
                    put("text", entry.text.take(200))
                    put("at", entry.time)
                },
            )
        }
        return ToolResult.ok(
            if (array.length() == 0) "There are no notifications." else "You have ${array.length()} notifications.",
            JSONObject().put("notifications", array),
        )
    }
}

class DismissNotificationTool :
    Tool(
        ToolSpec(
            name = "dismiss_notification",
            description = "Dismiss a notification by title or app name.",
            parameters = schema("match" to stringProp("Title or app name")),
            required = listOf("match"),
            restricted = true,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!hasNotificationAccess(context)) {
            return ToolResult.fail("I need notification access for that.")
        }
        val match = args.optString("match").lowercase(Locale.getDefault())
        val target =
            JarvisNotificationListener.snapshot(20).firstOrNull {
                it.title.lowercase(Locale.getDefault()).contains(match) ||
                    it.app.lowercase(Locale.getDefault()).contains(match)
            } ?: return ToolResult.fail("I could not find a notification matching \"$match\".")
        val dismissed = JarvisNotificationListener.dismiss(target.key)
        return if (dismissed) {
            ToolResult.ok(
                "Dismissed.",
            )
        } else {
            ToolResult.fail("The notification could not be dismissed.")
        }
    }
}

class ReadScreenTool :
    Tool(
        ToolSpec(
            name = "read_screen_text",
            description =
                "Read the visible text of the current screen. Stays on device " +
                    "and requires the accessibility service.",
            parameters = schema(),
            restricted = true,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!hasAccessibility(context)) {
            return ToolResult.fail("The accessibility service is off. Enable it in Settings to read the screen.")
        }
        val text = JarvisAccessibilityService.instance?.readScreenText().orEmpty()
        if (text.isBlank()) return ToolResult.fail("I could not read anything on this screen.")
        return ToolResult.ok("I've read the screen.", JSONObject().put("text", text.take(4000)))
    }
}

class TapElementTool :
    Tool(
        ToolSpec(
            name = "tap_element",
            description = "Tap a visible control by its label using the accessibility service.",
            parameters = schema("text" to stringProp("Visible label or content description")),
            required = listOf("text"),
            restricted = true,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!hasAccessibility(context)) {
            return ToolResult.fail("The accessibility service is off. Enable it in Settings to tap.")
        }
        val label = args.optString("text")
        val tapped = JarvisAccessibilityService.instance?.tapByText(label) ?: false
        return if (tapped) {
            ToolResult.ok(
                "Tapped \"$label\".",
            )
        } else {
            ToolResult.fail("I could not find \"$label\" on screen.")
        }
    }
}

class ScrollTool :
    Tool(
        ToolSpec(
            name = "scroll",
            description =
                "Scroll the current screen using the accessibility service. " +
                    "Use times (up to 30) to keep scrolling a feed in one call, e.g. " +
                    "\"scroll the Facebook feed\" is open_app then scroll times=10.",
            parameters =
                schema(
                    "direction" to
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("up").put("down")),
                    "times" to intProp("How many scrolls, 1 to 30"),
                    "pause_ms" to intProp("Pause between scrolls in milliseconds, 0 to 2000"),
                ),
            required = listOf("direction"),
            restricted = true,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!hasAccessibility(context)) {
            return ToolResult.fail("The accessibility service is off. Enable it in Settings to scroll.")
        }
        val down = args.optString("direction", "down") != "up"
        val times = args.optInt("times", 1).coerceIn(1, MAX_SCROLLS)
        val pause = args.optInt("pause_ms", DEFAULT_PAUSE_MS).coerceIn(0, 2000)
        var done = 0
        for (index in 0 until times) {
            val scrolled = JarvisAccessibilityService.instance?.scroll(down) ?: false
            if (!scrolled) break
            done++
            if (index < times - 1 && pause > 0) Thread.sleep(pause.toLong())
        }
        return when {
            done == 0 -> ToolResult.fail("Nothing to scroll here.")
            done < times ->
                ToolResult.ok(
                    "Scrolled $done ${if (done == 1) "time" else "times"}, then the screen ran out of content.",
                )
            times == 1 -> ToolResult.ok("Scrolled.")
            else -> ToolResult.ok("Scrolled $times times.")
        }
    }

    private companion object {
        const val MAX_SCROLLS = 30
        const val DEFAULT_PAUSE_MS = 350
    }
}

/**
 * Keeps tapping one control, which is what "keep playing" actually means on a
 * phone: Play again, Continue, Claim, Next. Bounded by a count and by the
 * label disappearing, so it cannot run away tapping a screen that changed.
 */
class AutoTapTool :
    Tool(
        ToolSpec(
            name = "auto_tap",
            description =
                "Tap the same visible control repeatedly — Play, Play again, Continue, " +
                    "Claim, Next — to keep a game or a feed going. Open the app first. " +
                    "Stops when the label disappears or times is reached (max 100).",
            parameters =
                schema(
                    "text" to stringProp("Visible label or content description"),
                    "times" to intProp("How many taps, 1 to 100"),
                    "pause_ms" to intProp("Pause between taps in milliseconds, 100 to 5000"),
                ),
            required = listOf("text"),
            restricted = true,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!hasAccessibility(context)) {
            return ToolResult.fail("The accessibility service is off. Enable it in Settings to tap.")
        }
        val label = args.optString("text").trim()
        if (label.isEmpty()) return ToolResult.fail("Which button should I tap?")
        val times = args.optInt("times", 20).coerceIn(1, MAX_TAPS)
        val pause = args.optInt("pause_ms", 800).coerceIn(100, 5000)
        var taps = 0
        for (index in 0 until times) {
            if (JarvisAccessibilityService.instance?.tapByText(label) != true) break
            taps++
            if (index < times - 1) Thread.sleep(pause.toLong())
        }
        return when {
            taps == 0 -> ToolResult.fail("I could not find \"$label\" on screen.")
            taps == 1 -> ToolResult.ok("Tapped \"$label\" once, then it was gone.")
            else -> ToolResult.ok("Tapped \"$label\" $taps times, then it was gone.")
        }
    }

    private companion object {
        const val MAX_TAPS = 100
    }
}

class ControlDeviceTool :
    Tool(
        ToolSpec(
            name = "control_device",
            description = "Send a command to a smart-home webhook configured in Settings (HTTP POST JSON).",
            parameters =
                schema(
                    "device" to stringProp("Device name"),
                    "action" to stringProp("Action, e.g. on, off, set"),
                    "value" to stringProp("Optional value"),
                ),
            required = listOf("device", "action"),
            confirmation = Confirmation.REQUIRED,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val webhook = prefs.getString("webhook_url", "").orEmpty()
        if (webhook.isBlank()) return ToolResult.fail("No smart-home webhook is configured.")
        val url =
            runCatching { URL(webhook) }.getOrNull()
                ?: return ToolResult.fail("The smart-home webhook URL is invalid.")
        if (url.protocol != "https" && url.protocol != "http") {
            return ToolResult.fail("The webhook must be http or https.")
        }
        val payload =
            JSONObject().apply {
                put("device", args.optString("device"))
                put("action", args.optString("action"))
                if (args.has("value")) put("value", args.optString("value"))
                put("source", "jarvis")
            }
        val connection =
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = connection.responseCode
            if (code in 200..299) {
                ToolResult.ok("${args.optString("device")} ${args.optString("action")}.")
            } else {
                ToolResult.fail("The smart-home endpoint returned HTTP $code.")
            }
        } catch (error: Exception) {
            ToolResult.fail("The smart-home endpoint could not be reached.")
        } finally {
            connection.disconnect()
        }
    }
}

class WriteNoteTool(
    private val file: File,
) : Tool(
        ToolSpec(
            name = "write_note",
            description = "Append a note to JARVIS's local notes file.",
            parameters = schema("text" to stringProp("Note body")),
            required = listOf("text"),
            confirmation = Confirmation.REQUIRED,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return ToolResult.fail("There is nothing to save.")
        val previous = if (file.exists()) file.readText() else ""
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        file.appendText("[$stamp] $text\n")
        return ToolResult(
            ok = true,
            speech = "Noted.",
            data = JSONObject().put("file", file.name),
            undo =
                UndoAction("Undo note") {
                    runCatching { file.writeText(previous) }
                },
        )
    }
}

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun hasAccessibility(context: Context): Boolean {
    val enabled =
        Settings.Secure
            .getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
    return enabled.contains("${context.packageName}/${JarvisAccessibilityService::class.java.name}")
}
