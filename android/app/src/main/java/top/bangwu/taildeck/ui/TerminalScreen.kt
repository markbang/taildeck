package top.bangwu.taildeck.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import top.bangwu.taildeck.tsbridge.SSHListener
import top.bangwu.taildeck.tsbridge.SSHSession
import top.bangwu.taildeck.tsbridge.Tsbridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

private sealed interface Conn {
    data object Idle : Conn
    data object Connecting : Conn
    data object Connected : Conn
    data class Closed(val reason: String) : Conn
}

// Bridges xterm.js in a WebView to a Go SSH session. Output is coalesced per
// frame because evaluateJavascript per chunk is slow on bulk output.
private class TerminalController(private val keys: SshKeys) {
    var webView: WebView? = null
    var session: SSHSession? = null
    var cols = 80
    var rows = 24
    var ready = false
    var onReady: () -> Unit = {}
    var onState: (Conn) -> Unit = {}
    var onCtrlConsumed: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pending = ByteArrayOutputStream()
    private var flushScheduled = false

    fun write(bytes: ByteArray) {
        synchronized(pending) {
            pending.write(bytes)
            if (flushScheduled) return
            flushScheduled = true
        }
        main.postDelayed({
            val data = synchronized(pending) {
                flushScheduled = false
                pending.toByteArray().also { pending.reset() }
            }
            if (data.isNotEmpty()) {
                webView?.evaluateJavascript("termWrite('${Base64.encodeToString(data, Base64.NO_WRAP)}')", null)
            }
        }, 16)
    }

    fun banner(text: String) = write("\u001b[2m$text\u001b[0m\r\n".toByteArray())

    fun send(bytes: ByteArray) {
        val s = session ?: return
        io.execute { runCatching { s.write(bytes) } }
    }

    fun connect(host: String, profile: SshProfile) {
        onState(Conn.Connecting)
        banner("ssh ${profile.user}@$host -p ${profile.port}")
        val listener = object : SSHListener {
            override fun onData(data: ByteArray) = write(data)
            override fun onClosed(reason: String) {
                main.post {
                    session = null
                    onState(Conn.Closed(reason))
                }
            }
        }
        io.execute {
            try {
                val pass = if (profile.auth == SshAuth.PASSWORD) profile.password else ""
                val key = if (profile.auth == SshAuth.KEY) {
                    keys.paths(profile.keyId) ?: throw IllegalStateException("SSH key not found; pick another key")
                } else {
                    null
                }
                val s = Tsbridge.dialSSH(
                    host, profile.port.toLong(), profile.user, pass,
                    key?.key.orEmpty(), key?.passphrase.orEmpty(), key?.cert.orEmpty(),
                    cols.toLong(), rows.toLong(), listener,
                )
                main.post {
                    session = s
                    onState(Conn.Connected)
                }
            } catch (e: Exception) {
                write("\r\n\u001b[31m${e.message}\u001b[0m\r\n".toByteArray())
                main.post { onState(Conn.Closed(e.message ?: "")) }
            }
        }
    }

    fun close() {
        val s = session
        session = null
        io.execute { s?.close() }
        io.shutdown()
    }

    @JavascriptInterface
    fun ready(c: Int, r: Int) {
        cols = c
        rows = r
        main.post {
            ready = true
            onReady()
        }
    }

    @JavascriptInterface
    fun input(data: String) = send(data.toByteArray(Charsets.UTF_8))

    @JavascriptInterface
    fun resize(c: Int, r: Int) {
        cols = c
        rows = r
        val s = session ?: return
        io.execute { runCatching { s.resize(c.toLong(), r.toLong()) } }
    }

    @JavascriptInterface
    fun ctrlConsumed() = main.post { onCtrlConsumed() }.let { }
}

private val EXTRA_KEYS = listOf(
    "Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
    "Home" to "\u001b[H", "End" to "\u001b[F", "|" to "|", "/" to "/", "-" to "-", "~" to "~",
)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(node: String, onBack: () -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    val saved = remember { app.store.sshProfile(node) }
    val controller = remember { TerminalController(app.keys) }
    var conn by remember { mutableStateOf<Conn>(Conn.Idle) }
    var profile by remember { mutableStateOf(saved?.takeIf { it.auth != SshAuth.PASSWORD || it.password.isNotEmpty() }) }
    var showDialog by remember { mutableStateOf(profile == null) }
    var ctrl by remember { mutableStateOf(false) }
    var termReady by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(if (profile != null) 1 else 0) }

    controller.onState = { conn = it }
    controller.onReady = { termReady = true }
    controller.onCtrlConsumed = { ctrl = false }

    LaunchedEffect(termReady, attempt) {
        val p = profile
        if (termReady && attempt > 0 && p != null) controller.connect(node, p)
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.close()
            controller.webView?.destroy()
        }
    }

    fun showKeyboard() {
        val wv = controller.webView ?: return
        wv.requestFocus()
        wv.evaluateJavascript("focusTerm()", null)
        context.getSystemService(InputMethodManager::class.java).showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0F1113)).imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text(node, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when (val c = conn) {
                        Conn.Connecting -> stringResource(R.string.ssh_connecting, node)
                        is Conn.Closed -> stringResource(R.string.ssh_closed) + (if (c.reason.isNotEmpty()) " · ${c.reason}" else "")
                        else -> profile?.let { "${it.user}@$node:${it.port}" } ?: ""
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (conn is Conn.Closed) {
                    TextButton(onClick = { showDialog = true }) { Text(stringResource(R.string.reconnect)) }
                }
                IconButton(onClick = ::showKeyboard) { Icon(Icons.Outlined.Keyboard, stringResource(R.string.keyboard)) }
            },
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0xFF0F1113.toInt())
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(controller, "Native")
                    loadUrl("file:///android_asset/term/index.html")
                    controller.webView = this
                }
            },
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = ctrl,
                    onClick = {
                        ctrl = !ctrl
                        controller.webView?.evaluateJavascript("armCtrl($ctrl)", null)
                        if (ctrl) showKeyboard()
                    },
                    label = { Text("Ctrl", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                EXTRA_KEYS.forEach { (label, seq) ->
                    TextButton(onClick = { controller.send(seq.toByteArray()) }) {
                        Text(label, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    if (showDialog) {
        SshDialog(
            node = node,
            initial = saved ?: SshProfile(),
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        SshAuth.TAILSCALE to R.string.ssh_auth_tailscale,
                        SshAuth.PASSWORD to R.string.ssh_auth_password,
                        SshAuth.KEY to R.string.ssh_auth_key,
                    ).forEach { (a, label) ->
                        FilterChip(selected = auth == a, onClick = { auth = a }, label = { Text(stringResource(label)) })
                    }
                }
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
                        TextButton(onClick = { importing = true }) { Text(stringResource(R.string.import_key)) }
                        val selected = identities.firstOrNull { it.id == keyId }
                        if (selected != null && !selected.hasCert) {
                            Text(stringResource(R.string.ssh_key_hint), style = MaterialTheme.typography.bodySmall)
                            Text(selected.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { context.copy(selected.publicKey) }) { Text(stringResource(R.string.copy_public_key)) }
                        }
                    }
                    SshAuth.TAILSCALE -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(SshProfile(user, portNum ?: 22, auth, if (auth == SshAuth.PASSWORD) password else "", keyId), rememberPw) },
                enabled = user.isNotBlank() && portNum != null && (auth != SshAuth.KEY || identities.any { it.id == keyId }),
            ) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (importing) {
        KeyImportDialog(onDismiss = { importing = false }) {
            keyId = it.id
            importing = false
        }
    }
}
