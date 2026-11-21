package ai.jarvis.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * The only service in the app with broad visibility, and deliberately narrow:
 *
 * * reads window text on demand (never in the background),
 * * taps a node the user named, or dispatches a single tap gesture,
 * * scrolls and goes home.
 *
 * Password fields are skipped, results are capped, and nothing leaves the
 * device: `read_screen_text` returns text to Qwen only as part of a tool result
 * the user requested. The accessibility indicator stays visible while enabled.
 */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        val seen = HashSet<String>()
        walk(root) { node ->
            if (builder.length < MAX_CHARS && !node.isPassword) {
                val text =
                    node.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                if (text.isNotEmpty() && seen.add(text)) {
                    builder.append(text).append('\n')
                }
                if (builder.length < MAX_CHARS) {
                    node.contentDescription
                        ?.toString()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { if (seen.add(it)) builder.append(it).append('\n') }
                }
            }
            builder.length < MAX_CHARS
        }
        return builder.toString().trim().take(MAX_CHARS)
    }

    fun tapByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val needle = label.trim().lowercase()
        if (needle.isEmpty()) return false
        val target =
            findNode(root) { node ->
                node.text
                    ?.toString()
                    ?.lowercase()
                    ?.contains(needle) == true ||
                    node.contentDescription
                        ?.toString()
                        ?.lowercase()
                        ?.contains(needle) == true
            } ?: return false

        var clickable: AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val bounds = android.graphics.Rect()
        target.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findNode(root) { it.isScrollable } ?: return false
        val action =
            if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
        return scrollable.performAction(action)
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun tapAt(
        x: Float,
        y: Float,
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture =
            GestureDescription
                .Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun walk(
        root: AccessibilityNodeInfo,
        visit: (AccessibilityNodeInfo) -> Boolean,
    ) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (!visit(node)) return
            if (depth >= MAX_DEPTH) continue
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (predicate(node)) {
                found = node
                false
            } else {
                true
            }
        }
        return found
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        private const val MAX_NODES = 2500
        private const val MAX_DEPTH = 60
        private const val MAX_CHARS = 4000
    }
}
