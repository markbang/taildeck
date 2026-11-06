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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.bangwu.taildeck.ChatMessage
import top.bangwu.taildeck.R
import top.bangwu.taildeck.Todo

sealed interface ChatRow { val key: String }
data class MessageRow(val m: ChatMessage) : ChatRow { override val key get() = m.id }
data class ToolGroupRow(override val key: String, val items: List<ChatMessage>, val open: Boolean) : ChatRow
data class TurnFooterRow(override val key: String, val text: String, val durationMs: Long?, val endTs: Long) : ChatRow

enum class ChatDisplay(val key: String) {
    FULL("full"), TOOLS("tools"), TURNS("turns");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: TOOLS }
}

private fun ChatMessage.groupable() = role == "thinking" || role == "tool" && kind != "plan" && kind != "question"

/**
 * Chronological rows, one turn per user message. TOOLS folds runs of two or
 * more calls; TURNS also folds everything an answered turn did before its
 * final reply. The latest turn stays open while the agent works: no footer,
 * and its last run keeps showing the newest call.
 */
fun buildRows(messages: List<ChatMessage>, working: Boolean, display: ChatDisplay = ChatDisplay.TOOLS): List<ChatRow> {
    val turns = ArrayList<MutableList<ChatMessage>>()
    for (m in messages) {
        if (m.role == "user" || turns.isEmpty()) turns += ArrayList<ChatMessage>()
        turns.last() += m
    }
    val rows = ArrayList<ChatRow>()
    turns.forEachIndexed { i, turn ->
        val open = working && i == turns.lastIndex
        val user = turn.first().takeIf { it.role == "user" }
        var body: List<ChatMessage> = if (user != null) turn.drop(1) else turn
        if (user != null) rows += MessageRow(user)
        if (display == ChatDisplay.TURNS && !open) {
            val last = body.indexOfLast { it.role == "assistant" }
            if (last > 0) {
                rows += ToolGroupRow("t:" + body.first().id, body.subList(0, last), false)
                body = body.subList(last, body.size)
            }
        }
        val run = ArrayList<ChatMessage>()
        fun flush(live: Boolean) {
            if (display != ChatDisplay.FULL && run.count { it.role == "tool" } >= 2) rows += ToolGroupRow("g:" + run.first().id, run.toList(), live)
            else run.forEach { rows += MessageRow(it) }
            run.clear()
        }
        for (m in body) {
            if (m.groupable()) {
                run += m
            } else {
                flush(false)
                rows += MessageRow(m)
            }
        }
        flush(open)
        val replies = turn.filter { it.role == "assistant" }.map { it.text }
        if (!open && replies.isNotEmpty()) {
            val end = turn.maxOf { it.ts }
            val start = user?.ts ?: 0L
            rows += TurnFooterRow("f:" + (user?.id ?: "head"), replies.joinToString("\n\n"), if (start > 0 && end >= start) end - start else null, end)
        }
    }
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
    "plan" -> Icons.Outlined.Map
    "question" -> Icons.AutoMirrored.Outlined.HelpOutline
    else -> Icons.Outlined.Build
}

@Composable
internal fun toolLabel(m: ChatMessage): String = when (m.kind) {
    "shell" -> stringResource(R.string.tool_shell)
    "read" -> stringResource(R.string.tool_read)
    "edit" -> stringResource(R.string.tool_edit)
    "write" -> stringResource(R.string.tool_write)
    "search" -> stringResource(R.string.tool_search)
    "fetch" -> stringResource(R.string.tool_fetch)
    "task" -> stringResource(R.string.tool_task)
    "todo" -> stringResource(R.string.tool_todo)
    "plan" -> stringResource(R.string.tool_plan)
    "question" -> stringResource(R.string.tool_question)
    else -> m.name.ifEmpty { "tool" }
}

@Composable
fun ToolRow(m: ChatMessage, ctx: ToolContext, modifier: Modifier = Modifier) {
    Column(modifier) {
        ToolLine(m, ctx)
        if (m.images.isNotEmpty()) ImageStrip(m.images, Modifier.padding(start = 30.dp, top = 2.dp, bottom = 4.dp))
    }
}

