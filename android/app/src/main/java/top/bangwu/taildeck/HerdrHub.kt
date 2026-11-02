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
)

data class Todo(val text: String, val status: String)

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
    val model: String = "",
) {
    companion object {
        fun parse(json: String): Transcript {
            val o = JSONObject(json)
            val arr = o.optJSONArray("messages") ?: JSONArray()
            val todos = o.optJSONArray("todos") ?: JSONArray()
            val ctx = o.optJSONObject("context")
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
                    )
                },
                todos = (0 until todos.length()).map { todos.getJSONObject(it).let { t -> Todo(t.optString("text"), t.optString("status")) } },
                contextUsed = ctx?.optLong("used") ?: 0,
                contextWindow = ctx?.optLong("window") ?: 0,
                model = o.optString("model"),
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
            if (a.status != before && (a.status == AgentStatus.BLOCKED || a.status == AgentStatus.DONE)) notify(node, a)
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

    suspend fun transcript(node: String, pane: String): Transcript = withContext(Dispatchers.IO) {
        val h = lock.withLock { conns[node] } ?: error("herdr is not connected")
        Transcript.parse(h.transcript(pane))
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
    private fun notify(node: String, agent: HerdrAgent) {
        if (viewing == node to agent.paneId && TaildeckApp.instance.foreground) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.agents_channel), NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, Routes.agent(node, agent.paneId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val id = (node + agent.paneId).hashCode()
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.getString(if (agent.status == AgentStatus.BLOCKED) R.string.agent_needs_you else R.string.agent_finished)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${agent.label} · $node")
            .setContentText(agent.title?.let { "$text · $it" } ?: text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    private companion object {
        const val TAG = "HerdrHub"
        const val CHANNEL = "agents"
    }
}
