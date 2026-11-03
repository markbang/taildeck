package top.bangwu.taildeck

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.bangwu.taildeck.tsbridge.Herdr
import top.bangwu.taildeck.tsbridge.HerdrListener
import top.bangwu.taildeck.tsbridge.Tsbridge
import top.bangwu.taildeck.ui.ScreenPrompt
import top.bangwu.taildeck.ui.parsePrompt

enum class AgentStatus { WORKING, BLOCKED, DONE, IDLE, UNKNOWN;
    fun settled() = this == DONE || this == IDLE
    companion object {
        fun of(s: String) = when (s) {
            "working" -> WORKING
            "blocked" -> BLOCKED
            "done" -> DONE
            "idle" -> IDLE
            else -> UNKNOWN
        }
    }
}

data class HerdrAgent(
    val paneId: String,
    val name: String?,
    val kind: String,
    val label: String,
    val status: AgentStatus,
    val title: String?,
    val cwd: String?,
    val workspace: String,
    val focused: Boolean,
)

sealed interface HerdrState {
    data object Checking : HerdrState
    data object NotInstalled : HerdrState
    data object NotRunning : HerdrState
    data class Failed(val message: String) : HerdrState
    data class Ready(val agents: List<HerdrAgent>) : HerdrState
}

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val name: String,
    val input: String,
    val output: String,
    val error: Boolean,
    val pending: Boolean,
    val ts: Long,
    val kind: String,
    val summary: String,
    val diff: String,
    val call: String = "",
    val images: List<String> = emptyList(),
    val from: String = "",
    val queued: Boolean = false,
    val background: Boolean = false,
)

data class Todo(val text: String, val status: String)

/** Model, effort and mode a Claude session runs with, read from its transcript. */
data class AgentSettings(val model: String = "", val effort: String = "", val permissionMode: String = "", val gitBranch: String = "")

/** A prompt the agent itself holds while it works (Claude's queue-operation entries). */
data class AgentQueued(val id: String, val text: String)

data class QueuedPrompt(val id: Long, val text: String)

data class Transcript(
    val screenMode: Boolean,
    val screen: String,
    val source: String,
    val matchedByDirectory: Boolean,
    val truncated: Boolean,
    val messages: List<ChatMessage>,
    val todos: List<Todo> = emptyList(),
    val contextUsed: Long = 0,
    val contextWindow: Long = 0,
    val settings: AgentSettings = AgentSettings(),
    val agentQueue: List<AgentQueued> = emptyList(),
) {
    companion object {
        fun parse(json: String): Transcript {
            val o = JSONObject(json)
            val arr = o.optJSONArray("messages") ?: JSONArray()
            val todos = o.optJSONArray("todos") ?: JSONArray()
            val ctx = o.optJSONObject("context")
            val set = o.optJSONObject("settings")
            val queued = o.optJSONArray("queued") ?: JSONArray()
            fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            return Transcript(
                screenMode = o.optString("mode") == "screen",
                screen = o.optString("screen"),
                source = o.optString("source"),
                matchedByDirectory = o.optString("matched") == "cwd",
                truncated = o.optBoolean("truncated"),
                messages = (0 until arr.length()).map {
                    val m = arr.getJSONObject(it)
                    ChatMessage(
                        id = m.optString("id"), role = m.optString("role"), text = m.optString("text"),
                        name = m.optString("name"), input = m.optString("input"), output = m.optString("output"),
                        error = m.optBoolean("error"), pending = m.optBoolean("pending"),
                        ts = m.optLong("ts"), kind = m.optString("kind"), summary = m.optString("summary"), diff = m.optString("diff"),
                        call = m.optString("call"), images = strings(m.optJSONArray("images")), from = m.optString("from"),
                        queued = m.optBoolean("queued"), background = m.optBoolean("background"),
                    )
                },
                todos = (0 until todos.length()).map { todos.getJSONObject(it).let { t -> Todo(t.optString("text"), t.optString("status")) } },
                contextUsed = ctx?.optLong("used") ?: 0,
                contextWindow = ctx?.optLong("window") ?: 0,
                settings = AgentSettings(
                    model = set?.optString("model").orEmpty().ifEmpty { o.optString("model") },
                    effort = set?.optString("effort").orEmpty(),
                    permissionMode = set?.optString("permission_mode").orEmpty(),
                    gitBranch = set?.optString("git_branch").orEmpty(),
                ),
                agentQueue = (0 until queued.length()).map { queued.getJSONObject(it).let { q -> AgentQueued(q.optString("id"), q.optString("text")) } },
            )
        }
    }
}

