package ai.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

private fun normaliseNumber(raw: String): String = raw.trim().replace(Regex("[^+0-9]"), "")

/**
 * Resolve a name or number against the contact provider. Returns null when the
 * name matches nothing; the caller reports that instead of guessing.
 */
private fun resolveNumber(
    context: Context,
    value: String,
): String? {
    if (value.any(Char::isDigit)) {
        val digits = normaliseNumber(value)
        if (digits.length >= 3) return digits
    }
    if (!ToolPermissions.granted(context, Manifest.permission.READ_CONTACTS)) return null
    val uri =
        Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(value),
        )
    context.contentResolver
        .query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return normaliseNumber(cursor.getString(0))
        }
    return null
}

class CallContactTool :
    Tool(
        ToolSpec(
            name = "call_contact",
            description = "Call a contact by name or phone number. Outgoing call, confirmed by the user.",
            parameters =
                schema(
                    "name" to stringProp("Contact name or phone number"),
                    "speaker" to boolProp("Route audio to the loudspeaker"),
                ),
            required = listOf("name"),
            confirmation = Confirmation.REQUIRED,
            permissions = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.CALL_PHONE)) {
            return ToolResult.fail("I need the phone permission to place calls.", Manifest.permission.CALL_PHONE)
        }
        val who = args.optString("name").trim()
        if (who.isEmpty()) return ToolResult.fail("Which contact should I call?")
        val number =
            resolveNumber(context, who)
                ?: return ToolResult.fail("I could not find \"$who\" in your contacts.")
        val intent =
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        return ToolResult.ok("Calling $who.")
    }
}

class EndCallTool :
    Tool(
        ToolSpec(
            name = "end_call",
            description = "End the call that is currently in progress.",
            parameters = schema(),
            permissions = listOf(Manifest.permission.ANSWER_PHONE_CALLS),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            return ToolResult.fail("I need call-control permission to hang up.", Manifest.permission.ANSWER_PHONE_CALLS)
        }
        val telecom =
            context.getSystemService(TelecomManager::class.java)
                ?: return ToolResult.fail("Telecom service unavailable.")

        @Suppress("DEPRECATION")
        val ended = telecom.endCall()
        return if (ended) ToolResult.ok("Call ended.") else ToolResult.fail("There is no call to end.")
    }
}

class AnswerCallTool :
    Tool(
        ToolSpec(
            name = "answer_call",
            description = "Answer the incoming call that is ringing now.",
            parameters = schema(),
            permissions = listOf(Manifest.permission.ANSWER_PHONE_CALLS),
            confirmation = Confirmation.REQUIRED,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            return ToolResult.fail("I need call-control permission to answer.", Manifest.permission.ANSWER_PHONE_CALLS)
        }
        val telecom =
            context.getSystemService(TelecomManager::class.java)
                ?: return ToolResult.fail("Telecom service unavailable.")
        telecom.acceptRingingCall()
        return ToolResult.ok("Answering.")
    }
}

class ReadCallLogTool :
    Tool(
        ToolSpec(
            name = "read_call_log",
            description = "Read the most recent calls. Returns name/number, type and when.",
            parameters = schema("limit" to intProp("How many entries, 1 to 20")),
            permissions = listOf(Manifest.permission.READ_CALL_LOG),
            restricted = true,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.READ_CALL_LOG)) {
            return ToolResult.fail("I need call-log access for that.", Manifest.permission.READ_CALL_LOG)
        }
        val limit = args.optInt("limit", 5).coerceIn(1, 20)
        val entries = JSONArray()
        context.contentResolver
            .query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && entries.length() < limit) {
                    entries.put(
                        JSONObject().apply {
                            put("name", cursor.getString(0) ?: "")
                            put("number", cursor.getString(1) ?: "")
                            put(
                                "type",
                                when (cursor.getInt(2)) {
                                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                    CallLog.Calls.MISSED_TYPE -> "missed"
                                    else -> "other"
                                },
                            )
                            put("at", cursor.getLong(3))
                        },
                    )
                }
            }
        return ToolResult.ok("I found ${entries.length()} recent calls.", JSONObject().put("calls", entries))
    }
}

