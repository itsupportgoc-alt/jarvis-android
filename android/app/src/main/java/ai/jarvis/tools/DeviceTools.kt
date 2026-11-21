package ai.jarvis.tools

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SetAlarmTool :
    Tool(
        ToolSpec(
            name = "set_alarm",
            description = "Create an alarm at a given hour and minute using the system clock app.",
            parameters =
                schema(
                    "hour" to intProp("24-hour hour, 0-23"),
                    "minute" to intProp("Minute, 0-59"),
                    "label" to stringProp("Optional alarm label"),
                ),
            required = listOf("hour", "minute"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.fail("That is not a valid time.")
        val intent =
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                args
                    .optString("label")
                    .takeIf { it.isNotBlank() }
                    ?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult.fail("This device has no clock app that accepts alarms.")
        }
        context.startActivity(intent)
        return ToolResult.ok(
            "Alarm set for %02d:%02d.".format(hour, minute),
            JSONObject().put("hour", hour).put("minute", minute),
        )
    }
}

class SetTimerTool :
    Tool(
        ToolSpec(
            name = "set_timer",
            description = "Start a countdown timer.",
            parameters =
                schema(
                    "seconds" to intProp("Duration in seconds"),
                    "label" to stringProp("Optional label"),
                ),
            required = listOf("seconds"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val seconds = args.optInt("seconds", 0)
        if (seconds <= 0) return ToolResult.fail("How long should the timer run?")
        val intent =
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                args
                    .optString("label")
                    .takeIf { it.isNotBlank() }
                    ?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult.fail("This device has no clock app that accepts timers.")
        }
        context.startActivity(intent)
        return ToolResult.ok("Timer started for $seconds seconds.")
    }
}

class TimeNowTool :
    Tool(
        ToolSpec(
            name = "what_time_is_it",
            description = "Report the current local time and date.",
            parameters = schema(),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)
        return ToolResult.ok(
            "It is $time.",
            JSONObject().put("time", time).put("date", date),
        )
    }
}

class CreateEventTool :
    Tool(
        ToolSpec(
            name = "create_event",
            description = "Open the calendar editor with a new event pre-filled. The user saves it.",
            parameters =
                schema(
                    "title" to stringProp("Event title"),
                    "start" to stringProp("Start, ISO-8601 local e.g. 2026-05-04T15:00"),
                    "duration_minutes" to intProp("Duration in minutes"),
                    "location" to stringProp("Optional location"),
                ),
            required = listOf("title", "start"),
            confirmation = Confirmation.REQUIRED,
            permissions = listOf(Manifest.permission.WRITE_CALENDAR),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return ToolResult.fail("What should the event be called?")
        val start =
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).parse(args.optString("start"))
            }.getOrNull() ?: return ToolResult.fail("I could not read that start time.")
        val duration = args.optInt("duration_minutes", 60).coerceIn(5, 24 * 60)
        val intent =
            Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.time)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.time + duration * 60_000L)
                args
                    .optString("location")
                    .takeIf { it.isNotBlank() }
                    ?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        return ToolResult.ok("The event editor is open for \"$title\".")
    }
}

class TodayEventsTool :
    Tool(
        ToolSpec(
            name = "read_today_events",
            description = "List today's calendar events.",
            parameters = schema("limit" to intProp("Maximum events, 1 to 20")),
            permissions = listOf(Manifest.permission.READ_CALENDAR),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.READ_CALENDAR)) {
            return ToolResult.fail("I need calendar access for that.", Manifest.permission.READ_CALENDAR)
        }
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val begin =
            Calendar
                .getInstance()
                .apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
        val end = begin + 24 * 60 * 60 * 1000L
        val uri =
            CalendarContract.Instances.CONTENT_URI
                .buildUpon()
                .appendPath(begin.toString())
                .appendPath(end.toString())
                .build()
        val events = JSONArray()
        context.contentResolver
            .query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                ),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                while (cursor.moveToNext() && events.length() < limit) {
                    events.put(
                        JSONObject().apply {
                            put("title", cursor.getString(0) ?: "")
                            put("start", fmt.format(Date(cursor.getLong(1))))
                            put("end", fmt.format(Date(cursor.getLong(2))))
                        },
                    )
                }
            }
        return ToolResult.ok(
            if (events.length() == 0) "Your calendar is clear today." else "You have ${events.length()} events today.",
            JSONObject().put("events", events),
        )
    }
}

