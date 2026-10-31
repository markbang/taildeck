package top.bangwu.taildeck.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import top.bangwu.taildeck.Node
import top.bangwu.taildeck.Phase
import top.bangwu.taildeck.R
import top.bangwu.taildeck.Service
import top.bangwu.taildeck.TaildeckApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNode: (String) -> Unit, onOpenInApp: (Service) -> Unit, onSettings: () -> Unit) {
    val app = TaildeckApp.instance
    val state by app.tailscale.state.collectAsStateWithLifecycle()
    val services by app.store.services.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val status = state.status
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(status?.tailnet ?: stringResource(R.string.app_name))
                            if (status != null) {
                                val online = status.peers.count { it.online }
                                Text(
                                    stringResource(R.string.n_online, online, status.peers.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
                    },
                )
                if (state.phase != Phase.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.Dns, null) },
                    label = { Text(stringResource(R.string.tab_nodes)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.Widgets, null) },
                    label = { Text(stringResource(R.string.tab_services)) },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> NodesTab(padding, status?.self, status?.peers.orEmpty(), state.phase == Phase.RUNNING, services, onNode)
            else -> ServicesTab(padding, services, status?.peers.orEmpty(), onOpenInApp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodesTab(
    padding: PaddingValues,
    self: Node?,
    peers: List<Node>,
    running: Boolean,
    services: List<Service>,
    onNode: (String) -> Unit,
) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var refreshing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            app.tailscale.refreshNow()
            delay(600)
            refreshing = false
        }
    }
    val filtered = peers.filter {
        query.isBlank() || it.name.contains(query, true) || it.ips.any { ip -> ip.startsWith(query) } ||
            it.tags.any { t -> t.contains(query, true) } || it.os.contains(query, true)
    }

    PullToRefreshBox(refreshing, onRefresh = { refreshing = true }, modifier = Modifier.padding(padding).fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_nodes)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (self != null && query.isBlank()) {
                item {
                    ListItem(
                        overlineContent = { Text(stringResource(R.string.this_device)) },
                        headlineContent = { Text(self.name) },
                        supportingContent = { Text(self.ips.joinToString("  "), fontFamily = FontFamily.Monospace) },
                        leadingContent = { StatusDot(running) },
                        modifier = Modifier.clickable { self.ipv4?.let(context::copy) },
                    )
                    HorizontalDivider()
                }
            }
            if (!running && peers.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.starting))
                    }
                }
            } else if (peers.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_nodes),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { it.id }) { node ->
                NodeRow(node, services.count { it.node == node.name }) { onNode(node.name) }
            }
        }
    }
}

@Composable
private fun NodeRow(node: Node, serviceCount: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = buildList {
                add(osLabel(node.os))
                node.ipv4?.let(::add)
                if (!node.online) relativeTime(node.lastSeen)?.let { add(stringResource(R.string.last_seen, it)) }
                if (node.tags.isNotEmpty()) add(node.tags.joinToString(" ") { it.removePrefix("tag:") })
            }
            Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { StatusDot(node.online) },
        trailingContent = {
            if (serviceCount > 0) {
                Text("$serviceCount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ServicesTab(padding: PaddingValues, services: List<Service>, peers: List<Node>, onOpenInApp: (Service) -> Unit) {
    val context = LocalContext.current
    if (services.isEmpty()) {
        Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.no_services_all), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val online = peers.associate { it.name to it.online }
    LazyColumn(Modifier.padding(padding).fillMaxSize()) {
        services.groupBy { it.node }.toSortedMap().forEach { (node, list) ->
            item(key = "h:$node") {
                Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(online[node] == true)
                    Spacer(Modifier.width(8.dp))
                    Text(node, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            items(list, key = { it.id }) { svc ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(svc.name) },
                    supportingContent = { Text(svc.url, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_in_app)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                                onClick = { menu = false; onOpenInApp(svc) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_link)) },
                                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                                onClick = { menu = false; context.copyBrowserLink(svc.url) },
                            )
                        }
                    },
                    modifier = Modifier.clickable { context.openInBrowser(svc.url) },
                )
            }
        }
    }
}
