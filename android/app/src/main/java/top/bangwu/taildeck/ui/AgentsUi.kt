package top.bangwu.taildeck.ui

import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.TopAppBarDefaults
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.bangwu.taildeck.AgentStatus
import top.bangwu.taildeck.ChatMessage
import top.bangwu.taildeck.HerdrAgent
import top.bangwu.taildeck.AgentSettings
import top.bangwu.taildeck.HerdrHub
import top.bangwu.taildeck.HerdrState
import top.bangwu.taildeck.QueuedPrompt
import top.bangwu.taildeck.R
import top.bangwu.taildeck.TaildeckApp
import top.bangwu.taildeck.Transcript

@Composable
fun statusColor(s: AgentStatus): Color = when (s) {
    AgentStatus.WORKING -> MaterialTheme.colorScheme.primary
    AgentStatus.BLOCKED -> Color(0xFFE5A23B)
    AgentStatus.DONE -> OnlineGreen
    else -> MaterialTheme.colorScheme.outline
}

@Composable
fun statusLabel(s: AgentStatus): String = stringResource(
    when (s) {
        AgentStatus.WORKING -> R.string.agent_working
        AgentStatus.BLOCKED -> R.string.agent_blocked
        AgentStatus.DONE -> R.string.agent_done
        AgentStatus.IDLE -> R.string.agent_idle
        AgentStatus.UNKNOWN -> R.string.agent_unknown
    },
)

/** Status dot that pulses while the agent works. */
@Composable
fun AgentDot(status: AgentStatus, modifier: Modifier = Modifier) {
    val pulse = if (status == AgentStatus.WORKING || status == AgentStatus.BLOCKED) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value
    } else {
        1f
    }
    Box(modifier.size(10.dp).alpha(pulse).background(statusColor(status), CircleShape))
}

@Composable
fun StatusChip(status: AgentStatus) {
    val c = statusColor(status)
    Surface(color = c.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentDot(status, Modifier.size(7.dp))
            Spacer(Modifier.width(5.dp))
            Text(statusLabel(status), style = MaterialTheme.typography.labelSmall, color = c)
        }
    }
}