class OpenAppTool :
    Tool(
        ToolSpec(
            name = "open_app",
            description = "Launch an installed app by name or package.",
            parameters = schema("name" to stringProp("App name or package id")),
            required = listOf("name"),
            permissions = listOf(Manifest.permission.QUERY_ALL_PACKAGES),
        ),
    ) {
    @Suppress("DEPRECATION")
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val query = args.optString("name").trim()
        if (query.isEmpty()) return ToolResult.fail("Which app?")
        val pm = context.packageManager
        val target =
            if (query.contains('.')) {
                query
            } else {
                pm
                    .getInstalledApplications(0)
                    .firstOrNull {
                        pm.getApplicationLabel(it).toString().equals(query, ignoreCase = true)
                    }?.packageName ?: pm
                    .getInstalledApplications(0)
                    .firstOrNull {
                        pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true)
                    }?.packageName
            }
        if (target == null) return ToolResult.fail("I could not find an app called \"$query\".")
        val intent =
            pm.getLaunchIntentForPackage(target)
                ?: return ToolResult.fail("\"$query\" has no launchable activity.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.ok("Opening $query.")
    }
}

class CloseAppTool :
    Tool(
        ToolSpec(
            name = "close_app",
            description =
                "Leave the current app. Android has no API to force-stop " +
                    "another app, so this returns to the home screen.",
            parameters = schema(),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val closed =
            ai.jarvis.service.JarvisAccessibilityService.instance
                ?.goHome() ?: false
        return if (closed) {
            ToolResult.ok("Closed.")
        } else {
            ToolResult.fail("I need the accessibility service enabled to leave an app.")
        }
    }
}

class ListAppsTool :
    Tool(
        ToolSpec(
            name = "list_installed_apps",
            description = "List apps that have a launcher icon.",
            parameters = schema("limit" to intProp("Maximum apps, 1 to 100")),
            permissions = listOf(Manifest.permission.QUERY_ALL_PACKAGES),
        ),
    ) {
    @Suppress("DEPRECATION")
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val pm = context.packageManager
        val limit = args.optInt("limit", 40).coerceIn(1, 100)
        val apps = JSONArray()
        pm
            .getInstalledApplications(0)
            .asSequence()
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }
            .take(limit)
            .forEach { info ->
                apps.put(
                    JSONObject().apply {
                        put("name", pm.getApplicationLabel(info).toString())
                        put("package", info.packageName)
                    },
                )
            }
        return ToolResult.ok("I found ${apps.length()} apps.", JSONObject().put("apps", apps))
    }
}

class BatteryTool :
    Tool(
        ToolSpec(
            name = "get_battery",
            description = "Report battery percentage and charging state.",
            parameters = schema(),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val intent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return ToolResult.fail("Battery state unavailable.")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = (level * 100) / scale
        val charging =
            intent
                .getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                .let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        return ToolResult.ok(
            "Battery is $percent percent${if (charging) " and charging" else ""}.",
            JSONObject().put("percent", percent).put("charging", charging),
        )
    }
}

class NetworkStatusTool :
    Tool(
        ToolSpec(
            name = "get_network_status",
            description = "Report whether the phone is online and on which transport.",
            parameters = schema(),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val manager =
            context.getSystemService(ConnectivityManager::class.java)
                ?: return ToolResult.fail("Connectivity service unavailable.")
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val transport =
            when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        return ToolResult.ok(
            if (transport == "offline") "We are offline." else "We are online on $transport.",
            JSONObject().put("transport", transport).put("metered", metered),
        )
    }
}

class BrightnessTool :
    Tool(
        ToolSpec(
            name = "set_brightness",
            description = "Set screen brightness, 0-100 percent. Needs the Write Settings special access.",
            parameters = schema("percent" to intProp("Brightness 0-100")),
            required = listOf("percent"),
            confirmation = Confirmation.REQUIRED,
            permissions = listOf(Manifest.permission.WRITE_SETTINGS),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!Settings.System.canWrite(context)) {
            return ToolResult.fail("I need Write Settings access for that.", Manifest.permission.WRITE_SETTINGS)
        }
        val percent = args.optInt("percent", 50).coerceIn(1, 100)
        val previous =
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128,
            )
        val value = (percent * 255) / 100
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val changed =
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value,
            )
        if (!changed) return ToolResult.fail("The system refused the brightness change.")
        return ToolResult(
            ok = true,
            speech = "Brightness at $percent percent.",
            undo =
                UndoAction("Brightness back") {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        previous,
                    )
                },
        )
    }
}

