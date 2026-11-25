package ai.jarvis.ui

import ai.jarvis.core.Speaker
import ai.jarvis.core.TranscriptLine
import ai.jarvis.ui.theme.JarvisCyan
import ai.jarvis.ui.theme.JarvisGold
import ai.jarvis.ui.theme.JarvisMuted
import ai.jarvis.ui.theme.MonoBody
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Transcript. Assistant in gold, user in cyan, partial hypotheses dimmed.
 *
 * The view stays pinned to the newest line: a reply is always visible without
 * scrolling. Pinning follows the user's intent, not the scroll position —
 * checking `canScrollForward` after content had already grown always reported
 * "not at the bottom", which is why the reply used to end up below the fold.
 * Dragging up pauses the follow; dragging back to the bottom resumes it.
 */
@Composable
fun TranscriptPanel(
    lines: List<TranscriptLine>,
    partial: String,
    streaming: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    /** True while the transcript should follow new content. */
    var follow by remember { mutableStateOf(true) }

    // Only a real drag changes the intent: scrolling up unpins, reaching the
    // bottom again re-pins. Programmatic scrolls land at the bottom, so they
    // leave it pinned either way.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling) follow = !canForward
            }
    }

    val itemCount by remember {
        derivedStateOf { listState.layoutInfo.totalItemsCount }
    }

    // The count is read from layout, so this runs after the new item exists and
    // cannot ask for an index the list does not have yet.
    LaunchedEffect(lines.size, streaming.length, partial.length, itemCount) {
        if (follow && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(12.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            TranscriptRow(
                label = if (line.speaker == Speaker.USER) "YOU" else "SEVEN",
                text = line.text,
                color = if (line.speaker == Speaker.USER) JarvisCyan else JarvisGold,
            )
        }
        if (streaming.isNotBlank()) {
            item(key = "streaming") {
                AssistantStreaming(streaming)
            }
        }
        if (partial.isNotBlank()) {
            item(key = "partial") {
                TranscriptRow(
                    label = "YOU",
                    text = partial,
                    color = JarvisCyan.copy(alpha = 0.55f),
                    italic = true,
                )
            }
        }
    }
}

@Composable
private fun AssistantStreaming(text: String) {
    TranscriptRow(
        label = "SEVEN",
        text = "$text▍",
        color = JarvisGold,
    )
}

@Composable
private fun TranscriptRow(
    label: String,
    text: String,
    color: androidx.compose.ui.graphics.Color,
    italic: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            color = JarvisMuted,
            modifier = Modifier.width(58.dp).padding(top = 3.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MonoBody,
                color = color,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                overflow = TextOverflow.Clip,
            )
        }
    }
    Spacer(Modifier.padding(bottom = 2.dp))
}
