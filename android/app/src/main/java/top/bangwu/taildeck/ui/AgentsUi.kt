package top.bangwu.taildeck.ui

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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import top.bangwu.taildeck.AgentStatus
import top.bangwu.taildeck.ChatMessage
import top.bangwu.taildeck.HerdrAgent
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
        Surface(
            onClick = onOpen,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SmartToy, null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.agents_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            stringResource(R.string.agents_count, agents.size),
                            if (working > 0) stringResource(R.string.agents_working_count, working) else null,
                            if (blocked > 0) stringResource(R.string.agents_blocked_count, blocked) else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (blocked > 0) AgentDot(AgentStatus.BLOCKED)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(node: String, onBack: () -> Unit, onAgent: (String) -> Unit, onTerminal: () -> Unit) {
    val hub = TaildeckApp.instance.herdr
    val states by hub.states.collectAsStateWithLifecycle()
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
    Scaffold(
        floatingActionButton = {
            AnimatedVisibility(state is HerdrState.Ready, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(stringResource(R.string.agent_new)) },
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.agents_title))
                        Text("herdr · $node", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton(onClick = onTerminal) { Icon(Icons.Outlined.Terminal, stringResource(R.string.ssh)) } },
            )
        },
    ) { padding ->
        PullToRefreshBox(refreshing, onRefresh = { refreshing = true }, modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(state?.javaClass, label = "agents") { _ ->
                when (state) {
                    is HerdrState.Ready -> if (state.agents.isEmpty()) {
                        Centered(stringResource(R.string.agents_none))
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                            state.agents.groupBy { it.workspace }.forEach { (ws, list) ->
                                item(key = "ws:$ws") {
                                    Text(
                                        ws,
                                        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                items(list, key = { it.paneId }) { AgentRow(it, Modifier.animateItem()) { onAgent(it.paneId) } }
                            }
                        }
                    }
                    HerdrState.NotInstalled -> Centered(stringResource(R.string.herdr_not_installed))
                    HerdrState.NotRunning -> Centered(stringResource(R.string.herdr_not_running))
                    is HerdrState.Failed -> Centered(state.message)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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

@Composable
private fun AgentRow(agent: HerdrAgent, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        leadingContent = { AgentDot(agent.status) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                StatusChip(agent.status)
            }
        },
        supportingContent = {
            Column {
                agent.title?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    listOfNotNull(agent.kind.takeIf { it != agent.label }, agent.cwd?.substringAfterLast('/')?.ifEmpty { "/" }, agent.paneId).joinToString(" · "),
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
    val listState = rememberLazyListState()
    val live = status == AgentStatus.WORKING || status == AgentStatus.BLOCKED
    val toolCtx = ToolContext(agent?.cwd, live) { openTool = it.id }

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

    // While the agent works, sending queues on the phone; long-press sends now.
    fun send(now: Boolean = false) {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        fullscreen = false
        if (status == AgentStatus.WORKING && !now) {
            hub.enqueue(node, pane, text)
            return
        }
        prompt(text)
    }

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
                    Column {
                        Text(agent?.label ?: pane, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AgentDot(status, Modifier.size(7.dp))
                            Spacer(Modifier.width(6.dp))
                            AnimatedContent(statusLabel(status) + (agent?.title?.let { " · $it" } ?: ""), label = "status") {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = onTerminal) { Icon(Icons.Outlined.Terminal, stringResource(R.string.ssh)) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.agent_rename)) }, onClick = { menu = false; renaming = true })
                        HorizontalDivider()
                        listOf(
                            ChatDisplay.FULL to R.string.chat_display_full,
                            ChatDisplay.TOOLS to R.string.chat_display_tools,
                            ChatDisplay.TURNS to R.string.chat_display_turns,
                        ).forEach { (d, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                leadingIcon = { if (d == display) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp)) },
                                onClick = { menu = false; app.store.setChatDisplay(d.key) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val t = transcript
                when {
                    t == null && error != null -> Centered(error.orEmpty())
                    t == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
                            if (working) {
                                val since = t.messages.lastOrNull { it.role == "user" }?.ts ?: 0L
                                item(key = "working") { WorkingRow(agent?.label.orEmpty(), since, Modifier.animateItem()) }
                            }
                            items(rows, key = { it.key }) { row ->
                                when (row) {
                                    is MessageRow -> MessageView(row.m, toolCtx, Modifier.animateItem())
                                    is ToolGroupRow -> ToolGroup(row, toolCtx, Modifier.animateItem()) { MessageView(it, toolCtx, Modifier) }
                                    is TurnFooterRow -> TurnFooter(row, Modifier.animateItem())
                                }
                            }
                            if (t.matchedByDirectory || t.truncated) {
                                item(key = "note") {
                                    Text(
                                        stringResource(if (t.matchedByDirectory) R.string.agent_matched_by_dir else R.string.agent_truncated),
                                        Modifier.fillMaxWidth().padding(8.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
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
                    ApprovalPanel(screen, pendingTool, toolCtx, picking, onKeys = { keys(*it.toTypedArray()) }, onTerminal = onTerminal)
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding()) {
                        Column(Modifier.animateContentSize(Motion.slow())) {
                            QueueTrack(
                                queued,
                                onEdit = { q ->
                                    hub.dequeue(node, pane, q.id)?.let { draft = if (draft.isBlank()) it.text else draft.trimEnd() + "\n" + it.text }
                                },
                                onSendNow = { q -> hub.dequeue(node, pane, q.id)?.let { prompt(it.text) } },
                            )
                            val tr = transcript
                            if (tr != null && (tr.todos.any { it.status != "completed" } || tr.contextUsed > 0)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                                    TodoTrack(tr.todos, Modifier.weight(1f))
                                    if (tr.todos.none { it.status != "completed" }) Spacer(Modifier.weight(1f))
                                    ContextChip(tr.contextUsed, tr.contextWindow, tr.model)
                                }
                            }
                            Composer(
                                draft = draft,
                                onDraft = { draft = it },
                                sending = sending,
                                working = status == AgentStatus.WORKING,
                                canVoice = canVoice,
                                onSend = { send() },
                                onSendNow = { send(now = true) },
                                onStop = { keys("esc") },
                                onVoice = { runCatching { voice.launch(voiceIntent) } },
                                onFullscreen = { fullscreen = true },
                            )
                        }
                    }
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
                    IconButton(onClick = { onEdit(q) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.agent_queue_edit), Modifier.size(18.dp)) }
                    IconButton(onClick = { onSendNow(q) }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.agent_queue_send_now), Modifier.size(18.dp)) }
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

@Composable
private fun WorkingRow(label: String, since: Long, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "dots")
    val phase = t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "p").value
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(Modifier.padding(end = 4.dp).size(6.dp).alpha(if (phase.toInt() == i) 1f else 0.3f).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        val elapsed = liveElapsed(since)?.let { " · " + durationLabel(it) }.orEmpty()
        Text(stringResource(R.string.agent_working_on, label) + elapsed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageView(m: ChatMessage, toolCtx: ToolContext, modifier: Modifier) {
    val context = LocalContext.current
    when (m.role) {
        "user" -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 320.dp).combinedClickable(onClick = {}, onLongClick = { context.copy(m.text) }),
            ) {
                Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge)
            }
        }
        "assistant" -> Box(modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { context.copy(m.text) })) {
            Markdown(m.text, Modifier.padding(end = 24.dp))
        }
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
    working: Boolean,
    canVoice: Boolean,
    onSend: () -> Unit,
    onSendNow: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            draft,
            onDraft,
            modifier = Modifier.weight(1f).heightIn(max = 180.dp),
            placeholder = { Text(stringResource(if (working) R.string.agent_queue_hint else R.string.agent_message_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            shape = RoundedCornerShape(22.dp),
            trailingIcon = when {
                draft.isBlank() && canVoice -> { { IconButton(onClick = onVoice) { Icon(Icons.Outlined.Mic, stringResource(R.string.agent_voice)) } } }
                draft.lines().size >= 3 || draft.length > 120 -> { { IconButton(onClick = onFullscreen) { Icon(Icons.Outlined.OpenInFull, stringResource(R.string.agent_fullscreen)) } } }
                else -> null
            },
        )
        Spacer(Modifier.width(8.dp))
        AnimatedVisibility(working && draft.isBlank(), enter = fadeIn(), exit = fadeOut()) {
            FilledIconButton(onClick = onStop, modifier = Modifier.padding(bottom = 4.dp)) { Icon(Icons.Outlined.Stop, stringResource(R.string.agent_stop)) }
        }
        AnimatedVisibility(!working || draft.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
            val enabled = draft.isNotBlank() && !sending
            Box(
                Modifier.padding(bottom = 4.dp).size(40.dp).clip(CircleShape)
                    .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    .combinedClickable(
                        enabled = enabled,
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = stringResource(if (working) R.string.agent_queue else R.string.agent_send),
                        onLongClickLabel = if (working) stringResource(R.string.agent_send_now) else null,
                        onClick = onSend,
                        onLongClick = if (working) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSendNow()
                            }
                        } else null,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                when {
                    sending -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
                    working -> Icon(Icons.Outlined.Schedule, null, tint = tint)
                    else -> Icon(Icons.AutoMirrored.Filled.Send, null, tint = tint)
                }
            }
        }
    }
}

@Composable
private fun ApprovalPanel(screen: String, tool: ChatMessage?, toolCtx: ToolContext, picking: Boolean, onKeys: (List<String>) -> Unit, onTerminal: () -> Unit) {
    val prompt = remember(screen) { parseScreenPrompt(screen) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(12.dp).animateContentSize(Motion.slow())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentDot(AgentStatus.BLOCKED)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.agent_needs_you), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onTerminal) { Text(stringResource(R.string.ssh)) }
            }
            if (tool != null) {
                Spacer(Modifier.size(8.dp))
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        // The call waits on the user, so no progress spinner here.
                        ToolRow(tool, ToolContext(toolCtx.cwd, live = false, toolCtx.onOpen))
                        when {
                            tool.diff.isNotEmpty() -> DiffView(tool.diff, maxLines = 14)
                            // The bridge appends the remaining arguments as an indented JSON object.
                            tool.kind == "shell" && tool.input.isNotEmpty() -> CodeBlock(tool.input.substringBeforeLast("\n{\n"), maxLines = 8)
                        }
                        if (tool.diff.isNotEmpty() || tool.kind == "shell") Spacer(Modifier.size(6.dp))
                    }
                }
            }
            val terminal = prompt == null || showKeys
            if (prompt != null) {
                Spacer(Modifier.size(10.dp))
                PromptOptions(prompt, enabled = !picking, onPick = onKeys)
                TextButton(onClick = { showKeys = !showKeys }) {
                    Text(stringResource(if (showKeys) R.string.agent_hide_terminal_keys else R.string.agent_terminal_keys))
                }
            }
            if (terminal && screen.isNotBlank()) {
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
            if (terminal) {
                Spacer(Modifier.size(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    APPROVAL_KEYS.forEach { (label, key) ->
                        FilledTonalButton(onClick = { onKeys(listOf(key)) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
                            Text(label, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
