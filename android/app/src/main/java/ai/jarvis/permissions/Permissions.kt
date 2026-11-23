package ai.jarvis.permissions

import ai.jarvis.service.JarvisAccessibilityService
import ai.jarvis.tools.CapabilityStatus
import ai.jarvis.tools.Tool
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Permission choreography helpers. The UI asks for one capability at a time,
 * immediately after the user requests the feature that needs it; nothing is
 * requested on launch.
 */
object Permissions {
    const val MIC = Manifest.permission.RECORD_AUDIO

    private val SPECIAL =
        setOf(
            Manifest.permission.WRITE_SETTINGS,
            Manifest.permission.ACCESS_NOTIFICATION_POLICY,
        )

    fun granted(
        context: Context,
        permission: String,
    ): Boolean = context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun hasAccessibility(context: Context): Boolean {
        val enabled =
            Settings.Secure
                .getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
        return enabled.contains("${context.packageName}/${JarvisAccessibilityService::class.java.name}")
    }

    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun hasDndAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    fun status(
        context: Context,
        tool: Tool,
    ): CapabilityStatus {
        if (tool.spec.name in setOf("read_notifications", "dismiss_notification")) {
            return if (hasNotificationAccess(context)) {
                CapabilityStatus.GRANTED
            } else {
                CapabilityStatus.NEEDS_GRANT
            }
        }
        if (tool.spec.name in setOf("read_screen_text", "tap_element", "scroll", "close_app")) {
            return if (hasAccessibility(context)) {
                CapabilityStatus.GRANTED
            } else {
                CapabilityStatus.NEEDS_GRANT
            }
        }
        if (tool.spec.permissions.isEmpty() || tool.spec.permissions.all { it == Manifest.permission.INTERNET }) {
            return CapabilityStatus.GRANTED
        }
        val missing =
            tool.spec.permissions.firstOrNull { permission ->
                when (permission) {
                    Manifest.permission.WRITE_SETTINGS -> !canWriteSettings(context)
                    Manifest.permission.ACCESS_NOTIFICATION_POLICY -> !hasDndAccess(context)
                    Manifest.permission.QUERY_ALL_PACKAGES -> false
                    else -> !granted(context, permission)
                }
            }
        return if (missing == null) CapabilityStatus.GRANTED else CapabilityStatus.NEEDS_GRANT
    }

    /** Tools that Google Play restricts; shown with an explicit policy note. */
    fun isRestricted(tool: Tool): Boolean = tool.spec.restricted

    /** App info screen, for permissions that can no longer be requested in-app. */
    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openWriteSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openNotificationAccess(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAccessibility(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openDndAccess(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun isSpecial(permission: String): Boolean = permission in SPECIAL
}
