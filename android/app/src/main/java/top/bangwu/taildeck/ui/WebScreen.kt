package top.bangwu.taildeck.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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

private class AuthPrompt(val host: String, val handler: HttpAuthHandler)

/** In-app fallback; the WebView loads the same loopback gateway URL a browser would. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(url: String, title: String, onBack: () -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    val localUrl = remember(url) { runCatching { app.tailscale.localUrl(url) } }
    var pageTitle by remember { mutableStateOf(title) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var authPrompt by remember { mutableStateOf<AuthPrompt?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data) else null
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

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
                                url,
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
                        IconButton(onClick = { context.openInBrowser(url) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                        }
                    },
                )
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            localUrl.onFailure { Text(it.message ?: "", Modifier.padding(24.dp)) }
            localUrl.onSuccess { start ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // Some apps serve stripped-down pages to the "; wv" WebView UA.
                            settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme
                                    if (scheme == "http" || scheme == "https") return false
                                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) }
                                    return true
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

                                override fun onShowFileChooser(
                                    view: WebView,
                                    callback: ValueCallback<Array<Uri>>,
                                    params: FileChooserParams,
                                ): Boolean {
                                    fileCallback?.onReceiveValue(null)
                                    fileCallback = callback
                                    return runCatching { filePicker.launch(params.createIntent()) }
                                        .onFailure { fileCallback = null; callback.onReceiveValue(null) }
                                        .isSuccess
                                }
                            }
                            setDownloadListener { downloadUrl, _, _, _, _ ->
                                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(downloadUrl))) }
                            }
                            loadUrl(start)
                            webView = this
                        }
                    },
                )
            }
        }
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
