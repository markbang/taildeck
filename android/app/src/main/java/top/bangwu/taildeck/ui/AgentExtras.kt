package top.bangwu.taildeck.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.bangwu.taildeck.AgentQueued
import top.bangwu.taildeck.AgentSettings
import top.bangwu.taildeck.ChatMessage
import top.bangwu.taildeck.HerdrHub
import top.bangwu.taildeck.R
import top.bangwu.taildeck.TaildeckApp
import top.bangwu.taildeck.Transcript

/** The node the open chat belongs to, for components that fetch from it. */
val LocalAgentNode = compositionLocalOf { "" }

// ---- images ----

private suspend fun decode(bytes: ByteArray, maxSide: Int): Bitmap? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun rememberRemoteBitmap(ref: String, maxSide: Int): Bitmap? {
    val node = LocalAgentNode.current
    val bitmap by produceState<Bitmap?>(null, node, ref, maxSide) {
        value = runCatching { decode(TaildeckApp.instance.herdr.image(node, ref), maxSide) }.getOrNull()
    }
    return bitmap
}

/** An image from the agent's machine; hidden when it can't be fetched. */
@Composable
fun RemoteImage(ref: String, maxHeight: Dp = 220.dp, modifier: Modifier = Modifier) {
    val bitmap = rememberRemoteBitmap(ref, 1200)
    var viewing by remember { mutableStateOf(false) }
    if (bitmap == null) return
    Image(
        bitmap.asImageBitmap(),
        null,
        modifier.heightIn(max = maxHeight).widthIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).clickable { viewing = true },
        contentScale = ContentScale.Fit,
    )
    if (viewing) ImageViewer(ref) { viewing = false }
}

@Composable
fun ImageStrip(refs: List<String>, modifier: Modifier = Modifier, maxHeight: Dp = 140.dp) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        refs.distinct().forEach { RemoteImage(it, maxHeight) }
    }
}

@Composable
private fun ImageViewer(ref: String, onClose: () -> Unit) {
    val bitmap = rememberRemoteBitmap(ref, 4096)
    var scale by remember { mutableFloatStateOf(1f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                dx += pan.x
                                dy += pan.y
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = dx, translationY = dy),
                    contentScale = ContentScale.Fit,
                )
            } ?: LoadingIndicator()
        }
    }
}

// ---- transcript entries beyond messages and tools ----