@Composable
private fun ToolLine(m: ChatMessage, ctx: ToolContext, modifier: Modifier = Modifier) {
    // Claude describes each command in a few words; that reads better than the command itself.
    val described = m.kind == "shell" && m.text.isNotEmpty()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { ctx.onOpen(m) }.padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                (m.pending || m.background) && ctx.live -> LoadingIndicator(Modifier.size(16.dp))
                m.error -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                else -> Icon(toolIcon(m.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(toolLabel(m), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text(
            if (described) m.text else m.relativeSummary(ctx.cwd),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (described) null else FontFamily.Monospace,
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
    val other = tools.count { it.kind !in setOf("edit", "write", "read", "shell", "search", "plan", "question") }
    val parts = listOfNotNull(
        if (edited > 0) pluralStringResource(R.plurals.tools_edited, edited, edited) else null,
        if (read > 0) pluralStringResource(R.plurals.tools_read, read, read) else null,
        if (ran > 0) pluralStringResource(R.plurals.tools_ran, ran, ran) else null,
        if (searched > 0) pluralStringResource(R.plurals.tools_searched, searched, searched) else null,
        if (other > 0) pluralStringResource(R.plurals.tools_other, other, other) else null,
    ).ifEmpty { listOf(pluralStringResource(R.plurals.tools_steps, items.size, items.size)) }
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
            val running = if (row.open && ctx.live) row.items.lastOrNull { it.role == "tool" && it.pending } else null
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (running != null) LoadingIndicator(Modifier.size(16.dp))
                else Icon(Icons.Outlined.Layers, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                running?.let { "${toolLabel(it)} ${it.text.ifEmpty { it.relativeSummary(ctx.cwd) }}" } ?: groupSummary(row.items),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp).rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val shown = when {
            open -> row.items
            row.open -> listOf(row.items.last())
            else -> emptyList()
        }
        val images = row.items.flatMap { it.images }
        if (!open && images.isNotEmpty()) ImageStrip(images, Modifier.padding(start = 30.dp, top = 2.dp))
        if (shown.isNotEmpty()) {
            Column(
                Modifier.padding(start = 12.dp).drawBehind { drawLine(rail, Offset(0f, 0f), Offset(0f, size.height), 1.5.dp.toPx()) }.padding(start = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { shown.forEach { item(it) } }
        }
    }
}

/** Plans and questions are part of the conversation, so they render as cards. */
@Composable
fun ToolCard(m: ChatMessage, ctx: ToolContext, modifier: Modifier) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(m.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(toolLabel(m), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                when {
                    m.pending && ctx.live -> Text(stringResource(R.string.tool_waiting), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    m.error -> Text(stringResource(R.string.tool_declined), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Markdown(m.text.ifEmpty { m.summary })
            if (!m.pending && m.output.isNotBlank()) {
                Text(
                    m.output.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        IconButton(onClick = { context.copy(row.text) }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(32.dp)) {
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

// ---- tracks above the composer: task list and context usage ----

@Composable
fun TodoTrack(todos: List<Todo>, modifier: Modifier = Modifier) {
    if (todos.none { it.status != "completed" }) return
    var open by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 0f else 180f, label = "chevron")
    val done = todos.count { it.status == "completed" }
    val current = todos.firstOrNull { it.status == "in_progress" } ?: todos.firstOrNull { it.status != "completed" }
    Column(modifier.animateContentSize(Motion.slow())) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Checklist, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.todo_progress, done, todos.size), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text(
                if (open) "" else current?.text.orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp).rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, bottom = 4.dp)) {
                todos.forEach { t ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            when (t.status) {
                                "completed" -> Icons.Outlined.CheckCircle
                                "in_progress" -> Icons.Outlined.RadioButtonChecked
                                else -> Icons.Outlined.RadioButtonUnchecked
                            },
                            null,
                            Modifier.padding(top = 2.dp).size(15.dp),
                            tint = when (t.status) {
                                "completed" -> OnlineGreen
                                "in_progress" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (t.status == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (t.status == "completed") TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
        }
    }
}

fun formatTokens(n: Long): String = when {
    n < 1_000 -> "$n"
    n < 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fK", n / 1_000.0)
    else -> String.format(java.util.Locale.ROOT, "%.2fM", n / 1_000_000.0)
}

/** Tokens in context after the latest model call; a share only when the agent records its window. */
@Composable
fun ContextChip(used: Long, window: Long, model: String) {
    if (used <= 0) return
    var details by remember { mutableStateOf(false) }
    val pct = if (window > 0) (used * 100 / window).toInt() else null
    Text(
        listOfNotNull(stringResource(R.string.context_used, formatTokens(used)), pct?.let { "$it%" }).joinToString(" · "),
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { details = true }.padding(horizontal = 6.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if ((pct ?: 0) >= 85) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { details = false }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.context_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (model.isNotEmpty()) Text(stringResource(R.string.context_model, model))
                    Text(stringResource(R.string.context_tokens, "%,d".format(used)))
                    if (window > 0) Text(stringResource(R.string.context_window, "%,d".format(window), pct ?: 0))
                    else Text(stringResource(R.string.context_window_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}
