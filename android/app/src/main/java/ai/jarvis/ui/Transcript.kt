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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Transcript. Assistant in gold, user in cyan, partial hypotheses dimmed.
 *
 * Auto-scroll follows new content only while the user is already at the bottom;
 * scrolling up pauses it, and returning to the bottom resumes.
 */
@Composable
fun TranscriptPanel(
    lines: List<TranscriptLine>,
    partial: String,
    streaming: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }
    val extra = (if (streaming.isNotBlank()) 1 else 0) + (if (partial.isNotBlank()) 1 else 0)

    LaunchedEffect(lines.size, streaming.length, partial.length) {
        if (atBottom && (lines.isNotEmpty() || extra > 0)) {
            val target = (lines.size + extra - 1).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
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
                label = if (line.speaker == Speaker.USER) "YOU" else "JARVIS",
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
        label = "JARVIS",
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