/** A slash command the user ran, shown like a sent message. */
@Composable
fun CommandChip(text: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun CommandOutput(text: String, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val lines = text.lines()
    Surface(
        onClick = { open = !open },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().animateContentSize(Motion.slow()),
    ) {
        SelectionContainer {
            Text(
                if (open || lines.size <= 6) text else lines.take(6).joinToString("\n") + "\n…",
                Modifier.padding(10.dp).horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NoteCard(icon: @Composable () -> Unit, title: String, text: String, modifier: Modifier, footer: String = "") {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (text.isNotEmpty()) Markdown(text)
            if (footer.isNotEmpty()) {
                Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A quiet centred line for interrupts, compactions, errors and background-job ends. */
@Composable
fun EventLine(text: String, modifier: Modifier, detail: String = "", error: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize(Motion.slow()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = detail.isNotEmpty()) { open = !open }.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(18.dp).size(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(18.dp).size(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        if (open && detail.isNotEmpty()) CodeBlock(detail, maxLines = 40)
    }
}

// ---- a prompt the agent is waiting on ----

/**
 * The agent's question with its choices as rows. Every answer first re-reads
 * the screen and goes nowhere if the question changed in the meantime.
 */
@Composable
fun PromptPanel(
    node: String,
    pane: String,
    screen: String,
    tool: ChatMessage?,
    toolCtx: ToolContext,
    onScreen: () -> Unit,
    onTerminal: () -> Unit,
    onChanged: () -> Unit,
    terminal: @Composable () -> Unit,
) {
    fun hub() = TaildeckApp.instance.herdr
    val scope = rememberCoroutineScope()
    val prompt = remember(screen) { parsePrompt(screen) }
    var chosen by remember(prompt?.question) { mutableStateOf<String?>(null) }
    var editing by remember(prompt?.question) { mutableStateOf<Int?>(null) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showKeys by rememberSaveable { mutableStateOf(false) }

    suspend fun confirm(seen: ScreenPrompt): ScreenPrompt? {
        val now = runCatching { parsePrompt(hub().screen(node, pane)) }.getOrNull()
        if (now == null || !now.sameQuestion(seen)) {
            onScreen()
            onChanged()
            return null
        }
        return now
    }
    suspend fun press(p: ScreenPrompt, index: Int) {
        val moves = p.moveTo(p.rowOf(index))
        if (moves != null) hub().sendKeys(node, pane, moves + "enter") else hub().sendText(node, pane, p.options[index].number)
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        busy = true
        runCatching { block() }
        delay(300)
        onScreen()
        busy = false
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(12.dp).animateContentSize(Motion.slow())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentDot(top.bangwu.taildeck.AgentStatus.BLOCKED)
                Spacer(Modifier.width(8.dp))
                Text(prompt?.question ?: stringResource(R.string.agent_needs_you), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(shapes = ButtonDefaults.shapes(), onClick = onTerminal) { Text(stringResource(R.string.ssh)) }
            }
            val ctx = prompt?.context
            if (ctx != null) {
                Spacer(Modifier.size(6.dp))
                PromptContextView(ctx)
            } else if (tool != null) {
                Spacer(Modifier.size(8.dp))
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        ToolRow(tool, ToolContext(toolCtx.cwd, live = false, toolCtx.onOpen))
                        when {
                            tool.diff.isNotEmpty() -> DiffView(tool.diff, maxLines = 14)
                            tool.kind == "shell" && tool.input.isNotEmpty() -> CodeBlock(tool.input.substringBeforeLast("\n{\n"), maxLines = 8)
                        }
                        if (tool.diff.isNotEmpty() || tool.kind == "shell") Spacer(Modifier.size(6.dp))
                    }
                }
            }
            if (prompt != null) {
                Spacer(Modifier.size(8.dp))
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(4.dp)) {
                        prompt.options.forEachIndexed { i, o ->
                            PromptOptionRow(
                                o,
                                chosen = chosen == o.number,
                                editing = editing == i,
                                typed = typed,
                                enabled = !busy,
                                onTyped = { typed = it },
                                onClick = {
                                    when {
                                        prompt.isFreeText(i) -> {
                                            typed = if (o.label.startsWith("Type something")) "" else o.label
                                            editing = i
                                        }
                                        o.checked != null -> act { confirm(prompt)?.let { press(it, i) } }
                                        else -> {
                                            chosen = o.number
                                            act { confirm(prompt)?.let { press(it, i) } }
                                        }
                                    }
                                },
                                onSubmitText = {
                                    val words = typed.trim()
                                    editing = null
                                    if (words.isNotEmpty()) act {
                                        val now = confirm(prompt) ?: return@act
                                        val moves = now.moveTo(now.rowOf(i)) ?: return@act
                                        val old = now.options[i].label
                                        val erase = if (old.startsWith("Type something")) 0 else old.codePointCount(0, old.length)
                                        hub().sendKeys(node, pane, moves + List(erase) { "backspace" })
                                        hub().sendText(node, pane, words)
                                        if (now.submit == null) {
                                            chosen = now.options[i].number
                                            hub().sendKeys(node, pane, listOf("enter"))
                                        }
                                    }
                                },
                            )
                        }
                        val submit = prompt.submit
                        if (submit != null && prompt.cursor != null) {
                            FilledTonalButton(
                                shapes = ButtonDefaults.shapes(),
                                onClick = {
                                    act {
                                        val now = confirm(prompt) ?: return@act
                                        now.moveTo(submit.row)?.let { hub().sendKeys(node, pane, it + "enter") }
                                    }
                                },
                                enabled = !busy && prompt.options.any { it.checked == true },
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                            ) { Text(submit.label) }
                        }
                    }
                }
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { showKeys = !showKeys }) {
                    Text(stringResource(if (showKeys) R.string.agent_hide_terminal_keys else R.string.agent_terminal_keys))
                }
            }
            if (prompt == null || showKeys) terminal()
        }
    }
}

@Composable
private fun PromptContextView(ctx: PromptContext) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(ctx.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ctx.detail.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        ctx.command?.let { cmd ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.Top) {
                    Text(cmd, Modifier.weight(1f).padding(vertical = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { context.copy(cmd) }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy), Modifier.size(15.dp)) }
                }
            }
        }
        ctx.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PromptOptionRow(
    o: PromptOption,
    chosen: Boolean,
    editing: Boolean,
    typed: String,
    enabled: Boolean,
    onTyped: (String) -> Unit,
    onClick: () -> Unit,
    onSubmitText: () -> Unit,
) {
    val negative = Regex("^(no\\b|deny|reject|cancel|否|不|拒绝|取消)", RegexOption.IGNORE_CASE).containsMatchIn(o.label)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (o.selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(enabled = enabled && !editing, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(o.number, Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        o.checked?.let {
            Icon(if (it) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(18.dp), tint = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
        }
        if (editing) {
            TextField(
                typed,
                onTyped,
                Modifier.weight(1f),
                placeholder = { Text(if (o.label.startsWith("Type something")) stringResource(R.string.prompt_type_something) else o.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmitText() }),
                trailingIcon = { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onSubmitText) { Icon(Icons.Outlined.Done, null) } },
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
            )
        } else {
            Text(
                o.label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (chosen) Icon(Icons.Outlined.Done, null, Modifier.size(16.dp))
    }
}

// ---- while the agent works ----

@Composable
private fun InkDots() {
    val t = rememberInfiniteTransition(label = "ink")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(6.dp).alpha(a).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

fun stopwatch(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** What the agent is doing right now, for how long, and a way to stop it. */
@Composable
fun WorkingBar(activity: String?, since: Long, subagents: Int, onSubagents: () -> Unit, onStop: () -> Unit) {
    val elapsed = liveElapsed(since)
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                InkDots()
                Spacer(Modifier.width(10.dp))
                Text(activity ?: stringResource(R.string.agent_working), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                elapsed?.let { Text(stopwatch(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(shapes = ButtonDefaults.shapes(), onClick = onStop) {
                    Icon(Icons.Outlined.Stop, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.agent_stop))
                }
            }
        }
        AnimatedVisibility(subagents > 0, enter = fadeIn(), exit = fadeOut()) {
            Surface(onClick = onSubagents, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(50), modifier = Modifier.padding(start = 6.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountTree, stringResource(R.string.subagents_working, subagents), Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$subagents", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

fun runningSubagents(t: Transcript?): List<ChatMessage> =
    t?.messages.orEmpty().filter { it.role == "tool" && it.kind == "task" && (it.pending || it.background) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentListSheet(subagents: List<ChatMessage>, onOpen: (ChatMessage) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.subagents_title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            subagents.forEach { s ->
                val elapsed = liveElapsed(s.ts)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InkDots()
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.summary.ifEmpty { stringResource(R.string.tool_task) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.text.ifEmpty { "general-purpose" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    elapsed?.let { Text(stopwatch(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** A subagent's own steps, read from its transcript and refreshed while it runs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentSheet(node: String, pane: String, task: ChatMessage, onDismiss: () -> Unit, item: @Composable (ChatMessage, ToolContext) -> Unit) {
    val hub = TaildeckApp.instance.herdr
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var openTool by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(task.call) {
        while (true) {
            runCatching { hub.subagent(node, pane, task.call) }
                .onSuccess { transcript = it.first; error = null }
                .onFailure { error = it.message?.substringAfter(": ") }
            if (!task.pending && !task.background) break
            delay(2500)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(task.summary.ifEmpty { stringResource(R.string.tool_task) }, style = MaterialTheme.typography.titleMedium)
            Text(task.text.ifEmpty { "general-purpose" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            val t = transcript
            val ctx = ToolContext(null, task.pending || task.background) { openTool = it.id }
            when {
                t != null -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(t.messages, key = { it.id }) { item(it, ctx) }
                    if (task.output.isNotEmpty() && !task.pending) item { NoteCard({ Icon(Icons.Outlined.Done, null, Modifier.size(16.dp)) }, stringResource(R.string.subagent_result), task.output, Modifier) }
                }
                error != null -> {
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.output.isNotEmpty()) Markdown(task.output)
                }
                else -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
            Spacer(Modifier.size(24.dp))
        }
        openTool?.let { id -> transcript?.messages?.firstOrNull { it.id == id }?.let { ToolSheet(it, null) { openTool = null } } }
    }
}

// ---- session details, model / effort switching and commands ----

private val CLAUDE_MODEL = Regex("^claude-([a-z]+)-(\\d+)(?:-(\\d{1,2}))?(?:-\\d{8})?(\\[1m])?$", RegexOption.IGNORE_CASE)

fun modelDisplayName(model: String): String {
    val m = CLAUDE_MODEL.find(model.trim()) ?: return model.trim().substringAfterLast('/')
    val version = listOf(m.groupValues[2], m.groupValues[3]).filter { it.isNotEmpty() }.joinToString(".")
    return m.groupValues[1].replaceFirstChar { it.titlecase() } + " " + version + if (m.groupValues[4].isNotEmpty()) " (1M)" else ""
}

@Composable
fun effortLabel(e: String): String = when (e) {
    "off", "none" -> stringResource(R.string.effort_off)
    "minimal" -> stringResource(R.string.effort_minimal)
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_xhigh)
    "max" -> stringResource(R.string.effort_max)
    "ultra" -> stringResource(R.string.effort_ultra)
    "auto" -> stringResource(R.string.effort_auto)
    else -> e
}

@Composable
private fun modeLabel(m: String): String = when (m) {
    "plan" -> stringResource(R.string.mode_plan)
    "acceptEdits" -> stringResource(R.string.mode_accept_edits)
    "bypassPermissions" -> stringResource(R.string.mode_bypass)
    "auto" -> stringResource(R.string.mode_auto)
    "dontAsk" -> stringResource(R.string.mode_dont_ask)
    else -> m
}

/** Harnesses whose model and reasoning level can be switched with a command. */
fun canSwitchModel(kind: String) = kind in setOf("claude", "codex", "pi", "omp")

/** Whether [current] (as recorded in the transcript) is the model [id] names. */
fun isCurrentModel(kind: String, current: String, id: String): Boolean = when (kind) {
    "claude" -> current.contains(id, ignoreCase = true)
    // pi ids are provider/model (the model part may hold slashes too); sessions may record just the model.
    "pi", "omp" -> current == id || id.substringAfter('/') == current || current.substringAfterLast('/') == id.substringAfterLast('/')
    else -> current == id
}

/** The command that switches to [model], keeping [effort] where the model supports it. */
fun modelCommand(kind: String, model: String, effort: String?): String = when (kind) {
    "codex" -> "/model $model" + (effort?.let { " --reasoning=$it" } ?: "")
    else -> "/model $model"
}

fun effortCommand(kind: String, effort: String): String = when (kind) {
    "claude" -> "/effort $effort"
    "codex" -> "/model --reasoning=$effort"
    else -> "/thinking $effort"
}

/**
 * The row above the composer: model · effort (tap to switch), commands,
 * permission mode, branch and context in use.
 */
@Composable
fun AgentControlsBar(
    kind: String,
    settings: AgentSettings,
    contextUsed: Long,
    window: Long,
    onModel: () -> Unit,
    onCommands: () -> Unit,
) {
    val switchable = canSwitchModel(kind)
    if (!switchable && settings.model.isEmpty() && contextUsed <= 0 && settings.gitBranch.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (switchable || settings.model.isNotEmpty()) {
            Pill(onClick = if (switchable) onModel else null) {
                Icon(Icons.Outlined.Psychology, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    settings.model.takeIf { it.isNotEmpty() }?.let(::modelDisplayName) ?: stringResource(R.string.session_model),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (settings.effort.isNotEmpty()) {
                    Text(" · " + effortLabel(settings.effort), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (switchable) Icon(Icons.Outlined.ExpandMore, null, Modifier.size(14.dp))
            }
        }
        Pill(onClick = onCommands) {
            Text("/", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.commands), style = MaterialTheme.typography.labelMedium)
        }
        if (settings.permissionMode.isNotEmpty() && settings.permissionMode != "default") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(3.dp))
                Text(modeLabel(settings.permissionMode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (settings.gitBranch.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.CallSplit, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(3.dp))
                Text(settings.gitBranch, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        ContextChip(contextUsed, window, settings.model)
    }
}

@Composable
private fun Pill(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(50)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/**
 * Pick a model and a reasoning level with taps. Each choice is sent to the
 * agent as its own command (/model, /effort, /thinking).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ModelSheet(
    node: String,
    pane: String,
    kind: String,
    settings: AgentSettings,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onCommand: (command: String, model: String?, effort: String?) -> Unit,
) {
    val options by produceState<HerdrHub.ModelOptions?>(null, node, kind) {
        value = runCatching { TaildeckApp.instance.herdr.modelOptions(node, kind, pane) }
            .getOrElse { HerdrHub.ModelOptions(emptyList(), emptyList(), custom = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val o = options
        if (o == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        else ModelChoices(kind, o, settings, enabled, onCommand)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ModelChoices(kind: String, o: HerdrHub.ModelOptions, settings: AgentSettings, enabled: Boolean, onCommand: (String, String?, String?) -> Unit) {
    var custom by remember { mutableStateOf("") }
    run {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            if (!enabled) {
                Text(stringResource(R.string.model_switch_busy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(stringResource(R.string.session_model), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            val current = o.models.firstOrNull { isCurrentModel(kind, settings.model, it.id) }
            if (o.models.isEmpty()) {
                Text(
                    stringResource(if (settings.model.isEmpty()) R.string.model_none_known else R.string.model_current, modelDisplayName(settings.model)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            o.models.forEach { m ->
                val selected = m == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable(enabled = enabled && !selected) {
                            val keep = settings.effort.takeIf { it in m.efforts }
                            // Codex falls back to the model's default level when none is given.
                            onCommand(modelCommand(kind, m.id, keep), m.id, keep ?: m.default.ifEmpty { null })
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge)
                        if (!m.name.equals(m.id, ignoreCase = true)) Text(m.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected) Icon(Icons.Outlined.Done, null, Modifier.size(18.dp))
                }
            }
            if (o.custom) {
                Spacer(Modifier.size(6.dp))
                TextField(
                    custom,
                    { custom = it.trim() },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(if (kind == "codex") R.string.model_custom_codex else R.string.model_custom_pi)) },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { if (custom.isNotEmpty()) onCommand(modelCommand(kind, custom, null), custom, null) }),
                    trailingIcon = {
                        IconButton(shapes = IconButtonDefaults.shapes(), onClick = { onCommand(modelCommand(kind, custom, null), custom, null) }, enabled = enabled && custom.isNotEmpty()) { Icon(Icons.Outlined.Done, null) }
                    },
                )
            }
            val efforts = current?.efforts?.takeIf { it.isNotEmpty() } ?: o.efforts
            if (efforts.isNotEmpty()) {
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.session_effort), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(6.dp))
                ChoiceGroup(
                    efforts,
                    efforts.firstOrNull { it.equals(settings.effort, ignoreCase = true) },
                    onSelect = { e -> onCommand(effortCommand(kind, e), null, e) },
                    enabled = enabled,
                ) { Text(effortLabel(it), maxLines = 1) }
                current?.default?.takeIf { it.isNotEmpty() }?.let {
                    Text(stringResource(R.string.effort_default, effortLabel(it)), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Commands each harness takes by typing, with what they do. */
fun builtinCommands(kind: String): List<HerdrHub.Command> = when (kind) {
    "claude" -> listOf(
        "compact" to "Compact the conversation", "clear" to "Start a new conversation", "context" to "Show context usage",
        "cost" to "Show usage and cost", "model" to "Switch model", "effort" to "Set thinking effort",
        "review" to "Review a pull request", "security-review" to "Review pending changes for security issues",
        "pr-comments" to "Fetch pull request comments", "init" to "Write CLAUDE.md for this project",
    )
    "codex" -> listOf(
        "compact" to "Summarize the conversation to free context", "recap" to "Summarize the conversation now",
        "review" to "Review current changes and find issues", "new" to "Start a new chat", "init" to "Create an AGENTS.md file",
        "diff" to "Show git diff, including untracked files", "status" to "Show session configuration and token usage",
        "model" to "Choose model and reasoning effort", "plan" to "Switch to Plan mode", "goal" to "Set or view the goal of a long task",
        "fork" to "Fork the current chat", "side" to "Start a side conversation", "rename" to "Rename the thread",
        "export" to "Export the conversation as Markdown", "ps" to "List background terminals", "stop" to "Stop all background terminals",
        "usage" to "View account usage", "mcp" to "List MCP tools",
    )
    "pi", "omp" -> listOf(
        "compact" to "Compact the session context", "new" to "Start a new session", "model" to "Switch model (provider/model)",
        "thinking" to "Set thinking level", "name" to "Set session display name", "session" to "Show session info and stats",
        "export" to "Export the session", "clone" to "Duplicate the session at this point", "reload" to "Reload extensions, skills and prompts",
    )
    else -> emptyList()
}.map { HerdrHub.Command(it.first, it.second, "builtin") }

/** Every command the agent knows: its own, then the project's and the user's commands and skills. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandSheet(commands: List<HerdrHub.Command>, loading: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            TextField(
                q,
                { q = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.commands_search)) },
                singleLine = true,
                leadingIcon = { Text("/", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            val shown = commands.filter { q.isBlank() || it.name.contains(q.trim().removePrefix("/"), ignoreCase = true) || it.description.contains(q.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(top = 8.dp)) {
                listOf(
                    "builtin" to R.string.commands_builtin,
                    "command" to R.string.commands_custom,
                    "prompt" to R.string.commands_custom,
                    "skill" to R.string.commands_skills,
                ).groupBy({ it.second }, { it.first }).forEach { (title, sources) ->
                    val group = shown.filter { it.source in sources }
                    if (group.isNotEmpty()) {
                        item(key = "h$title") {
                            Text(stringResource(title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        items(group, key = { "${it.source}:${it.name}" }) { c ->
                            Column(Modifier.fillMaxWidth().clickable { onPick("/${c.name} ") }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text("/" + c.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                if (c.description.isNotEmpty()) {
                                    Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(36.dp)) } }
                else if (shown.isEmpty()) item { Text(stringResource(R.string.commands_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---- Claude's own queue, command suggestions, the next-prompt suggestion ----

@Composable
fun AgentQueueStack(queued: List<AgentQueued>) {
    if (queued.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(pluralStringResource(R.plurals.agent_queued_count, queued.size, queued.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        queued.forEach {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text(it.text, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun CommandSuggestions(draft: String, commands: List<HerdrHub.Command>, onPick: (String) -> Unit) {
    if (!draft.startsWith("/") || draft.contains(' ') || draft.contains('\n')) return
    val q = draft.drop(1).lowercase()
    val matches = commands.filter { it.name.lowercase().contains(q) }.sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }.take(8)
    if (matches.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            matches.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { onPick("/${c.name} ") }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("/" + c.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(c.description, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.source == "skill") Text(stringResource(R.string.command_skill), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SuggestionChip(text: String, onSend: () -> Unit, onEdit: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(50)).combinedClickable(onClick = onSend, onLongClick = onEdit),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- attachments ----

data class Attachment(val id: Long, val name: String, val thumbnail: Bitmap?, val path: String? = null, val error: String? = null)

@Composable
fun AttachmentStrip(items: List<Attachment>, onRemove: (Attachment) -> Unit) {
    if (items.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { a ->
            Box {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (a.thumbnail != null) Image(a.thumbnail.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(a.name, Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        when {
                            a.error != null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                                Text("!", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            a.path == null -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                                LoadingIndicator(Modifier.size(22.dp))
                            }
                        }
                    }
                }
                Surface(
                    onClick = { onRemove(a) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp),
                ) { Icon(Icons.Outlined.Close, stringResource(R.string.remove), Modifier.padding(3.dp), tint = MaterialTheme.colorScheme.inverseOnSurface) }
            }
        }
    }
}

// ---- restarting the agent ----

sealed interface RestartPhase {
    data object Running : RestartPhase
    data class Failed(val message: String) : RestartPhase
}

@Composable
fun RestartBar(label: String, phase: RestartPhase, since: Long, onDismiss: () -> Unit, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (phase) {
                RestartPhase.Running -> {
                    LoadingIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.agent_restarting, label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    liveElapsed(since)?.let { Text(stopwatch(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                is RestartPhase.Failed -> {
                    Icon(Icons.Outlined.Update, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text(phase.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = onRetry) { Icon(Icons.Outlined.Refresh, stringResource(R.string.retry)) }
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                }
            }
        }
    }
}