class VolumeTool :
    Tool(
        ToolSpec(
            name = "set_volume",
            description = "Set media volume, 0-100 percent.",
            parameters = schema("percent" to intProp("Volume 0-100")),
            required = listOf("percent"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val audio =
            context.getSystemService(AudioManager::class.java)
                ?: return ToolResult.fail("Audio service unavailable.")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val previous = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = args.optInt("percent", 50).coerceIn(0, 100)
        val index = (percent * max) / 100
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        return ToolResult(
            ok = true,
            speech = "Media volume at $percent percent.",
            undo =
                UndoAction("Volume back") {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0)
                },
        )
    }
}

class TorchTool :
    Tool(
        ToolSpec(
            name = "toggle_flashlight",
            description = "Turn the flashlight on or off.",
            parameters = schema("on" to boolProp("True to turn on")),
            required = listOf("on"),
            permissions = listOf(Manifest.permission.CAMERA),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.CAMERA)) {
            return ToolResult.fail("I need camera permission for the torch.", Manifest.permission.CAMERA)
        }
        val manager =
            context.getSystemService(CameraManager::class.java)
                ?: return ToolResult.fail("Camera service unavailable.")
        val cameraId =
            manager.cameraIdList.firstOrNull { id ->
                manager
                    .getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.fail("This device has no flashlight.")
        val on = args.optBoolean("on", true)
        manager.setTorchMode(cameraId, on)
        return ToolResult(
            ok = true,
            speech = if (on) "Light on." else "Light off.",
            undo =
                UndoAction(if (on) "Turn light off" else "Turn light on") {
                    runCatching { manager.setTorchMode(cameraId, !on) }
                },
        )
    }
}

class DndTool :
    Tool(
        ToolSpec(
            name = "set_dnd",
            description = "Turn Do Not Disturb on or off. Needs notification policy access.",
            parameters = schema("enabled" to boolProp("True for on")),
            required = listOf("enabled"),
            confirmation = Confirmation.REQUIRED,
            permissions = listOf(Manifest.permission.ACCESS_NOTIFICATION_POLICY),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val manager =
            context.getSystemService(NotificationManager::class.java)
                ?: return ToolResult.fail("Notification service unavailable.")
        if (!manager.isNotificationPolicyAccessGranted) {
            return ToolResult.fail(
                "I need notification policy access. Open Settings to grant it.",
                Manifest.permission.ACCESS_NOTIFICATION_POLICY,
            )
        }
        val enabled = args.optBoolean("enabled", true)
        val previous = manager.currentInterruptionFilter
        manager.setInterruptionFilter(
            if (enabled) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            },
        )
        return ToolResult(
            ok = true,
            speech = if (enabled) "Do Not Disturb is on." else "Do Not Disturb is off.",
            undo =
                UndoAction("Restore interruptions") {
                    runCatching { manager.setInterruptionFilter(previous) }
                },
        )
    }
}

class NavigateTool :
    Tool(
        ToolSpec(
            name = "navigate_to",
            description = "Open navigation to an address or place.",
            parameters = schema("destination" to stringProp("Address or place name")),
            required = listOf("destination"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val destination = args.optString("destination").trim()
        if (destination.isEmpty()) return ToolResult.fail("Where should I navigate?")
        val geo = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
        context.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.ok("Navigating to $destination.")
    }
}

class NearbyTool :
    Tool(
        ToolSpec(
            name = "find_nearby",
            description = "Search the map for something nearby.",
            parameters = schema("query" to stringProp("What to find, e.g. pharmacy")),
            required = listOf("query"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolResult.fail("What should I look for?")
        val geo = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        context.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.ok("Showing nearby $query.")
    }
}

class SearchWebTool :
    Tool(
        ToolSpec(
            name = "search_web",
            description = "Open a web search in the browser.",
            parameters = schema("query" to stringProp("Search terms")),
            required = listOf("query"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolResult.fail("What should I search for?")
        val intent =
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        return ToolResult.ok("Searching for $query.")
    }
}

class OpenUrlTool :
    Tool(
        ToolSpec(
            name = "open_url",
            description = "Open an http or https URL in the browser.",
            parameters = schema("url" to stringProp("Full http(s) URL")),
            required = listOf("url"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val url = args.optString("url").trim()
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
            return ToolResult.fail("Only http and https URLs can be opened.")
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.ok("Opening the page.")
    }
}