class SendSmsTool :
    Tool(
        ToolSpec(
            name = "send_sms",
            description = "Send an SMS to a contact or number. Irreversible; the host confirms first.",
            parameters =
                schema(
                    "to" to stringProp("Contact name or phone number"),
                    "message" to stringProp("Message body"),
                ),
            required = listOf("to", "message"),
            confirmation = Confirmation.REQUIRED,
            permissions = listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS),
            restricted = true,
        ),
    ) {
    @Suppress("DEPRECATION")
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.SEND_SMS)) {
            return ToolResult.fail("I need SMS permission to send that.", Manifest.permission.SEND_SMS)
        }
        val to = args.optString("to").trim()
        val message = args.optString("message").trim()
        if (to.isEmpty() || message.isEmpty()) return ToolResult.fail("I need both a recipient and a message.")
        val number =
            resolveNumber(context, to)
                ?: return ToolResult.fail("I could not find \"$to\".")
        val manager = SmsManager.getDefault()
        val parts = manager.divideMessage(message)
        manager.sendMultipartTextMessage(number, null, parts, null, null)
        return ToolResult.ok(
            "Message sent to $to.",
            JSONObject().put("to", number).put("chars", message.length),
        )
    }
}

class ReadSmsTool :
    Tool(
        ToolSpec(
            name = "read_sms",
            description = "Read recent SMS messages. Restricted permission; never uploaded.",
            parameters =
                schema(
                    "limit" to intProp("How many messages, 1 to 10"),
                    "from" to stringProp("Optional sender filter"),
                ),
            permissions = listOf(Manifest.permission.READ_SMS),
            restricted = true,
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.READ_SMS)) {
            return ToolResult.fail("I need SMS read access for that.", Manifest.permission.READ_SMS)
        }
        val limit = args.optInt("limit", 3).coerceIn(1, 10)
        val messages = JSONArray()
        val selection = args.optString("from").takeIf { it.isNotBlank() }?.let { "address LIKE ?" }
        val selectionArgs = selection?.let { arrayOf("%${args.optString("from")}%") }
        context.contentResolver
            .query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                selection,
                selectionArgs,
                "date DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && messages.length() < limit) {
                    messages.put(
                        JSONObject().apply {
                            put("from", cursor.getString(0) ?: "")
                            // Truncate: the model only needs the gist, and shorter tool
                            // results keep the prompt (and latency) small.
                            put("body", (cursor.getString(1) ?: "").take(280))
                            put("at", cursor.getLong(2))
                        },
                    )
                }
            }
        return ToolResult.ok("I found ${messages.length()} messages.", JSONObject().put("messages", messages))
    }
}

class SendWhatsAppTool :
    Tool(
        ToolSpec(
            name = "send_whatsapp",
            description = "Open WhatsApp with a pre-filled message to a contact. The user still presses send.",
            parameters =
                schema(
                    "to" to stringProp("Contact name or phone number"),
                    "message" to stringProp("Message text"),
                ),
            required = listOf("to", "message"),
            confirmation = Confirmation.REQUIRED,
            permissions = listOf(Manifest.permission.READ_CONTACTS),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val to = args.optString("to").trim()
        val message = args.optString("message")
        val number = resolveNumber(context, to) ?: return ToolResult.fail("I could not find \"$to\".")
        val url = "https://wa.me/$number?text=${Uri.encode(message)}"
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return ToolResult.ok("WhatsApp is open with the message ready.")
    }
}

/**
 * A messaging app JARVIS can hand a message to.
 *
 * `deepLink` builds a URI that carries both recipient and text, which only some
 * apps support by phone number; the rest are opened with a share intent and the
 * user picks the chat. Package names also appear in the manifest `<queries>`,
 * otherwise API 30+ hides these apps from the resolver.
 */
private class MessagingTarget(
    val id: String,
    val label: String,
    val packageName: String,
    val deepLink: ((number: String, text: String) -> String)?,
)

private val MESSAGING_TARGETS =
    listOf(
        MessagingTarget("whatsapp", "WhatsApp", "com.whatsapp") { number, text ->
            "https://wa.me/$number?text=${Uri.encode(text)}"
        },
        MessagingTarget("sms", "Messages", "com.android.mms") { number, text ->
            "smsto:$number?body=${Uri.encode(text)}"
        },
        MessagingTarget("messenger", "Messenger", "com.facebook.orca", null),
        MessagingTarget("telegram", "Telegram", "org.telegram.messenger", null),
        MessagingTarget("signal", "Signal", "org.thoughtcrime.securesms", null),
        MessagingTarget("instagram", "Instagram", "com.instagram.android", null),
    )

