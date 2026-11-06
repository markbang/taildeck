package top.bangwu.taildeck.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import top.bangwu.taildeck.R
import top.bangwu.taildeck.TaildeckApp
import java.util.UUID

private class AuthPrompt(val host: String, val handler: HttpAuthHandler)
private class SslPrompt(val host: String, val handler: SslErrorHandler)
private class ExternalPrompt(val uri: Uri)
private class LinkMenu(val link: String?, val image: String?)
private class Fullscreen(val view: View, val callback: WebChromeClient.CustomViewCallback)

// Hosts the user accepted a bad certificate for, kept for the process lifetime.
private val trustedHosts = mutableSetOf<String>()

/**
 * Loads the tailnet URL as-is through the gateway's forward proxy. Only when
 * the WebView can't override proxies does it fall back to the localhost URL.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(url: String, title: String, onBack: () -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var startUrl by remember { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(url) {
        startUrl = if (app.tailscale.ensureWebProxy()) Result.success(url) else runCatching { app.tailscale.localUrl(url) }
    }
    var pageTitle by remember { mutableStateOf(title) }
    var pageUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sslPrompt by remember { mutableStateOf<SslPrompt?>(null) }
    var authPrompt by remember { mutableStateOf<AuthPrompt?>(null) }
    var externalPrompt by remember { mutableStateOf<ExternalPrompt?>(null) }
    var linkMenu by remember { mutableStateOf<LinkMenu?>(null) }
    var fullscreen by remember { mutableStateOf<Fullscreen?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var chooser by remember { mutableStateOf<Pair<FileChooser, ValueCallback<Array<Uri>>>?>(null) }
    val pendingBlobs = remember { mutableSetOf<String>() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        chooser?.let { (c, cb) -> cb.onReceiveValue(c.result(result.resultCode == Activity.RESULT_OK, result.data)) }
        chooser = null
    }

    fun notify(message: String) = scope.launch { snackbar.showSnackbar(message) }

    fun saved(file: SavedFile) = scope.launch {
        val r = snackbar.showSnackbar(context.getString(R.string.web_downloaded, file.name), context.getString(R.string.web_open_file))
        if (r == SnackbarResult.ActionPerformed) Downloads.open(context, file)
    }

    fun download(target: String, userAgent: String, disposition: String?, mime: String?) {
        val view = webView ?: return
        val name = URLUtil.guessFileName(target, disposition, mime)
        when {
            target.startsWith("blob:") -> {
                val token = UUID.randomUUID().toString()
                pendingBlobs += token
                view.evaluateJavascript(blobDownloadScript(target, token, name, mime.orEmpty()), null)
            }
            target.startsWith("data:") -> scope.launch {
                runCatching { Downloads.saveDataUrl(context, target, name, mime) }
                    .onSuccess(::saved).onFailure { notify(context.getString(R.string.web_download_failed, it.message)) }
            }
            else -> scope.launch {
                notify(context.getString(R.string.web_downloading, name))
                runCatching { Downloads.fetch(context, target, userAgent, disposition, mime, view.url, trustedHosts) }
                    .onSuccess(::saved).onFailure { notify(context.getString(R.string.web_download_failed, it.message)) }
            }
        }
    }

    fun openExternal(uri: Uri) {
        when (val r = context.openExternal(uri)) {
            ExternalResult.Opened -> Unit
            is ExternalResult.Fallback -> webView?.loadUrl(r.url)
            ExternalResult.NoApp -> notify(context.getString(R.string.web_no_app))
        }
    }

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }

    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
    BackHandler(enabled = canGoBack && fullscreen == null) { webView?.goBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(pageTitle.ifEmpty { title }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    subtitle = { Text(pageUrl, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(shapes = IconButtonDefaults.shapes(), onClick = onBack) { Icon(Icons.Default.Close, stringResource(R.string.back)) }
                    },
                    actions = {
                        AnimatedVisibility(canGoBack, enter = fadeIn(), exit = fadeOut()) {
                            IconButton(shapes = IconButtonDefaults.shapes(), onClick = { webView?.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                        }
                        IconButton(shapes = IconButtonDefaults.shapes(), onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.reload)) }
                        Box {
                            IconButton(shapes = IconButtonDefaults.shapes(), onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_in_browser)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                                    onClick = { menu = false; context.openInBrowser(pageUrl) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.web_copy_link)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = { menu = false; context.copy(pageUrl) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.web_share)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                    onClick = { menu = false; share(pageUrl) },
                                )
                            }
                        }
                    },
                )
                AnimatedVisibility(progress in 1..99, enter = fadeIn(), exit = fadeOut()) {
                    LinearWavyProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        // Edge-to-edge windows don't resize for the IME, so the WebView must be
        // inset explicitly or its viewport stays shrunk after the keyboard hides.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            startUrl?.onFailure { Text(it.message ?: "", Modifier.padding(24.dp)) }
            startUrl?.onSuccess { start ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            // window.open/target=_blank load in this view, like a single-tab browser.
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(false)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // Some apps serve stripped-down pages to the "; wv" WebView UA.
                            settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            addJavascriptInterface(object {
                                // Only answers downloads this screen started, so pages can't write files on their own.
                                @JavascriptInterface
                                fun save(token: String, dataUrl: String, name: String, mime: String) {
                                    if (!pendingBlobs.remove(token)) return
                                    scope.launch {
                                        runCatching { Downloads.saveDataUrl(ctx, dataUrl, name, mime) }
                                            .onSuccess(::saved).onFailure { notify(ctx.getString(R.string.web_download_failed, it.message)) }
                                    }
                                }

                                @JavascriptInterface
                                fun fail(token: String, message: String) {
                                    if (pendingBlobs.remove(token)) notify(ctx.getString(R.string.web_download_failed, message))
                                }
                            }, "TaildeckDownload")

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                    pageUrl = url
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme?.lowercase() ?: return false
                                    if (scheme in WEB_SCHEMES) return false
                                    if (scheme == "file" || scheme == "content") return true
                                    // Like Chrome: user taps open apps directly, redirects ask first.
                                    if (request.hasGesture()) openExternal(request.url) else externalPrompt = ExternalPrompt(request.url)
                                    return true
                                }

                                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                                    authPrompt = AuthPrompt(host, handler)
                                }

                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    val host = runCatching { java.net.URI(error.url).host }.getOrNull() ?: error.url
                                    if (host in trustedHosts) handler.proceed() else sslPrompt = SslPrompt(host, handler)
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
                                    chooser?.second?.onReceiveValue(null)
                                    val c = FileChooser(ctx, params)
                                    chooser = c to callback
                                    return runCatching { filePicker.launch(c.intent) }
                                        .onFailure { chooser = null; callback.onReceiveValue(null) }
                                        .isSuccess
                                }

                                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                    fullscreen?.callback?.onCustomViewHidden()
                                    fullscreen = Fullscreen(view, callback)
                                }

                                override fun onHideCustomView() {
                                    fullscreen = null
                                }
                            }
                            setDownloadListener { target, userAgent, disposition, mime, _ -> download(target, userAgent, disposition, mime) }
                            setOnLongClickListener {
                                val hit = (it as WebView).hitTestResult
                                when (hit.type) {
                                    WebView.HitTestResult.SRC_ANCHOR_TYPE -> { linkMenu = LinkMenu(hit.extra, null); true }
                                    WebView.HitTestResult.IMAGE_TYPE,
                                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> { linkMenu = LinkMenu(null, hit.extra); true }
                                    else -> false
                                }
                            }
                            loadUrl(start)
                            webView = this
                        }
                    },
                )
            }
        }
    }

    fullscreen?.let { fs ->
        Dialog(
            onDismissRequest = { fs.callback.onCustomViewHidden(); fullscreen = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            AndroidView(
                factory = { fs.view.apply { (parent as? ViewGroup)?.removeView(this); setBackgroundColor(Color.BLACK) } },
                modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
            )
        }
    }

    linkMenu?.let { m ->
        val target = m.link ?: m.image.orEmpty()
        AlertDialog(
            onDismissRequest = { linkMenu = null },
            title = { Text(target, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    val itemColors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    if (m.image != null) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.web_save_image)) },
                            leadingContent = { Icon(Icons.Outlined.Download, null) },
                            colors = itemColors,
                            modifier = Modifier.clickable {
                                linkMenu = null
                                download(m.image, webView?.settings?.userAgentString.orEmpty(), null, null)
                            },
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.web_copy_link)) },
                        leadingContent = { Icon(Icons.Outlined.ContentCopy, null) },
                        colors = itemColors,
                        modifier = Modifier.clickable { linkMenu = null; context.copy(target) },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.open_in_browser)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                        colors = itemColors,
                        modifier = Modifier.clickable { linkMenu = null; context.openInBrowser(target) },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.web_share)) },
                        leadingContent = { Icon(Icons.Outlined.Share, null) },
                        colors = itemColors,
                        modifier = Modifier.clickable { linkMenu = null; share(target) },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { linkMenu = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    externalPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { externalPrompt = null },
            title = { Text(stringResource(R.string.web_open_app_title)) },
            text = { Text(p.uri.toString(), maxLines = 4, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { externalPrompt = null; openExternal(p.uri) }) { Text(stringResource(R.string.web_open)) } },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { externalPrompt = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    sslPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { p.handler.cancel(); sslPrompt = null },
            title = { Text(stringResource(R.string.web_cert_title)) },
            text = { Text(stringResource(R.string.web_cert_body, p.host)) },
            confirmButton = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { trustedHosts += p.host; p.handler.proceed(); sslPrompt = null }) {
                    Text(stringResource(R.string.web_continue))
                }
            },
            dismissButton = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { p.handler.cancel(); sslPrompt = null }) { Text(stringResource(R.string.cancel)) }
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
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { p.handler.proceed(user, pass); authPrompt = null }) { Text(stringResource(R.string.sign_in)) }
            },
            dismissButton = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { p.handler.cancel(); authPrompt = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