/** Compact summary used on the node page and in the terminal bar. */
@Composable
fun HerdrSummary(node: String, onOpen: () -> Unit) {
    val states by TaildeckApp.instance.herdr.states.collectAsStateWithLifecycle()
    val ready = states[node] as? HerdrState.Ready
    AnimatedVisibility(ready != null && ready.agents.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val agents = ready?.agents.orEmpty()
        val blocked = agents.count { it.status == AgentStatus.BLOCKED }
        val working = agents.count { it.status == AgentStatus.WORKING }
        val c = MaterialTheme.colorScheme
        Surface(
            onClick = onOpen,
            color = if (blocked > 0) c.tertiaryContainer else c.secondaryContainer,
            shape = MaterialTheme.shapes.extraLargeIncreased,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(
                    if (blocked > 0) MaterialShapes.SoftBurst else MaterialShapes.Cookie9Sided,
                    if (blocked > 0) c.tertiary else c.secondary,
                    size = 48.dp,
                    spin = working > 0,
                ) { Icon(Icons.Outlined.SmartToy, null, tint = if (blocked > 0) c.onTertiary else c.onSecondary) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.agents_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        listOfNotNull(
                            stringResource(R.string.agents_count, agents.size),
                            if (working > 0) stringResource(R.string.agents_working_count, working) else null,
                            if (blocked > 0) stringResource(R.string.agents_blocked_count, blocked) else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(node: String, onBack: () -> Unit, onAgent: (String) -> Unit, onTerminal: () -> Unit) {
    val hub = TaildeckApp.instance.herdr
    val states by hub.states.collectAsStateWithLifecycle()
    val unread by hub.unread.collectAsStateWithLifecycle()
    val muted by hub.muted.collectAsStateWithLifecycle()
    val state = states[node]
    var refreshing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            hub.refreshNow(node)
            delay(500)
            refreshing = false
        }
    }
    if (creating && state is HerdrState.Ready) {
        NewAgentDialog(node, state.agents, onDismiss = { creating = false }, onStarted = { creating = false; onAgent(it) })
    }
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        containerColor = Surfaces.page,
        floatingActionButton = {
            AnimatedVisibility(state is HerdrState.Ready, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                SmallExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(stringResource(R.string.agent_new)) },
                )
            }
        },
        topBar = {
            LargeFlexibleTopAppBar(
                scrollBehavior = bar,
                colors = Surfaces.bar(),
                title = { Text(stringResource(R.string.agents_title)) },
                subtitle = { Text("herdr · $node") },
                navigationIcon = { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onTerminal) { Icon(Icons.Outlined.Terminal, stringResource(R.string.ssh)) } },
            )
        },
    ) { padding ->
        RefreshBox(refreshing, onRefresh = { refreshing = true }, modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(state?.javaClass, label = "agents") { _ ->
                when (state) {
                    is HerdrState.Ready -> if (state.agents.isEmpty()) {
                        Centered(stringResource(R.string.agents_none))
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
                            // Agents waiting on you, and finished ones you haven't opened, come first.
                            val needsYou = state.agents.filter { it.status == AgentStatus.BLOCKED || it.status == AgentStatus.DONE && (node to it.paneId) in unread }
                                .sortedBy { if (it.status == AgentStatus.BLOCKED) 0 else 1 }
                            if (needsYou.isNotEmpty()) {
                                item(key = "needs") { GroupHeader(stringResource(R.string.agents_needs_you), Modifier.animateItem()) }
                                segmentedItems(needsYou, key = { "n:" + it.paneId }) { a, shapes ->
                                    AgentRow(a, (node to a.paneId) in unread, shapes, attention = true) { onAgent(a.paneId) }
                                }
                            }
                            state.agents.filterNot { it in needsYou }.groupBy { it.workspace }.forEach { (ws, list) ->
                                item(key = "ws:$ws") {
                                    val isMuted = top.bangwu.taildeck.Store.muteKey(node, ws) in muted
                                    WorkspaceHeader(ws, isMuted) { hub.setMuted(node, ws, !isMuted) }
                                }
                                segmentedItems(list, key = { it.paneId }) { a, shapes -> AgentRow(a, (node to a.paneId) in unread, shapes) { onAgent(a.paneId) } }
                            }
                        }
                    }
                    HerdrState.NotInstalled -> Centered(stringResource(R.string.herdr_not_installed))
                    HerdrState.NotRunning -> Centered(stringResource(R.string.herdr_not_running))
                    is HerdrState.Failed -> Centered(state.message)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceHeader(name: String, muted: Boolean, onToggleMute: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        GroupHeader(name, Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })) {
            if (muted) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.NotificationsOff, stringResource(R.string.agents_unmute), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        GroupMenu(menu, onDismissRequest = { menu = false }) {
            MenuItem(text = { Text(stringResource(if (muted) R.string.agents_unmute else R.string.agents_mute)) }, onClick = { menu = false; onToggleMute() })
        }
    }
}

@Composable
private fun AgentRow(agent: HerdrAgent, unread: Boolean, shapes: ListItemShapes, attention: Boolean = false, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = Surfaces.itemColors(if (attention && agent.status == AgentStatus.BLOCKED) MaterialTheme.colorScheme.tertiaryContainer else Surfaces.item),
        leadingContent = { AgentAvatar(agent.kind, agent.status) },
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agent.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (unread) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
                Spacer(Modifier.width(8.dp))
                StatusChip(agent.status)
            }
        },
        supportingContent = {
            val dir = agent.cwd?.substringAfterLast('/')?.ifEmpty { "/" }
            // Titles like pi's "π - documentation" only repeat the directory shown below.
            val title = agent.title?.takeUnless { dir != null && it.contains(dir) && it.replace(dir, "").count(Char::isLetterOrDigit) <= 1 }
            Column {
                title?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    listOfNotNull(agent.kind.takeIf { it != agent.label }, dir, agent.paneId).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
    )
}