class SendAppMessageTool :
    Tool(
        ToolSpec(
            name = "send_message",
            description =
                "Open a messaging app with a message ready to send. Apps: whatsapp, messenger, " +
                    "telegram, signal, instagram, sms. The user still presses send.",
            parameters =
                schema(
                    "app" to stringProp("Messaging app: whatsapp, messenger, telegram, signal, instagram or sms"),
                    "to" to stringProp("Contact name or phone number"),
                    "message" to stringProp("Message text"),
                ),
            required = listOf("app", "to", "message"),
            confirmation = Confirmation.REQUIRED,
            permissions = listOf(Manifest.permission.READ_CONTACTS),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val requested = args.optString("app").trim().ifEmpty { "whatsapp" }
        val message = args.optString("message").trim()
        if (message.isEmpty()) return ToolResult.fail("I need a message to send.")
        val to = args.optString("to").trim()
        if (to.isEmpty()) return ToolResult.fail("I need a recipient.")
        val target =
            MESSAGING_TARGETS.firstOrNull {
                it.id.equals(requested, ignoreCase = true) || it.label.equals(requested, ignoreCase = true)
            } ?: return ToolResult.fail(
                "I can message with ${MESSAGING_TARGETS.joinToString(", ") { it.label }}.",
            )
        val installed =
            runCatching { context.packageManager.getLaunchIntentForPackage(target.packageName) != null }
                .getOrDefault(false)

        // Apps that accept recipient + text in a URI get the message pre-filled.
        val deepLink = target.deepLink
        if (deepLink != null) {
            val number =
                resolveNumber(context, to)
                    ?: return ToolResult.fail("I could not find \"$to\".")
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(deepLink(number, message)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return ToolResult.ok(
                "${target.label} is open with the message to $to ready.",
                JSONObject().put("app", target.id).put("to", number),
            )
        }

        // Everything else: share the text into that app, or at worst open it.
        val share =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, message)
                .setPackage(target.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val shared = installed && runCatching { context.startActivity(share) }.isSuccess
        if (!shared) {
            val launch = context.packageManager.getLaunchIntentForPackage(target.packageName)
            if (launch == null) {
                return ToolResult.fail(
                    "${target.label} is not installed. WhatsApp, Messages and SMS always work.",
                )
            }
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ToolResult.ok(
                "${target.label} is open. Pick $to and paste the message; it could not be filled in automatically.",
            )
        }
        return ToolResult.ok(
            "${target.label} is open with the message ready for $to; choose the chat if it asks.",
            JSONObject().put("app", target.id),
        )
    }
}

class SearchContactsTool :
    Tool(
        ToolSpec(
            name = "search_contacts",
            description = "Find contacts whose name matches a query. Returns names and numbers.",
            parameters =
                schema(
                    "query" to stringProp("Name fragment"),
                    "limit" to intProp("Maximum results, 1 to 10"),
                ),
            required = listOf("query"),
            permissions = listOf(Manifest.permission.READ_CONTACTS),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.READ_CONTACTS)) {
            return ToolResult.fail("I need contact access for that.", Manifest.permission.READ_CONTACTS)
        }
        val query = args.optString("query")
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val found = JSONArray()
        val uri =
            Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(query),
            )
        context.contentResolver
            .query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && found.length() < limit) {
                    found.put(
                        JSONObject().apply {
                            put("name", cursor.getString(0) ?: "")
                            put("number", cursor.getString(1) ?: "")
                        },
                    )
                }
            }
        return ToolResult.ok(
            if (found.length() == 0) "No contacts matched." else "I found ${found.length()} contacts.",
            JSONObject().put("contacts", found),
        )
    }
}

class GetContactNumberTool :
    Tool(
        ToolSpec(
            name = "get_contact_number",
            description = "Look up one contact's phone number.",
            parameters = schema("name" to stringProp("Contact name")),
            required = listOf("name"),
            permissions = listOf(Manifest.permission.READ_CONTACTS),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        if (!ToolPermissions.granted(context, Manifest.permission.READ_CONTACTS)) {
            return ToolResult.fail("I need contact access for that.", Manifest.permission.READ_CONTACTS)
        }
        val name = args.optString("name")
        val number =
            resolveNumber(context, name)
                ?: return ToolResult.fail("I could not find \"$name\".")
        // Never speak the number unless asked; surface it on screen only.
        return ToolResult.ok(
            "I've put the number on screen.",
            JSONObject().put("name", name).put("number", number),
        )
    }
}
