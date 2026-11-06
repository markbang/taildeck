package top.bangwu.taildeck.ui

import top.bangwu.taildeck.HerdrState
import top.bangwu.taildeck.AgentStatus
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.bangwu.taildeck.R
import top.bangwu.taildeck.SshAuth
import top.bangwu.taildeck.SshKeys
import top.bangwu.taildeck.SshProfile
import top.bangwu.taildeck.TaildeckApp
import top.bangwu.taildeck.term.TerminalView
import top.bangwu.taildeck.tsbridge.SSHListener
import top.bangwu.taildeck.tsbridge.SSHSession
import top.bangwu.taildeck.tsbridge.Tsbridge
import java.util.concurrent.Executors

private sealed interface Conn {
    data object Idle : Conn
    data object Connecting : Conn
    data object Connected : Conn
    data class Closed(val reason: String) : Conn
}

// Owns the SSH session for one screen and pipes bytes between it and the
// Ghostty-backed TerminalView.
private class SshController(private val keys: SshKeys, private val res: android.content.res.Resources) {
    var view: TerminalView? = null
    var onState: (Conn) -> Unit = {}
    var onNeedSettings: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var session: SSHSession? = null
    private var generation = 0

    fun feed(bytes: ByteArray) {
        view?.feed(bytes)
    }

    fun banner(text: String) = feed("\u001b[2m$text\u001b[0m\r\n".toByteArray())

    fun send(bytes: ByteArray) {
        val s = session ?: return
        io.execute { runCatching { s.write(bytes) } }
    }

    fun resize(cols: Int, rows: Int) {
        val s = session ?: return
        io.execute { runCatching { s.resize(cols.toLong(), rows.toLong()) } }
    }

    fun connect(host: String, profile: SshProfile) {
        val v = view ?: return
        val gen = ++generation
        session?.let { old ->
            session = null
            io.execute { old.close() }
        }
        onState(Conn.Connecting)
        banner("ssh ${profile.user}@$host -p ${profile.port}")
        val listener = object : SSHListener {
            override fun onData(data: ByteArray) = feed(data)
            override fun onClosed(reason: String) {
                main.post {
                    if (gen != generation) return@post
                    session = null
                    onState(Conn.Closed(reason))
                }
            }
        }
        val cols = v.cols
        val rows = v.rows
        io.execute {
            try {
                val pass = if (profile.auth == SshAuth.PASSWORD) profile.password else ""
                val key = if (profile.auth == SshAuth.KEY) {
                    keys.paths(profile.keyId) ?: run {
                        main.post { if (gen == generation) onNeedSettings() }
                        throw IllegalStateException("SSH key not found; pick another key")
                    }
                } else {
                    null
                }
                val s = Tsbridge.dialSSH(
                    host, profile.port.toLong(), profile.user, pass,
                    key?.key.orEmpty(), key?.passphrase.orEmpty(), key?.cert.orEmpty(),
                    cols.toLong(), rows.toLong(), listener,
                )
                main.post {
                    if (gen != generation) {
                        io.execute { s.close() }
                        return@post
                    }
                    session = s
                    onState(Conn.Connected)
                    // Resizes during the handshake were dropped; sync the PTY now.
                    view?.let { resize(it.cols, it.rows) }
                }
            } catch (e: Exception) {
                feed("\r\n\u001b[31m${e.message}\u001b[0m\r\n".toByteArray())
                if (e.message?.contains("unable to authenticate") == true) {
                    authHints(host, profile, e.message.orEmpty()).forEach { feed("\u001b[33m$it\u001b[0m\r\n".toByteArray()) }
                }
                main.post { if (gen == generation) onState(Conn.Closed(e.message ?: "")) }
            }
        }
    }