/**
 * One herdr connection per node, opened with the node's SSH profile. Herdr
 * events only invalidate; the agent list is re-read on every change.
 */
class HerdrHub(private val context: Context, private val keys: SshKeys) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val conns = HashMap<String, Herdr>()
    private val profiles = HashMap<String, SshProfile>()
    private val retries = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, HerdrState>>(emptyMap())
    val states: StateFlow<Map<String, HerdrState>> = _states

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits a node name whenever that node's herdr state changed. */
    val changes: SharedFlow<String> = _changes

    private val _queues = MutableStateFlow<Map<Pair<String, String>, List<QueuedPrompt>>>(emptyMap())
    /** Prompts held on the phone until their agent stops working. */
    val queues: StateFlow<Map<Pair<String, String>, List<QueuedPrompt>>> = _queues
    private var queueSeq = 0L

    /** Pane currently open in the chat screen; it doesn't get notifications. */
    @Volatile var viewing: Pair<String, String>? = null
        set(v) {
            field = v
            if (v != null) markRead(v.first, v.second)
        }

    private val _unread = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    /** Agents that finished or asked something since they were last opened. */
    val unread: StateFlow<Set<Pair<String, String>>> = _unread

    fun markRead(node: String, pane: String) = _unread.update { it - (node to pane) }

    private val _muted = MutableStateFlow(TaildeckApp.instance.store.mutedWorkspaces())
    val muted: StateFlow<Set<String>> = _muted

    fun setMuted(node: String, workspace: String, muted: Boolean) {
        TaildeckApp.instance.store.setWorkspaceMuted(node, workspace, muted)
        _muted.value = TaildeckApp.instance.store.mutedWorkspaces()
    }

    fun isMuted(node: String, workspace: String) = Store.muteKey(node, workspace) in _muted.value

    /** Connects if needed. Profiles without usable credentials are ignored. */
    fun ensure(node: String, profile: SshProfile) {
        if (profile.auth == SshAuth.PASSWORD && profile.password.isEmpty()) return
        scope.launch {
            lock.withLock {
                profiles[node] = profile
                val current = _states.value[node]
                if (conns[node] != null && current is HerdrState.Ready) return@withLock
                if (current == HerdrState.Checking) return@withLock
                setState(node, HerdrState.Checking)
            }
            connect(node)
        }
    }

    private suspend fun connect(node: String) {
        val profile = profiles[node] ?: return
        val result = runCatching {
            val key = if (profile.auth == SshAuth.KEY) keys.paths(profile.keyId) ?: error("SSH key not found") else null
            Tsbridge.connectHerdr(
                node, profile.port.toLong(), profile.user,
                if (profile.auth == SshAuth.PASSWORD) profile.password else "",
                key?.key.orEmpty(), key?.passphrase.orEmpty(), key?.cert.orEmpty(),
                listener(node),
            )
        }
        lock.withLock {
            result.onSuccess { h ->
                conns.remove(node)?.close()
                conns[node] = h
            }.onFailure { e ->
                val msg = e.message.orEmpty()
                setState(
                    node,
                    when {
                        msg.startsWith("HERDR_NOT_INSTALLED") -> HerdrState.NotInstalled
                        msg.startsWith("HERDR_NOT_RUNNING") -> HerdrState.NotRunning
                        else -> HerdrState.Failed(msg)
                    },
                )
            }
        }
        if (result.isSuccess) refresh(node)
    }

    private fun listener(node: String) = object : HerdrListener {
        override fun onChange() {
            scope.launch { refresh(node) }
        }

        override fun onClosed(reason: String) {
            scope.launch {
                lock.withLock { conns.remove(node) }
                // Unexpected drops (network change, sleep) reconnect quietly.
                if (reason.isNotEmpty() && profiles.containsKey(node)) scheduleRetry(node)
            }
        }
    }

    private fun scheduleRetry(node: String) {
        retries[node]?.cancel()
        retries[node] = scope.launch {
            for (wait in listOf(2_000L, 8_000L, 30_000L)) {
                delay(wait)
                if (conns[node] != null) return@launch
                connect(node)
                if (conns[node] != null) return@launch
            }
        }
    }

    fun refreshNow(node: String) = scope.launch { refresh(node) }

    private suspend fun refresh(node: String) {
        val h = lock.withLock { conns[node] } ?: return
        val agents = runCatching {
            val labels = HashMap<String, String>()
            runCatching {
                val ws = JSONObject(h.call("workspace.list", "{}")).optJSONArray("workspaces") ?: JSONArray()
                for (i in 0 until ws.length()) {
                    val w = ws.getJSONObject(i)
                    labels[w.optString("workspace_id")] = w.optString("label").ifEmpty { w.optString("workspace_id") }
                }
            }
            val arr = JSONObject(h.call("agent.list", "{}")).optJSONArray("agents") ?: JSONArray()
            (0 until arr.length()).map { parseAgent(arr.getJSONObject(it), labels) }
        }.getOrElse {
            Log.w(TAG, "herdr refresh: ${it.message}")
            return
        }
        val previous = (_states.value[node] as? HerdrState.Ready)?.agents.orEmpty().associateBy { it.paneId }
        setState(node, HerdrState.Ready(agents))
        _changes.tryEmit(node)
        for (a in agents) {
            val before = previous[a.paneId]?.status ?: continue
            if (a.status != before && (a.status == AgentStatus.BLOCKED || a.status == AgentStatus.DONE)) {
                val looking = viewing == node to a.paneId && TaildeckApp.instance.foreground
                if (!looking) _unread.update { it + (node to a.paneId) }
                if (!looking && !isMuted(node, a.workspace)) notify(node, a)
            }
            if (a.status != before && a.status.settled()) flushQueue(node, a.paneId)
        }
    }

    private fun parseAgent(o: JSONObject, labels: Map<String, String>): HerdrAgent {
        val kind = o.optString("agent").ifEmpty { "agent" }
        val name = o.optString("name").ifEmpty { null }
        val display = o.optString("display_agent").ifEmpty { null }
        val ws = o.optString("workspace_id")
        return HerdrAgent(
            paneId = o.optString("pane_id"),
            name = name,
            kind = kind,
            label = name ?: display ?: kind,
            status = AgentStatus.of(o.optString("agent_status")),
            title = o.optString("terminal_title_stripped").ifEmpty { o.optString("title") }.ifEmpty { null },
            cwd = o.optString("foreground_cwd").ifEmpty { o.optString("cwd") }.ifEmpty { null },
            workspace = labels[ws] ?: ws,
            focused = o.optBoolean("focused"),
        )
    }

    private fun setState(node: String, state: HerdrState) = _states.update { it + (node to state) }

    suspend fun call(node: String, method: String, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val h = lock.withLock { conns[node] } ?: error("herdr is not connected")
        JSONObject(h.call(method, params.toString()))
    }

    private suspend fun conn(node: String): Herdr = lock.withLock { conns[node] } ?: error("herdr is not connected")

    suspend fun transcript(node: String, pane: String): Transcript = withContext(Dispatchers.IO) {
        Transcript.parse(conn(node).transcript(pane))
    }

    /** Reads further back next time; false when the whole session is already shown. */
    suspend fun transcriptMore(node: String, pane: String): Boolean = withContext(Dispatchers.IO) { conn(node).transcriptMore(pane) }

    suspend fun subagent(node: String, pane: String, call: String): Pair<Transcript, JSONObject?> = withContext(Dispatchers.IO) {
        val json = conn(node).subagent(pane, call)
        Transcript.parse(json) to JSONObject(json).optJSONObject("agent")
    }

    /** Quits the agent and opens the same session again, then sends [then] if given. */
    suspend fun restart(node: String, pane: String, then: String?) = withContext(Dispatchers.IO) {
        try {
            conn(node).restartAgent(pane, then.orEmpty())
        } finally {
            refresh(node)
        }
    }

    suspend fun screen(node: String, pane: String, ansi: Boolean = false): String {
        val p = JSONObject().put("target", pane).put("source", "visible").put("lines", 60)
        if (ansi) p.put("strip_ansi", false)
        return call(node, "agent.read", p).optJSONObject("read")?.optString("text").orEmpty()
    }

    suspend fun sendText(node: String, pane: String, text: String) {
        call(node, "pane.send_text", JSONObject().put("pane_id", pane).put("text", text))
    }

    suspend fun sendKeys(node: String, pane: String, keys: List<String>) {
        call(node, "agent.send_keys", JSONObject().put("target", pane).put("keys", JSONArray(keys)))
    }

    private val bytes = object : android.util.LruCache<String, ByteArray>(24 shl 20) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** An image from a transcript ("img:<id>") or a file the agent named on the node. */
    suspend fun image(node: String, ref: String): ByteArray = withContext(Dispatchers.IO) {
        val key = "$node\u0000$ref"
        bytes.get(key) ?: run {
            val h = conn(node)
            val b = if (ref.startsWith("img:")) h.image(ref) else h.readFile(ref, 20L shl 20)
            b.also { bytes.put(key, it) }
        }
    }

    suspend fun upload(node: String, name: String, data: ByteArray): String = withContext(Dispatchers.IO) { conn(node).upload(name, data) }

    data class Command(val name: String, val description: String, val source: String)

    data class ModelChoice(val id: String, val name: String, val efforts: List<String>, val default: String)
    data class ModelOptions(val models: List<ModelChoice>, val efforts: List<String>, val custom: Boolean)

    private val modelCache = HashMap<String, ModelOptions>()

    /** Models and reasoning levels the agent can switch to, from its own files on the node. */
    suspend fun modelOptions(node: String, kind: String): ModelOptions = withContext(Dispatchers.IO) {
        modelCache["$node|$kind"]?.let { return@withContext it }
        val o = JSONObject(conn(node).modelOptions(kind))
        fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        val arr = o.optJSONArray("models") ?: JSONArray()
        ModelOptions(
            models = (0 until arr.length()).map { arr.getJSONObject(it).let { m -> ModelChoice(m.optString("id"), m.optString("name"), strings(m.optJSONArray("efforts")), m.optString("default")) } },
            efforts = strings(o.optJSONArray("efforts")),
            custom = o.optBoolean("custom"),
        ).also { modelCache["$node|$kind"] = it }
    }

    private val commandCache = HashMap<String, Pair<Long, List<Command>>>()

    /** Prompt commands and skills for an agent of [kind] in [cwd], cached for a minute. */
    suspend fun commands(node: String, kind: String, cwd: String): List<Command> = withContext(Dispatchers.IO) {
        val key = "$node|$kind|$cwd"
        commandCache[key]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return@withContext it.second }
        val arr = JSONArray(conn(node).commands(kind, cwd))
        val list = (0 until arr.length()).map { arr.getJSONObject(it).let { c -> Command(c.optString("name"), c.optString("description"), c.optString("source")) } }
        commandCache[key] = System.currentTimeMillis() to list
        list
    }

    /** Waits for the node's herdr connection, opening it from the saved SSH profile. */
    private suspend fun awaitConnected(node: String): Boolean {
        if (lock.withLock { conns[node] } != null) return true
        val profile = profiles[node] ?: TaildeckApp.instance.store.sshProfile(node) ?: return false
        ensure(node, profile)
        repeat(30) {
            delay(500)
            if (lock.withLock { conns[node] } != null) return true
        }
        return false
    }

    fun enqueue(node: String, pane: String, text: String) {
        _queues.update { it + ((node to pane) to it[node to pane].orEmpty() + QueuedPrompt(++queueSeq, text)) }
        val status = (_states.value[node] as? HerdrState.Ready)?.agents?.firstOrNull { it.paneId == pane }?.status
        if (status?.settled() == true) scope.launch { flushQueue(node, pane) }
    }

    fun dequeue(node: String, pane: String, id: Long): QueuedPrompt? {
        var found: QueuedPrompt? = null
        _queues.update { all ->
            val list = all[node to pane].orEmpty()
            found = list.firstOrNull { it.id == id }
            all + ((node to pane) to list.filterNot { it.id == id })
        }
        return found
    }

    suspend fun prompt(node: String, pane: String, text: String) {
        call(node, "agent.prompt", JSONObject().put("target", pane).put("text", text))
    }

    /** These agents hold prompts typed while they work themselves, so the phone sends right away. */
    fun queuesNatively(kind: String) = kind in setOf("claude", "codex", "pi", "omp")

    // One prompt per settle: the agent starts working again and the next waits.
    private suspend fun flushQueue(node: String, pane: String) {
        val next = _queues.value[node to pane]?.firstOrNull() ?: return
        runCatching { prompt(node, pane, next.text) }
            .onSuccess { dequeue(node, pane, next.id) }
            .onFailure { Log.w(TAG, "queued prompt: ${it.message}") }
    }

    suspend fun workspaces(node: String): List<Pair<String, String>> {
        val arr = call(node, "workspace.list", JSONObject()).optJSONArray("workspaces") ?: JSONArray()
        return (0 until arr.length()).map {
            val w = arr.getJSONObject(it)
            w.optString("workspace_id") to w.optString("label").ifEmpty { w.optString("workspace_id") }
        }
    }

    /** Opens a tab (or a new workspace) and starts [kind] in it; returns the pane id. */
    suspend fun startAgent(node: String, kind: String, name: String, workspaceId: String?, cwd: String?): String {
        val params = JSONObject().put("label", name)
        if (!cwd.isNullOrBlank()) params.put("cwd", cwd.trim())
        val created = if (workspaceId == null) call(node, "workspace.create", params)
        else call(node, "tab.create", params.put("workspace_id", workspaceId))
        val pane = created.getJSONObject("root_pane").getString("pane_id")
        runCatching { call(node, "agent.start", JSONObject().put("name", name).put("kind", kind).put("pane_id", pane)) }
            .onFailure {
                runCatching { call(node, "pane.close", JSONObject().put("pane_id", pane)) }
                throw it
            }
        refresh(node)
        return pane
    }

    suspend fun rename(node: String, pane: String, name: String?) {
        call(node, "agent.rename", JSONObject().put("target", pane).put("name", name ?: JSONObject.NULL))
        refresh(node)
    }

    fun closeAll() {
        scope.launch {
            lock.withLock {
                retries.values.forEach { it.cancel() }
                retries.clear()
                profiles.clear()
                conns.values.forEach { it.close() }
                conns.clear()
                _states.value = emptyMap()
            }
        }
    }

    // ---- notifications for agents that need attention ----

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun notify(node: String, agent: HerdrAgent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.agents_channel), NotificationManager.IMPORTANCE_HIGH))
        val id = notificationId(node, agent.paneId)
        val text = context.getString(if (agent.status == AgentStatus.BLOCKED) R.string.agent_needs_you else R.string.agent_finished)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${agent.label} · $node")
            .setContentText(agent.title?.let { "$text · $it" } ?: text)
            .setContentIntent(openIntent(node, agent.paneId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (agent.status == AgentStatus.BLOCKED) {
            // Allow/Deny only for a plain yes/no prompt; the screen it was read
            // from travels along so the answer is checked against it first.
            val seen = runCatching { screen(node, agent.paneId) }.getOrDefault("")
            val prompt = parsePrompt(seen)
            if (prompt != null && yesIndex(prompt) >= 0 && noIndex(prompt) >= 0) {
                prompt.question?.let { b.setContentText(it) }
                b.addAction(0, context.getString(R.string.agent_allow), actionIntent(node, agent.paneId, AgentActionReceiver.ALLOW, seen))
                b.addAction(0, context.getString(R.string.agent_deny), actionIntent(node, agent.paneId, AgentActionReceiver.DENY, seen))
            }
        } else {
            val input = androidx.core.app.RemoteInput.Builder(AgentActionReceiver.REPLY_TEXT).setLabel(context.getString(R.string.agent_reply)).build()
            b.addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.agent_reply), actionIntent(node, agent.paneId, AgentActionReceiver.REPLY, null, mutable = true))
                    .addRemoteInput(input).build(),
            )
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
    }

    fun notificationId(node: String, pane: String) = (node + pane).hashCode()

    private fun openIntent(node: String, pane: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, Routes.agent(node, pane))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, notificationId(node, pane), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(node: String, pane: String, action: String, screen: String?, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, AgentActionReceiver::class.java).setAction(action)
            .setData(android.net.Uri.parse("taildeck-agent://$action/${android.net.Uri.encode(node)}/${android.net.Uri.encode(pane)}"))
            .putExtra(AgentActionReceiver.NODE, node).putExtra(AgentActionReceiver.PANE, pane)
            .putExtra(AgentActionReceiver.SCREEN, screen?.takeLast(16_000))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, notificationId(node, pane) + action.hashCode(), intent, flags)
    }

    private fun yesIndex(p: ScreenPrompt) = p.options.indexOfFirst { it.number == "1" && YES.containsMatchIn(it.label) }
    private fun noIndex(p: ScreenPrompt) = p.options.indexOfFirst { NO.containsMatchIn(it.label) }

    /**
     * Answers a prompt from its notification, but only if the screen still
     * shows the question the notification was made from.
     */
    fun answerFromNotification(node: String, pane: String, allow: Boolean, seen: String) = scope.launch {
        val ok = runCatching {
            if (!awaitConnected(node)) return@runCatching false
            val then = parsePrompt(seen) ?: return@runCatching false
            val now = parsePrompt(screen(node, pane)) ?: return@runCatching false
            if (!now.sameQuestion(then)) return@runCatching false
            val i = if (allow) yesIndex(now) else noIndex(now)
            if (i < 0) return@runCatching false
            sendKeys(node, pane, now.moveTo(now.rowOf(i))?.plus("enter") ?: listOf(now.options[i].number))
            true
        }.getOrDefault(false)
        settleNotification(node, pane, if (ok) null else context.getString(R.string.agent_answer_failed))
    }

    fun replyFromNotification(node: String, pane: String, text: String) = scope.launch {
        val ok = runCatching { awaitConnected(node) && run { prompt(node, pane, text); true } }.getOrDefault(false)
        settleNotification(node, pane, if (ok) null else context.getString(R.string.agent_reply_failed))
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun settleNotification(node: String, pane: String, failure: String?) {
        val nm = NotificationManagerCompat.from(context)
        val id = notificationId(node, pane)
        if (failure == null) {
            nm.cancel(id)
            refreshNow(node)
            return
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(failure)
            .setContentText(context.getString(R.string.agent_open_to_answer))
            .setContentIntent(openIntent(node, pane))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    private companion object {
        const val TAG = "HerdrHub"
        const val CHANNEL = "agents"
        val YES = Regex("^(Yes|Allow|Approve|Proceed)\\b", RegexOption.IGNORE_CASE)
        val NO = Regex("^(No|Deny|Reject|Cancel)\\b", RegexOption.IGNORE_CASE)
    }
}
