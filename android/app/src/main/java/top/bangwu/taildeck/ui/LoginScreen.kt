package top.bangwu.taildeck.ui

import androidx.compose.material.icons.outlined.Hub
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.bangwu.taildeck.Phase
import top.bangwu.taildeck.R
import top.bangwu.taildeck.TaildeckApp
import top.bangwu.taildeck.Tailscale

@Composable
fun LoginScreen(onReady: () -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    val state by app.tailscale.state.collectAsStateWithLifecycle()
    var hostname by rememberSaveable { mutableStateOf(app.store.hostname.ifBlank { Tailscale.defaultHostname() }) }
    var controlUrl by rememberSaveable { mutableStateOf(app.store.controlUrl) }
    var authKey by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(app.store.controlUrl.isNotBlank()) }
    var openedUrl by remember { mutableStateOf<String?>(null) }

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    LaunchedEffect(state.phase) {
        if (state.phase == Phase.RUNNING) onReady()
    }
    LaunchedEffect(state.authUrl) {
        val url = state.authUrl
        if (url != null && url != openedUrl) {
            openedUrl = url
            openUrl(url)
        }
    }

    val busy = state.phase == Phase.STARTING || state.phase == Phase.NEEDS_LOGIN
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        // Login is the app's hero moment: the mark changes shape as sign-in moves along.
        val c = MaterialTheme.colorScheme
        ShapeBadge(
            when {
                state.authUrl != null -> androidx.compose.material3.MaterialShapes.SoftBurst
                busy -> androidx.compose.material3.MaterialShapes.Cookie9Sided
                else -> androidx.compose.material3.MaterialShapes.Cookie12Sided
            },
            c.primaryContainer,
            size = 112.dp,
            spin = busy,
        ) {
            Icon(Icons.Outlined.Hub, null, Modifier.size(48.dp), tint = c.onPrimaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLargeEmphasized, color = c.primary)
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.displaySmallEmphasized)
        Text(stringResource(R.string.login_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it.lowercase().replace(Regex("[^a-z0-9-]"), "-") },
            label = { Text(stringResource(R.string.device_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(shapes = ButtonDefaults.shapes(), onClick = { advanced = !advanced }, enabled = !busy) {
            Text(stringResource(R.string.advanced))
            Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        AnimatedVisibility(advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = controlUrl,
                    onValueChange = { controlUrl = it.trim() },
                    label = { Text(stringResource(R.string.control_url)) },
                    supportingText = { Text(stringResource(R.string.control_url_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it.trim() },
                    label = { Text(stringResource(R.string.auth_key)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        val stage = when {
            !busy -> 0
            state.authUrl != null -> 2
            else -> 1
        }
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                (fadeIn(Motion.fade()) + slideInVertically(Motion.slow()) { it / 6 })
                    .togetherWith(fadeOut(Motion.fadeFast()))
                    .using(SizeTransform(clip = false))
            },
            label = "login",
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (current) {
                    0 -> Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = {
                            app.store.hostname = hostname.trim('-')
                            app.store.controlUrl = controlUrl
                            app.tailscale.start(authKey)
                        },
                        enabled = hostname.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (state.phase == Phase.ERROR) stringResource(R.string.retry) else stringResource(R.string.sign_in)) }
                    2 -> {
                        Text(stringResource(R.string.waiting_login), style = MaterialTheme.typography.bodyMedium)
                        Button(shapes = ButtonDefaults.shapes(), onClick = { state.authUrl?.let(::openUrl) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(stringResource(R.string.open_login))
                        }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.starting))
                    }
                }
                if (current != 0) {
                    OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { app.tailscale.stop() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}
