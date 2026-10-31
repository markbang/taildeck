package top.bangwu.taildeck.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.bangwu.taildeck.R
import top.bangwu.taildeck.TaildeckApp

private class SslPrompt(val host: String, val handler: SslErrorHandler)
private class AuthPrompt(val host: String, val handler: HttpAuthHandler)

// Hosts the user accepted a bad certificate for, kept for the process lifetime.
private val trustedHosts = mutableSetOf<String>()

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(url: String, title: String, onBack: () -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    var proxyReady by remember { mutableStateOf<Boolean?>(null) }
    var pageTitle by remember { mutableStateOf(title) }
    var currentUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var sslPrompt by remember { mutableStateOf<SslPrompt?>(null) }
    var authPrompt by remember { mutableStateOf<AuthPrompt?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) { proxyReady = app.tailscale.ensureWebProxy() }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(pageTitle.ifEmpty { title }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                currentUrl,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.Close, stringResource(R.string.back)) }
                    },
                    actions = {
                        if (canGoBack) {
                            IconButton(onClick = { webView?.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                        }
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.reload)) }
                    },
                )
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (proxyReady) {
                null -> Unit
                false -> Text(stringResource(R.string.web_no_proxy), Modifier.padding(24.dp))
                true -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    currentUrl = url
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    currentUrl = url
                                    canGoBack = view.canGoBack()
                                }

                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    val host = runCatching { java.net.URI(error.url).host }.getOrNull() ?: error.url
                                    if (host in trustedHosts) handler.proceed() else sslPrompt = SslPrompt(host, handler)
                                }

                                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                                    authPrompt = AuthPrompt(host, handler)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView, t: String?) {
                                    if (!t.isNullOrBlank() && !t.startsWith("http")) pageTitle = t
                                }
                            }
                            setDownloadListener { _, _, _, _, _ ->
                                Toast.makeText(ctx, R.string.web_download_unsupported, Toast.LENGTH_SHORT).show()
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                )
            }
        }
    }

    sslPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { p.handler.cancel(); sslPrompt = null },
            title = { Text(stringResource(R.string.web_cert_title)) },
            text = { Text(stringResource(R.string.web_cert_body, p.host)) },
            confirmButton = {
                TextButton(onClick = { trustedHosts += p.host; p.handler.proceed(); sslPrompt = null }) {
                    Text(stringResource(R.string.web_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { p.handler.cancel(); sslPrompt = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    authPrompt?.let { p ->
        var user by remember(p) { mutableStateOf("") }
        var pass by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { p.handler.cancel(); authPrompt = null },
            title = { Text(stringResource(R.string.web_auth_title, p.host)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.ssh_user)) }, singleLine = true)
                    OutlinedTextField(
                        pass,
                        { pass = it },
                        label = { Text(stringResource(R.string.ssh_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { p.handler.proceed(user, pass); authPrompt = null }) { Text(stringResource(R.string.sign_in)) }
            },
            dismissButton = {
                TextButton(onClick = { p.handler.cancel(); authPrompt = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