    // The server only says which methods failed; spell out the usual causes
    // for the kind of SSH server we actually reached.
    private fun authHints(host: String, profile: SshProfile, error: String): List<String> {
        val user = profile.user
        val server = Regex("""\[server (SSH-[^\]]+)]""").find(error)?.groupValues?.get(1)
        val lines = mutableListOf("", res.getString(R.string.ssh_auth_rejected, user, host))
        server?.let { lines += res.getString(R.string.ssh_auth_server, it) }
        if (server?.contains("Tailscale", ignoreCase = true) == true) {
            lines += res.getString(R.string.ssh_hint_tailscale_server, user)
            return lines
        }
        val dropbear = server?.contains("dropbear", ignoreCase = true) == true
        when (profile.auth) {
            SshAuth.KEY -> {
                val id = keys.list.value.firstOrNull { it.id == profile.keyId }
                id?.let { lines += res.getString(R.string.ssh_auth_used_key, it.type, it.fingerprint) }
                if (id != null && id.hasCert && user !in id.certPrincipals) {
                    lines += res.getString(R.string.ssh_hint_cert_principal, id.certPrincipals.joinToString(","), user)
                }
                lines += res.getString(R.string.ssh_hint_works_elsewhere, user, host)
                lines += if (dropbear && user == "root") {
                    res.getString(R.string.ssh_hint_dropbear_keys)
                } else {
                    res.getString(R.string.ssh_hint_authorized_keys, user)
                }
                if (!dropbear) {
                    if (user == "root") lines += res.getString(R.string.ssh_hint_root)
                    lines += res.getString(R.string.ssh_hint_from_restriction)
                    lines += res.getString(R.string.ssh_hint_server_log)
                }
            }
            SshAuth.PASSWORD -> {
                lines += res.getString(R.string.ssh_hint_password)
                if (user == "root" && !dropbear) lines += res.getString(R.string.ssh_hint_root)
            }
            SshAuth.TAILSCALE -> lines += res.getString(R.string.ssh_hint_tailscale, user)
        }
        return lines
    }

    fun close() {
        val s = session
        session = null
        io.execute { s?.close() }
        io.shutdown()
        view?.close()
    }
}

