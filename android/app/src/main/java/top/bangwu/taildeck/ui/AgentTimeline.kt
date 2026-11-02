package top.bangwu.taildeck.ui

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.bangwu.taildeck.ChatMessage
import top.bangwu.taildeck.R
import kotlin.math.abs

sealed interface ChatRow { val key: String }
data class MessageRow(val m: ChatMessage) : ChatRow { override val key get() = m.id }
data class ToolGroupRow(override val key: String, val items: List<ChatMessage>, val open: Boolean) : ChatRow
data class TurnFooterRow(override val key: String, val text: String, val durationMs: Long?, val endTs: Long) : ChatRow

/**
 * Chronological rows. Runs with two or more tool calls fold into one group;
 * every answered turn ends with a footer. The latest turn stays open while
 * the agent works, so it gets no footer and its last run shows its newest call.
 */
fun buildRows(messages: List<ChatMessage>, working: Boolean): List<ChatRow> {
    val rows = ArrayList<ChatRow>()
    val run = ArrayList<ChatMessage>()
    var turnUser: ChatMessage? = null
    val replies = ArrayList<String>()
    var lastTs = 0L

    fun flush(open: Boolean) {
        if (run.count { it.role == "tool" } >= 2) rows += ToolGroupRow("g:" + run.first().id, run.toList(), open)
        else run.forEach { rows += MessageRow(it) }
        run.clear()
    }
    fun closeTurn() {
        if (replies.isNotEmpty()) {
            val start = turnUser?.ts ?: 0L
            val duration = if (start > 0 && lastTs >= start) lastTs - start else null
            rows += TurnFooterRow("f:" + (turnUser?.id ?: "head"), replies.joinToString("\n\n"), duration, lastTs)
        }
        replies.clear()
    }

    for (m in messages) {
        if (m.role == "user") {
            flush(false)
            closeTurn()
            turnUser = m
            lastTs = m.ts
            rows += MessageRow(m)
            continue
        }
        if (m.ts > lastTs) lastTs = m.ts
        if (m.role == "tool" || m.role == "thinking") {
            run += m
            continue
        }
        flush(false)
        rows += MessageRow(m)
        if (m.role == "assistant") replies += m.text
    }
    flush(working)
    if (!working) closeTurn()
    return rows
}

/** What a tool row needs from the screen around it. */
class ToolContext(val cwd: String?, val live: Boolean, val onOpen: (ChatMessage) -> Unit)

fun ChatMessage.relativeSummary(cwd: String?): String {
    if (cwd.isNullOrEmpty() || cwd == "/") return summary
    return summary.split(", ").joinToString(", ") { it.removePrefix("$cwd/") }
}

private fun toolIcon(kind: String): ImageVector = when (kind) {
    "shell" -> Icons.Outlined.Terminal
    "read" -> Icons.Outlined.Description
    "edit" -> Icons.Outlined.Edit
    "write" -> Icons.Outlined.EditNote
    "search" -> Icons.Outlined.Search
    "fetch" -> Icons.Outlined.Language
    "task" -> Icons.Outlined.AccountTree
    "todo" -> Icons.Outlined.Checklist
    else -> Icons.Outlined.Build
}

@Composable
private fun toolLabel(m: ChatMessage): String = when (m.kind) {
    "shell" -> stringResource(R.string.tool_shell)
    "read" -> stringResource(R.string.tool_read)
    "edit" -> stringResource(R.string.tool_edit)
    "write" -> stringResource(R.string.tool_write)
    "search" -> stringResource(R.string.tool_search)
    "fetch" -> stringResource(R.string.tool_fetch)
    "task" -> stringResource(R.string.tool_task)
    "todo" -> stringResource(R.string.tool_todo)
    else -> m.name.ifEmpty { "tool" }
}