private val APPROVAL_KEYS = listOf(
    "Enter" to "enter", "Esc" to "esc", "↑" to "up", "↓" to "down", "Tab" to "tab",
    "1" to "1", "2" to "2", "3" to "3", "y" to "y", "n" to "n",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(node: String, pane: String, onBack: () -> Unit, onTerminal: () -> Unit) {
    val app = TaildeckApp.instance
    val hub = app.herdr
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val states by hub.states.collectAsStateWithLifecycle()
    val queues by hub.queues.collectAsStateWithLifecycle()
    val displayKey by app.store.chatDisplay.collectAsStateWithLifecycle()
    val display = ChatDisplay.of(displayKey)
    val agent = (states[node] as? HerdrState.Ready)?.agents?.firstOrNull { it.paneId == pane }
    val status = agent?.status ?: AgentStatus.UNKNOWN
    val currentStatus by rememberUpdatedState(status)
    val queued = queues[node to pane].orEmpty()
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf("") }
    var draft by remember(node, pane) { mutableStateOf(app.store.draft(node, pane)) }
    var sending by remember { mutableStateOf(false) }
    var openTool by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var openSubagent by remember { mutableStateOf<String?>(null) }
    var subagentList by remember { mutableStateOf(false) }
    var restart by remember { mutableStateOf<Pair<RestartPhase, Long>?>(null) }
    var statusLine by remember { mutableStateOf<StatusLine?>(null) }
    var suggestion by remember { mutableStateOf<String?>(null) }
    var commands by remember { mutableStateOf<List<HerdrHub.Command>>(emptyList()) }
    var commandsLoading by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var commandSheet by remember { mutableStateOf(false) }
    // A model or level just picked shows until the transcript records a change.
    var picked by remember { mutableStateOf<Pair<AgentSettings, AgentSettings>?>(null) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    val listState = rememberLazyListState()
    val kind = agent?.kind.orEmpty()
    val live = status == AgentStatus.WORKING || status == AgentStatus.BLOCKED
    val toolCtx = ToolContext(agent?.cwd, live) {
        if (it.kind == "task" && it.call.isNotEmpty() && kind == "claude") openSubagent = it.id else openTool = it.id
    }

    DisposableEffect(node, pane) {
        hub.viewing = node to pane
        onDispose { if (hub.viewing == node to pane) hub.viewing = null }
    }
    val latestDraft by rememberUpdatedState(draft)
    LaunchedEffect(draft) {
        delay(400)
        app.store.saveDraft(node, pane, draft)
    }
    DisposableEffect(node, pane) {
        onDispose { app.store.saveDraft(node, pane, latestDraft) }
    }

    suspend fun reload() {
        runCatching { hub.transcript(node, pane) }
            .onSuccess { transcript = it; error = null }
            .onFailure { error = it.message }
    }

    // Herdr events trigger immediate reloads; polling covers file writes that
    // don't change agent state (streaming replies, tool output).
    LaunchedEffect(node, pane) {
        launch { hub.changes.filter { it == node }.collect { reload() } }
        while (true) {
            reload()
            delay(if (currentStatus == AgentStatus.WORKING) 1200 else 4000)
        }
    }
    var picking by remember { mutableStateOf(false) }
    suspend fun readScreen() {
        screen = runCatching {
            hub.call(node, "agent.read", JSONObject().put("target", pane).put("source", "visible").put("lines", 60))
                .optJSONObject("read")?.optString("text").orEmpty()
        }.getOrDefault(screen).lines().dropLastWhile { it.isBlank() }.joinToString("\n")
    }

    // The TUI may finish drawing its prompt after the status flips, so keep reading.
    LaunchedEffect(status) {
        while (status == AgentStatus.BLOCKED) {
            readScreen()
            delay(1500)
        }
    }

    fun keys(vararg k: String) = scope.launch {
        picking = true
        runCatching { hub.call(node, "agent.send_keys", JSONObject().put("target", pane).put("keys", JSONArray(k.toList()))) }
            .onFailure { snackbar.showSnackbar(it.message.orEmpty()) }
        delay(400)
        hub.refreshNow(node)
        readScreen()
        picking = false
    }

    // Follow new output only while the reader sits at the bottom; a user scroll decides.
    var follow by remember { mutableStateOf(true) }
    val nearBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 120 } }
    LaunchedEffect(listState) {
        snapshotFlow { nearBottom }.collect { if (listState.isScrollInProgress) follow = it }
    }
    fun toBottom() = scope.launch {
        follow = true
        listState.animateScrollToItem(0)
    }

    // A failed prompt goes back into the draft so nothing typed is lost.
    fun prompt(text: String) {
        sending = true
        scope.launch {
            runCatching { hub.prompt(node, pane, text) }
                .onSuccess { toBottom() }
                .onFailure {
                    draft = if (draft.isBlank()) text else draft.trimEnd() + "\n" + text
                    val msg = it.message.orEmpty()
                    snackbar.showSnackbar(if (msg.startsWith("agent_blocked")) context.getString(R.string.agent_blocked_send) else msg)
                }
            sending = false
            reload()
        }
    }

    // Claude, Codex and pi hold what's typed while they work, so it goes
    // straight to them; other agents get the phone's queue (long-press sends now).
    val localQueue = status == AgentStatus.WORKING && !hub.queuesNatively(kind)
    fun send(now: Boolean = false) {
        val paths = attachments.mapNotNull { it.path }
        if (attachments.any { it.path == null && it.error == null }) return
        val text = (listOf(draft.trim()) + paths).filter { it.isNotEmpty() }.joinToString("\n")
        if (text.isEmpty()) return
        draft = ""
        attachments = emptyList()
        fullscreen = false
        suggestion = null
        if (localQueue && !now) {
            hub.enqueue(node, pane, text)
            return
        }
        prompt(text)
    }

    fun restartAgent(then: String?) {
        restart = RestartPhase.Running to System.currentTimeMillis()
        scope.launch {
            runCatching { hub.restart(node, pane, then) }
                .onSuccess { restart = null; reload(); snackbar.showSnackbar(context.getString(R.string.agent_restarted)) }
                .onFailure { e -> restart = RestartPhase.Failed(e.message.orEmpty().substringAfter(": ")) to System.currentTimeMillis() }
        }
    }

    // Claude's status line says what it's doing and for how long.
    LaunchedEffect(status, kind) {
        statusLine = null
        while (status == AgentStatus.WORKING) {
            runCatching { parseStatusLine(hub.screen(node, pane)) }.onSuccess { statusLine = it }
            delay(2000)
        }
    }
    // Claude shows a suggested next prompt, dimmed, in its empty input.
    LaunchedEffect(status, kind) {
        suggestion = null
        if (kind != "claude" || !status.settled()) return@LaunchedEffect
        for (wait in listOf(300L, 1200L, 2000L, 4500L)) {
            delay(wait)
            val next = runCatching { parseClaudeSuggestion(hub.screen(node, pane, ansi = true)) }.getOrNull()
            suggestion = next
            if (next != null) break
        }
    }
    val wantsCommands = draft.startsWith("/") || commandSheet
    LaunchedEffect(wantsCommands, kind) {
        if (!wantsCommands) return@LaunchedEffect
        commands = builtinCommands(kind)
        commandsLoading = true
        val own = runCatching { hub.commands(node, kind, agent?.cwd.orEmpty()) }.getOrDefault(emptyList())
        commands = commands + own.filter { o -> commands.none { it.name == o.name } }
        commandsLoading = false
    }
    val recorded = transcript?.settings ?: AgentSettings()
    val shownSettings = picked?.takeIf { it.first == recorded }?.second ?: recorded
    LaunchedEffect(recorded) { if (picked?.first != recorded) picked = null }

    fun attach(uris: List<android.net.Uri>) {
        uris.take(6).forEach { uri ->
            val id = System.nanoTime()
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "file"
            attachments = attachments + Attachment(id, name, null)
            scope.launch {
                val result = runCatching {
                    val data = withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } } ?: error(context.getString(R.string.attach_unreadable))
                    if (data.size > 30 shl 20) error(context.getString(R.string.attach_too_big, name))
                    val thumb = withContext(Dispatchers.Default) {
                        runCatching { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull()
                    }
                    attachments = attachments.map { if (it.id == id) it.copy(thumbnail = thumb) else it }
                    hub.upload(node, name, data)
                }
                attachments = attachments.map {
                    if (it.id != id) it else result.fold({ p -> it.copy(path = p) }, { e -> it.copy(error = e.message.orEmpty()) })
                }
                result.exceptionOrNull()?.let { snackbar.showSnackbar(it.message.orEmpty()) }
            }
        }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { attach(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attach(it) }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (heard.isNotEmpty()) {
            val sep = if (draft.isEmpty() || draft.last().isWhitespace() || draft.last().code >= 0x2E80 || heard.first().code >= 0x2E80) "" else " "
            draft += sep + heard
        }
    }
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }
    val canVoice = remember { voiceIntent.resolveActivity(context.packageManager) != null }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val dismissOnFlick = remember(density) {
        val threshold = with(density) { 1500.dp.toPx() }
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (available.y > threshold) {
                    keyboard?.hide()
                    focus.clearFocus()
                }
                return Velocity.Zero
            }
        }
    }

    if (fullscreen) {
        FullscreenComposer(agent?.label ?: pane, draft, { draft = it }, canSend = !sending, onSend = { send() }, onClose = { fullscreen = false })
    }
    if (renaming) RenameAgentDialog(node, pane, agent?.name) { renaming = false }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentAvatar(kind, status, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(agent?.label ?: pane, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AnimatedContent(statusLabel(status) + (agent?.title?.let { " · $it" } ?: ""), label = "status") {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = onTerminal) { Icon(Icons.Outlined.Terminal, stringResource(R.string.ssh)) }
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                    GroupMenu(
                        menu,
                        { menu = false },
                        {
                            MenuItem(text = { Text(stringResource(R.string.agent_rename)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; renaming = true })
                            if (kind in setOf("claude", "codex", "pi", "omp")) {
                                MenuItem(
                                    text = { Text(stringResource(R.string.agent_restart)) },
                                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                                    onClick = { menu = false; restartAgent(null) },
                                )
                                MenuItem(
                                    text = { Text(stringResource(R.string.agent_restart_continue)) },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                                    onClick = { menu = false; restartAgent(context.getString(R.string.agent_continue_prompt)) },
                                )
                            }
                        },
                        {
                            val options = listOf(
                                ChatDisplay.FULL to R.string.chat_display_full,
                                ChatDisplay.TOOLS to R.string.chat_display_tools,
                                ChatDisplay.TURNS to R.string.chat_display_turns,
                            )
                            options.forEachIndexed { i, (d, label) ->
                                DropdownMenuItem(
                                    checked = d == display,
                                    onCheckedChange = { menu = false; app.store.setChatDisplay(d.key) },
                                    text = { Text(stringResource(label)) },
                                    shapes = MenuDefaults.itemShape(i, options.size),
                                    checkedLeadingIcon = { Icon(Icons.Outlined.Check, null) },
                                )
                            }
                        },
                    )
                },
            )
        },
    ) { padding ->
        androidx.compose.runtime.CompositionLocalProvider(LocalAgentNode provides node) {
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val t = transcript
                when {
                    t == null && error != null -> Centered(error.orEmpty())
                    t == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    t.screenMode -> ScreenView(t.screen)
                    else -> {
                        val working = status == AgentStatus.WORKING
                        val rows = remember(t.messages, working, display) { buildRows(t.messages, working, display).asReversed() }
                        LaunchedEffect(rows.firstOrNull()?.key, rows.size, working) {
                            if (follow) listState.scrollToItem(0)
                        }
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize().nestedScroll(dismissOnFlick),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(rows, key = { it.key }) { row ->
                                when (row) {
                                    is MessageRow -> MessageView(row.m, toolCtx, Modifier.animateItem())
                                    is ToolGroupRow -> ToolGroup(row, toolCtx, Modifier.animateItem()) { MessageView(it, toolCtx, Modifier) }
                                    is TurnFooterRow -> TurnFooter(row, Modifier.animateItem())
                                }
                            }
                            if (t.matchedByDirectory || t.truncated) {
                                item(key = "note") {
                                    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (t.truncated) {
                                            var loading by remember { mutableStateOf(false) }
                                            TextButton(
                                                shapes = ButtonDefaults.shapes(),
                                                enabled = !loading,
                                                onClick = {
                                                    loading = true
                                                    scope.launch {
                                                        if (runCatching { hub.transcriptMore(node, pane) }.getOrDefault(false)) reload()
                                                        loading = false
                                                    }
                                                },
                                            ) { Text(stringResource(R.string.agent_load_earlier)) }
                                        }
                                        if (t.matchedByDirectory) {
                                            Text(
                                                stringResource(R.string.agent_matched_by_dir),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        ScrollToBottomButton(!nearBottom, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { toBottom() }
                    }
                }
            }
            AnimatedContent(
                targetState = status == AgentStatus.BLOCKED,
                transitionSpec = { (slideInVertically { it / 3 } + fadeIn()).togetherWith(slideOutVertically { it / 3 } + fadeOut()) },
                label = "composer",
            ) { blocked ->
                if (blocked) {
                    val msgs = transcript?.messages.orEmpty()
                    val pendingTool = msgs.drop(msgs.indexOfLast { it.role == "user" } + 1).lastOrNull { it.role == "tool" && it.pending }
                    PromptPanel(
                        node, pane, screen, pendingTool, toolCtx,
                        onScreen = { scope.launch { readScreen() } },
                        onTerminal = onTerminal,
                        onChanged = { scope.launch { snackbar.showSnackbar(context.getString(R.string.prompt_changed)) } },
                    ) { TerminalKeys(screen, picking) { keys(*it.toTypedArray()) } }
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding()) {
                        Column(Modifier.animateContentSize(Motion.slow())) {
                            val tr = transcript
                            restart?.let { (phase, since) ->
                                RestartBar(agent?.label ?: pane, phase, since, onDismiss = { restart = null }, onRetry = { restartAgent(null) })
                            }
                            if (status == AgentStatus.WORKING) {
                                val running = tr?.messages?.lastOrNull { it.role == "tool" && it.pending }
                                val activity = statusLine?.text ?: running?.let { "${toolLabel(it)} ${it.text.ifEmpty { it.relativeSummary(agent?.cwd) }}" }
                                val since = statusLine?.elapsedMs?.let { System.currentTimeMillis() - it }
                                    ?: tr?.messages?.lastOrNull { it.role == "user" && !it.queued }?.ts ?: 0L
                                val subs = runningSubagents(tr)
                                WorkingBar(activity, since, subs.size, onSubagents = { subagentList = true }, onStop = { keys("esc") })
                            }
                            AgentQueueStack(tr?.agentQueue.orEmpty())
                            QueueTrack(
                                queued,
                                onEdit = { q ->
                                    hub.dequeue(node, pane, q.id)?.let { draft = if (draft.isBlank()) it.text else draft.trimEnd() + "\n" + it.text }
                                },
                                onSendNow = { q -> hub.dequeue(node, pane, q.id)?.let { prompt(it.text) } },
                            )
                            if (tr != null && tr.todos.any { it.status != "completed" }) {
                                TodoTrack(tr.todos, Modifier.padding(horizontal = 10.dp).padding(top = 4.dp))
                            }
                            AgentControlsBar(
                                kind, shownSettings, tr?.contextUsed ?: 0, tr?.contextWindow ?: 0,
                                onModel = { modelSheet = true },
                                onCommands = { commandSheet = true },
                            )
                            val s = suggestion
                            if (s != null && draft.isBlank() && attachments.isEmpty()) {
                                SuggestionChip(s, onSend = { suggestion = null; prompt(s) }, onEdit = { draft = s; suggestion = null })
                            }
                            CommandSuggestions(draft, commands) { draft = it }
                            AttachmentStrip(attachments) { a -> attachments = attachments - a }
                            Composer(
                                draft = draft,
                                onDraft = { draft = it },
                                sending = sending || attachments.any { it.path == null && it.error == null },
                                queues = localQueue,
                                hasAttachments = attachments.any { it.path != null },
                                canVoice = canVoice,
                                onSend = { send() },
                                onSendNow = { send(now = true) },
                                onVoice = { runCatching { voice.launch(voiceIntent) } },
                                onFullscreen = { fullscreen = true },
                                onPickImages = { pickImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                }
            }
        }
        if (modelSheet) {
            // pi would take a command typed mid-turn as a steering message, so it waits.
            val busy = status == AgentStatus.BLOCKED || status == AgentStatus.WORKING && kind in setOf("pi", "omp")
            ModelSheet(node, pane, kind, shownSettings, enabled = !busy, onDismiss = { modelSheet = false }) { cmd, model, effort ->
                picked = recorded to shownSettings.copy(model = model ?: shownSettings.model, effort = effort ?: shownSettings.effort)
                prompt(cmd)
            }
        }
        if (commandSheet) {
            CommandSheet(commands, commandsLoading, onPick = { draft = it; commandSheet = false }) { commandSheet = false }
        }
        if (subagentList) {
            SubagentListSheet(runningSubagents(transcript), onOpen = { subagentList = false; openSubagent = it.id }) { subagentList = false }
        }
        openSubagent?.let { id ->
            transcript?.messages?.firstOrNull { it.id == id }?.let { task ->
                SubagentSheet(node, pane, task, onDismiss = { openSubagent = null }) { m, ctx -> MessageView(m, ctx, Modifier) }
            }
        }
        }
        ToolSheetHost(transcript, openTool, agent?.cwd) { openTool = null }
    }
}

@Composable
private fun ScrollToBottomButton(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        SmallFloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.agent_scroll_bottom))
        }
    }
}

@Composable
private fun QueueTrack(queued: List<QueuedPrompt>, onEdit: (QueuedPrompt) -> Unit, onSendNow: (QueuedPrompt) -> Unit) {
    if (queued.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        queued.forEach { q ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(q.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = { onEdit(q) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.agent_queue_edit), Modifier.size(18.dp)) }
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = { onSendNow(q) }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.agent_queue_send_now), Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ToolSheetHost(transcript: Transcript?, id: String?, cwd: String?, onDismiss: () -> Unit) {
    val m = id?.let { i -> transcript?.messages?.firstOrNull { it.id == i } } ?: return
    ToolSheet(m, cwd, onDismiss)
}

@Composable
private fun ScreenView(text: String) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(stringResource(R.string.agent_screen_mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        val scroll = rememberScrollState()
        LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxSize()) {
            SelectionContainer {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp).verticalScroll(scroll).horizontalScroll(rememberScrollState()),
                    softWrap = false,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageView(m: ChatMessage, toolCtx: ToolContext, modifier: Modifier) {
    val context = LocalContext.current
    when (m.role) {
        "user" -> Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val images = m.images + imagePathsIn(m.text)
            if (images.isNotEmpty()) ImageStrip(images, maxHeight = 200.dp)
            if (m.text.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    modifier = Modifier.widthIn(max = 320.dp).combinedClickable(onClick = {}, onLongClick = { context.copy(m.text) }),
                ) {
                    Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (m.queued) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.agent_sent_while_working), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "assistant" -> Column(modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { context.copy(m.text) }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Markdown(m.text, Modifier.padding(end = 24.dp))
            imagePathsIn(m.text).takeIf { it.isNotEmpty() }?.let { ImageStrip(it) }
        }
        "command" -> CommandChip(m.text, modifier)
        "output" -> CommandOutput(m.text, modifier)
        "recap" -> NoteCard({ Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }, stringResource(R.string.agent_recap), m.text, modifier)
        "teammate" -> NoteCard(
            { Icon(Icons.Outlined.SmartToy, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
            stringResource(R.string.agent_teammate, m.from.ifEmpty { "?" }),
            m.text,
            modifier,
            footer = m.summary,
        )
        "task" -> EventLine(m.text.ifEmpty { stringResource(R.string.agent_background_done) }, modifier, detail = m.output)
        "system" -> EventLine(
            when (m.kind) {
                "interrupted" -> stringResource(R.string.agent_interrupted)
                "compacted" -> stringResource(R.string.agent_compacted)
                else -> m.text
            },
            modifier,
            error = m.kind == "error",
        )
        "thinking" -> Expandable(
            modifier = modifier,
            icon = { Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp)) },
            title = stringResource(R.string.agent_thinking),
            summary = m.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim('*', ' ').orEmpty(),
        ) {
            Text(m.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "tool" -> if (m.kind == "plan" || m.kind == "question") ToolCard(m, toolCtx, modifier) else ToolRow(m, toolCtx, modifier)
        else -> Text(
            m.text,
            modifier.fillMaxWidth().padding(vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Expandable(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    monospaceSummary: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val rotation = androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chevron").value
    Surface(
        onClick = { open = !open },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().animateContentSize(Motion.slow()),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    summary,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (monospaceSummary) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp).rotate(rotation))
            }
            if (open) {
                Spacer(Modifier.size(8.dp))
                content()
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    sending: Boolean,
    queues: Boolean,
    hasAttachments: Boolean,
    canVoice: Boolean,
    onSend: () -> Unit,
    onSendNow: () -> Unit,
    onVoice: () -> Unit,
    onFullscreen: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var attachMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.padding(bottom = 4.dp)) {
            IconButton(shapes = IconButtonDefaults.shapes(), onClick = { attachMenu = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.attach)) }
            GroupMenu(attachMenu, onDismissRequest = { attachMenu = false }) {
                MenuItem(text = { Text(stringResource(R.string.attach_image)) }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { attachMenu = false; onPickImages() })
                MenuItem(text = { Text(stringResource(R.string.attach_file)) }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) }, onClick = { attachMenu = false; onPickFiles() })
            }
        }
        androidx.compose.material3.TextField(
            draft,
            onDraft,
            modifier = Modifier.weight(1f).heightIn(max = 180.dp),
            placeholder = { Text(stringResource(if (queues) R.string.agent_queue_hint else R.string.agent_message_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            shape = RoundedCornerShape(28.dp),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            trailingIcon = when {
                draft.isBlank() && canVoice -> { { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onVoice) { Icon(Icons.Outlined.Mic, stringResource(R.string.agent_voice)) } } }
                draft.lines().size >= 3 || draft.length > 120 -> { { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onFullscreen) { Icon(Icons.Outlined.OpenInFull, stringResource(R.string.agent_fullscreen)) } } }
                else -> null
            },
        )
        Spacer(Modifier.width(8.dp))
        val enabled = (draft.isNotBlank() || hasAttachments) && !sending
        val c = MaterialTheme.colorScheme
        // The send button is the chat's hero: a circle at rest, a cookie when there's
        // something to send, spinning while it goes out.
        ShapeBadge(
            if (enabled || sending) MaterialShapes.Cookie9Sided else MaterialShapes.Circle,
            if (enabled || sending) c.primary else c.surfaceContainerHighest,
            Modifier.padding(bottom = 2.dp).clip(CircleShape)
                .combinedClickable(
                    enabled = enabled,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClickLabel = stringResource(if (queues) R.string.agent_queue else R.string.agent_send),
                    onLongClickLabel = if (queues) stringResource(R.string.agent_send_now) else null,
                    onClick = onSend,
                    onLongClick = if (queues) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSendNow()
                        }
                    } else null,
                ),
            size = 48.dp,
            spin = sending,
        ) {
            val tint = if (enabled || sending) c.onPrimary else c.onSurface.copy(alpha = 0.38f)
            when {
                queues -> Icon(Icons.Outlined.Schedule, null, tint = tint)
                else -> Icon(Icons.AutoMirrored.Filled.Send, null, tint = tint)
            }
        }
    }
}

/** The terminal's last lines and raw keys, for prompts the parser can't read. */
@Composable
private fun TerminalKeys(screen: String, picking: Boolean, onKeys: (List<String>) -> Unit) {
    Column {
        if (screen.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    screen.lines().takeLast(18).joinToString("\n"),
                    Modifier.padding(10.dp).horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    softWrap = false,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            APPROVAL_KEYS.forEach { (label, key) ->
                FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = { onKeys(listOf(key)) }, enabled = !picking, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
                    Text(label, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