private val EXTRA_KEYS = listOf(
    "Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB,
    "↑" to KeyEvent.KEYCODE_DPAD_UP, "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
    "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
    "PgUp" to KeyEvent.KEYCODE_PAGE_UP, "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN,
)
private val EXTRA_CHARS = listOf("|", "/", "-", "~", "_", ":")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(node: String, onBack: () -> Unit, onAgents: () -> Unit) {
    val app = TaildeckApp.instance
    val saved = remember { app.store.sshProfile(node) }
    val controller = remember { SshController(app.keys, app.resources) }
    var conn by remember { mutableStateOf<Conn>(Conn.Idle) }
    var profile by remember { mutableStateOf(saved?.takeIf { it.auth != SshAuth.PASSWORD || it.password.isNotEmpty() }) }
    var showDialog by remember { mutableStateOf(profile == null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf<String?>(null) }
    var viewReady by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(if (profile != null) 1 else 0) }

    controller.onState = { conn = it }
    val herdrStates by app.herdr.states.collectAsStateWithLifecycle()
    val herdrAgents = (herdrStates[node] as? HerdrState.Ready)?.agents
    // Once the shell works, the same credentials can reach herdr's socket.
    LaunchedEffect(conn) {
        val p = profile
        if (conn == Conn.Connected && p != null) app.herdr.ensure(node, p)
    }
    controller.onNeedSettings = { showDialog = true }

    LaunchedEffect(viewReady, attempt) {
        val p = profile
        if (viewReady && attempt > 0 && p != null) controller.connect(node, p)
    }
    DisposableEffect(Unit) { onDispose { controller.close() } }

    Column(Modifier.fillMaxSize().background(Color(TerminalView.BG)).imePadding()) {
        TopAppBar(
            title = { Text(title?.ifBlank { null } ?: node, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            subtitle = {
                val sub = when (val c = conn) {
                    Conn.Connecting -> stringResource(R.string.ssh_connecting, node)
                    is Conn.Closed -> stringResource(R.string.ssh_closed) + (if (c.reason.isNotEmpty()) " · ${c.reason}" else "")
                    else -> profile?.let { "${it.user}@$node:${it.port}" } ?: ""
                }
                AnimatedContent(sub, transitionSpec = { fadeIn(Motion.fade()).togetherWith(fadeOut(Motion.fadeFast())) }, label = "conn") {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                AnimatedVisibility(conn is Conn.Closed, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut()) {
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = { if (profile == null) showDialog = true else attempt++ }) { Text(stringResource(R.string.reconnect)) }
                }
                AnimatedVisibility(herdrAgents != null, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut()) {
                    val blocked = herdrAgents.orEmpty().count { it.status == AgentStatus.BLOCKED }
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = onAgents) {
                        BadgedBox(badge = { if (blocked > 0) Badge { Text("$blocked") } }) {
                            Icon(Icons.Outlined.SmartToy, stringResource(R.string.agents_title))
                        }
                    }
                }
                IconButton(shapes = IconButtonDefaults.shapes(), onClick = { showDialog = true }) { Icon(Icons.Outlined.ManageAccounts, stringResource(R.string.ssh_settings)) }
                IconButton(shapes = IconButtonDefaults.shapes(), onClick = { controller.view?.showKeyboard() }) { Icon(Icons.Outlined.Keyboard, stringResource(R.string.keyboard)) }
            },
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            factory = { ctx ->
                TerminalView(ctx).apply {
                    onSend = controller::send
                    onResize = { c, r -> controller.resize(c, r) }
                    onTitle = { t -> post { title = t } }
                    onModifiersConsumed = { post { ctrl = false; alt = false } }
                    controller.view = this
                    viewReady = true
                }
            },
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleButton(
                    checked = ctrl,
                    onCheckedChange = { ctrl = it; controller.view?.ctrlArmed = it },
                    shapes = ToggleButtonDefaults.shapes(),
                    modifier = Modifier.padding(horizontal = 2.dp),
                ) { Text("Ctrl", fontFamily = FontFamily.Monospace) }
                ToggleButton(
                    checked = alt,
                    onCheckedChange = { alt = it; controller.view?.altArmed = it },
                    shapes = ToggleButtonDefaults.shapes(),
                    modifier = Modifier.padding(horizontal = 2.dp),
                ) { Text("Alt", fontFamily = FontFamily.Monospace) }
                EXTRA_KEYS.forEach { (label, code) ->
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = { controller.view?.sendKey(code) }) { Text(label, fontFamily = FontFamily.Monospace) }
                }
                EXTRA_CHARS.forEach { ch ->
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = { controller.view?.sendText(ch) }) { Text(ch, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }

    if (showDialog) {
        SshDialog(
            node = node,
            initial = profile ?: saved ?: SshProfile(),
            onDismiss = {
                showDialog = false
                if (profile == null) onBack()
            },
            onConnect = { p, remember ->
                app.store.saveSshProfile(node, if (remember) p else p.copy(password = ""))
                showDialog = false
                if (conn is Conn.Closed) controller.banner("")
                profile = p
                attempt++
            },
        )
    }
}

@Composable
private fun SshDialog(node: String, initial: SshProfile, onDismiss: () -> Unit, onConnect: (SshProfile, Boolean) -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    var user by remember { mutableStateOf(initial.user) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var auth by remember { mutableStateOf(initial.auth) }
    var password by remember { mutableStateOf(initial.password) }
    var rememberPw by remember { mutableStateOf(initial.password.isNotEmpty()) }
    var keyId by remember { mutableStateOf(initial.keyId) }
    var importing by remember { mutableStateOf(false) }
    val identities by app.keys.list.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { runCatching { app.keys.ensureBuiltin() } }
    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_connect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(node, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it.trim() }, label = { Text(stringResource(R.string.ssh_user)) }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(
                        port,
                        { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.ssh_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                val authLabels = mapOf(
                    SshAuth.TAILSCALE to R.string.ssh_auth_tailscale,
                    SshAuth.PASSWORD to R.string.ssh_auth_password,
                    SshAuth.KEY to R.string.ssh_auth_key,
                )
                ChoiceGroup(authLabels.keys.toList(), auth, onSelect = { auth = it }) { Text(stringResource(authLabels.getValue(it)), maxLines = 1) }
                when (auth) {
                    SshAuth.PASSWORD -> {
                        OutlinedTextField(
                            password,
                            { password = it },
                            label = { Text(stringResource(R.string.ssh_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(rememberPw, { rememberPw = it })
                            Text(stringResource(R.string.ssh_remember), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    SshAuth.KEY -> {
                        Text(stringResource(R.string.ssh_choose_key), style = MaterialTheme.typography.labelLarge)
                        identities.forEach { id ->
                            Row(
                                Modifier.fillMaxWidth().clickable { keyId = id.id },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = keyId == id.id, onClick = { keyId = id.id })
                                Column(Modifier.weight(1f)) {
                                    Text(if (id.builtin) stringResource(R.string.key_builtin) else id.name)
                                    Text(identitySubtitle(id), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        TextButton(shapes = ButtonDefaults.shapes(), onClick = { importing = true }) { Text(stringResource(R.string.import_key)) }
                        val selected = identities.firstOrNull { it.id == keyId }
                        if (selected != null && !selected.hasCert) {
                            Text(stringResource(R.string.ssh_key_hint), style = MaterialTheme.typography.bodySmall)
                            Text(selected.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(shapes = ButtonDefaults.shapes(), onClick = { context.copy(selected.publicKey) }) { Text(stringResource(R.string.copy_public_key)) }
                        }
                    }
                    SshAuth.TAILSCALE -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                shapes = ButtonDefaults.shapes(),
                onClick = { onConnect(SshProfile(user, portNum ?: 22, auth, if (auth == SshAuth.PASSWORD) password else "", keyId), rememberPw) },
                enabled = user.isNotBlank() && portNum != null && (auth != SshAuth.KEY || identities.any { it.id == keyId }),
            ) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (importing) {
        KeyImportDialog(onDismiss = { importing = false }) {
            keyId = it.id
            importing = false
        }
    }
}