@Composable
fun ToolRow(m: ChatMessage, ctx: ToolContext, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { ctx.onOpen(m) }.padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                m.pending && ctx.live -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.8.dp)
                m.error -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                else -> Icon(toolIcon(m.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(toolLabel(m), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text(
            m.relativeSummary(ctx.cwd),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun groupSummary(items: List<ChatMessage>): String {
    val tools = items.filter { it.role == "tool" }
    fun distinct(vararg kinds: String) = tools.filter { it.kind in kinds }.map { it.summary.ifEmpty { it.id } }.distinct().size
    val edited = distinct("edit", "write")
    val read = distinct("read")
    val ran = tools.count { it.kind == "shell" }
    val searched = tools.count { it.kind == "search" }
    val other = tools.count { it.kind !in setOf("edit", "write", "read", "shell", "search") }
    val parts = listOfNotNull(
        if (edited > 0) pluralStringResource(R.plurals.tools_edited, edited, edited) else null,
        if (read > 0) pluralStringResource(R.plurals.tools_read, read, read) else null,
        if (ran > 0) pluralStringResource(R.plurals.tools_ran, ran, ran) else null,
        if (searched > 0) pluralStringResource(R.plurals.tools_searched, searched, searched) else null,
        if (other > 0) pluralStringResource(R.plurals.tools_other, other, other) else null,
    )
    return parts.joinToString(stringResource(R.string.list_separator)).replaceFirstChar { it.titlecase() }
}

@Composable
fun ToolGroup(row: ToolGroupRow, ctx: ToolContext, modifier: Modifier, item: @Composable (ChatMessage) -> Unit) {
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val rail = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth().animateContentSize(Motion.slow())) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (row.open && ctx.live && row.items.any { it.pending }) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.8.dp)
                else Icon(Icons.Outlined.Layers, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(groupSummary(row.items), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp).rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val shown = when {
            open -> row.items
            row.open -> listOf(row.items.last())
            else -> emptyList()
        }
        if (shown.isNotEmpty()) {
            Column(
                Modifier.padding(start = 12.dp).drawBehind { drawLine(rail, Offset(0f, 0f), Offset(0f, size.height), 1.5.dp.toPx()) }.padding(start = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { shown.forEach { item(it) } }
        }
    }
}

@Composable
fun DiffView(diff: String, maxLines: Int = Int.MAX_VALUE) {
    val add = OnlineGreen
    val del = MaterialTheme.colorScheme.error
    val meta = MaterialTheme.colorScheme.primary
    val plain = MaterialTheme.colorScheme.onSurfaceVariant
    val lines = diff.lines()
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp).width(IntrinsicSize.Max)) {
            lines.take(maxLines).forEach { l ->
                val (bg, fg) = when {
                    l.startsWith("***") || l.startsWith("@@") || l.startsWith("+++") || l.startsWith("---") -> Color.Transparent to meta
                    l.startsWith("+") -> add.copy(alpha = 0.16f) to MaterialTheme.colorScheme.onSurface
                    l.startsWith("-") -> del.copy(alpha = 0.16f) to MaterialTheme.colorScheme.onSurface
                    else -> Color.Transparent to plain
                }
                Text(
                    l.ifEmpty { " " },
                    Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp),
                    color = fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    softWrap = false,
                )
            }
            if (lines.size > maxLines) {
                Text("…", Modifier.padding(horizontal = 12.dp), color = plain, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSheet(m: ChatMessage, cwd: String?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (m.error) Icons.Outlined.ErrorOutline else toolIcon(m.kind),
                    null,
                    Modifier.size(18.dp),
                    tint = if (m.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(toolLabel(m), style = MaterialTheme.typography.titleMedium)
                if (m.kind != "other" && m.name.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(m.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (m.summary.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                SelectionContainer {
                    Text(m.relativeSummary(cwd), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(12.dp))
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        m.diff.isNotEmpty() -> DiffView(m.diff)
                        m.input.isNotEmpty() -> CodeBlock(m.input, stringResource(R.string.agent_tool_input))
                    }
                    if (m.output.isNotEmpty()) CodeBlock(m.output, stringResource(if (m.error) R.string.agent_tool_error else R.string.agent_tool_output), maxLines = 400)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
fun durationLabel(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> stringResource(R.string.duration_s, s)
        s < 3600 -> stringResource(R.string.duration_ms, s / 60, s % 60)
        else -> stringResource(R.string.duration_hm, s / 3600, s % 3600 / 60)
    }
}

@Composable
fun TurnFooter(row: TurnFooterRow, modifier: Modifier) {
    val context = LocalContext.current
    var showTime by remember { mutableStateOf(false) }
    LaunchedEffect(showTime) {
        if (showTime) {
            delay(3000)
            showTime = false
        }
    }
    val worked = row.durationMs?.let { stringResource(R.string.agent_worked_for, durationLabel(it)) }
    val time = row.endTs.takeIf { it > 0 }?.let {
        val date = if (DateUtils.isToday(it)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_TIME or date)
    }
    Row(modifier.padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { context.copy(row.text) }, Modifier.size(32.dp)) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.agent_copy_turn), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val label = if (showTime || worked == null) time else worked
        if (label != null) {
            Text(
                label,
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = worked != null && time != null) { showTime = !showTime }.padding(4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Seconds since [since] (a node timestamp), ticking; null when unknown or skewed. */
@Composable
fun liveElapsed(since: Long): Long? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val d = now - since
    return if (since > 0 && d in 0..86_400_000) d else null
}

// ---- prompts drawn by the agent's TUI while it waits (permission, questions) ----

data class ScreenPrompt(val question: String?, val options: List<String>, val selected: Int)

private const val BOX_CHARS = "│┃|╭╮╰╯─━┌┐└┘"
private val OPTION = Regex("""^([❯›>▶→●]\s*)?(\d{1,2})[.)]\s+(.+)$""")
private val KEY_HINT = Regex("""\s*\((esc|[a-z])\)$""")
private val NEGATIVE = Regex("""^(no\b|deny|reject|cancel|否|不|拒绝|取消)""", RegexOption.IGNORE_CASE)

/**
 * Finds the last numbered choice list on screen, as drawn by Claude Code,
 * Codex and similar TUIs. Wrapped descriptions between options are allowed.
 */
fun parseScreenPrompt(screen: String): ScreenPrompt? {
    val lines = screen.lines().map { it.trim().trim { c -> c in BOX_CHARS }.trim() }
    var best: ScreenPrompt? = null
    var start = -1
    var options = ArrayList<String>()
    var selected = -1
    var gap = 0
    lines.forEachIndexed { i, l ->
        val m = OPTION.find(l)
        val n = m?.groupValues?.get(2)?.toInt()
        if (m != null && n == 1) {
            start = i
            options = arrayListOf()
            selected = -1
        }
        if (m != null && start >= 0 && n == options.size + 1) {
            if (m.groupValues[1].isNotEmpty()) selected = options.size
            options += m.groupValues[3].replace(KEY_HINT, "").trim()
            gap = 0
            if (options.size >= 2) {
                val above = lines.subList(maxOf(0, start - 8), start).filter { it.isNotBlank() }
                val q = above.lastOrNull { it.endsWith("?") || it.endsWith("？") } ?: above.lastOrNull()
                best = ScreenPrompt(q, options.toList(), selected)
            }
        } else if (start >= 0 && l.isNotEmpty() && ++gap > 3) {
            start = -1
        }
    }
    return best
}

/** Keys that pick option [i]: move the cursor when one is drawn, else type its number. */
fun ScreenPrompt.keysFor(i: Int): List<String> {
    if (selected < 0) return listOf("${i + 1}")
    val d = i - selected
    return List(abs(d)) { if (d > 0) "down" else "up" } + "enter"
}

@Composable
fun PromptOptions(prompt: ScreenPrompt, enabled: Boolean, onPick: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        prompt.question?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        prompt.options.forEachIndexed { i, label ->
            val text = @Composable {
                Text("${i + 1}. $label", Modifier.fillMaxWidth(), textAlign = TextAlign.Start, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val modifier = Modifier.fillMaxWidth()
            when {
                NEGATIVE.containsMatchIn(label) -> OutlinedButton(
                    onClick = { onPick(prompt.keysFor(i)) },
                    enabled = enabled,
                    modifier = modifier,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { text() }
                i == 0 -> Button(onClick = { onPick(prompt.keysFor(i)) }, enabled = enabled, modifier = modifier) { text() }
                else -> FilledTonalButton(onClick = { onPick(prompt.keysFor(i)) }, enabled = enabled, modifier = modifier) { text() }
            }
        }
    }
}
