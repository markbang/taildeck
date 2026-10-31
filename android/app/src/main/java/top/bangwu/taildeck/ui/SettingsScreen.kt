package top.bangwu.taildeck.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VpnKeyOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.bangwu.taildeck.BuildConfig
import top.bangwu.taildeck.R
import top.bangwu.taildeck.SshIdentity
import top.bangwu.taildeck.TaildeckApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogs: () -> Unit, onLoggedOut: () -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.tailscale.state.collectAsStateWithLifecycle()
    val status = state.status
    val identities by app.keys.list.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshIdentity?>(null) }
    LaunchedEffect(Unit) { runCatching { app.keys.ensureBuiltin() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel(stringResource(R.string.account))
            ListItem(
                headlineContent = { Text(status?.self?.user ?: "—") },
                supportingContent = { Text(listOfNotNull(status?.tailnet, app.store.controlUrl.ifBlank { null }).joinToString(" · ")) },
                overlineContent = { Text(stringResource(R.string.tailnet)) },
            )
            status?.self?.let { self ->
                ListItem(
                    overlineContent = { Text(stringResource(R.string.this_device)) },
                    headlineContent = { Text(self.dnsName.ifEmpty { self.name }) },
                    supportingContent = {
                        Text(
                            self.ips.joinToString("  ") + "\n" + stringResource(R.string.notif_text, app.tailscale.gatewayPort),
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    modifier = Modifier.clickable { self.ipv4?.let(context::copy) },
                )
            }
            HorizontalDivider()

            SectionLabel(stringResource(R.string.ssh_keys))
            identities.forEach { id ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    leadingContent = { Icon(if (id.hasCert) Icons.Outlined.VerifiedUser else Icons.Outlined.Key, null) },
                    headlineContent = { Text(if (id.builtin) stringResource(R.string.key_builtin) else id.name) },
                    supportingContent = { Text(identitySubtitle(id), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_public_key)) },
                                onClick = { menu = false; context.copy(id.publicKey) },
                            )
                            if (!id.builtin) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = { menu = false; deleting = id },
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { context.copy(id.publicKey) },
                )
            }
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Add, null) },
                headlineContent = { Text(stringResource(R.string.import_key)) },
                modifier = Modifier.clickable { importing = true },
            )
            ListItem(
                leadingContent = { Icon(Icons.Outlined.VpnKeyOff, null) },
                headlineContent = { Text(stringResource(R.string.forget_host_keys)) },
                modifier = Modifier.clickable {
                    app.tailscale.forgetHostKeys()
                    Toast.makeText(context, R.string.host_keys_cleared, Toast.LENGTH_SHORT).show()
                },
            )
            HorizontalDivider()

            ListItem(
                leadingContent = { Icon(Icons.Outlined.Description, null) },
                headlineContent = { Text(stringResource(R.string.logs)) },
                modifier = Modifier.clickable(onClick = onLogs),
            )
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { confirmLogout = true },
            )
            Text(
                stringResource(R.string.version, BuildConfig.VERSION_NAME),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (importing) KeyImportDialog(onDismiss = { importing = false }) { importing = false }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.delete_key_confirm, id.name)) },
            confirmButton = {
                TextButton(onClick = { app.keys.delete(id.id); deleting = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        app.tailscale.logout()
                        onLoggedOut()
                    }
                }) { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    var logs by remember { mutableStateOf(app.tailscale.logs()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { logs = app.tailscale.logs() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.reload)) }
                    IconButton(onClick = { context.copy(logs) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy)) }
                },
            )
        },
    ) { padding ->
        SelectionContainer(Modifier.padding(padding).fillMaxSize()) {
            Text(
                logs,
                Modifier.verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}
