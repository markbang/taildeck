package top.bangwu.taildeck.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.togetherWith
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.bangwu.taildeck.Node
import top.bangwu.taildeck.R
import top.bangwu.taildeck.Service
import top.bangwu.taildeck.TaildeckApp

private val COMMON_PORTS = listOf(
    80, 443, 81, 2283, 3000, 3001, 5000, 5001, 5050, 5244, 5678, 6080, 7878, 8000, 8006, 8080, 8081,
    8088, 8096, 8123, 8181, 8384, 8443, 8888, 8989, 9000, 9090, 9091, 9443, 10000, 19999, 32400,
)
private val HTTPS_PORTS = setOf(443, 5001, 8006, 8443, 9443, 10000)

private data class ServiceDraft(val id: String?, val name: String, val scheme: String, val port: String, val path: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NodeScreen(name: String, onBack: () -> Unit, onOpenInApp: (String, String) -> Unit, onSsh: (String) -> Unit, onAgents: (String) -> Unit) {
    val app = TaildeckApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.tailscale.state.collectAsStateWithLifecycle()
    val allServices by app.store.services.collectAsStateWithLifecycle()
    val node = state.status?.peers?.firstOrNull { it.name == name }
    val services = allServices.filter { it.node == name }

    var ping by remember { mutableStateOf<String?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<List<Int>?>(null) }
    var draft by remember { mutableStateOf<ServiceDraft?>(null) }
    // Probe herdr in the background with the saved SSH profile, if any.
    LaunchedEffect(name, node?.online) {
        if (node?.online == true) app.store.sshProfile(name)?.let { app.herdr.ensure(name, it) }
    }

    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        containerColor = Surfaces.page,
        topBar = {
            LargeFlexibleTopAppBar(
                scrollBehavior = bar,
                colors = Surfaces.bar(),
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = {
                    if (node != null) Text(listOf(osLabel(node.os), stringResource(if (node.online) R.string.online else R.string.offline)).joinToString(" · "))
                },
                navigationIcon = { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    Button(
                        shapes = ButtonDefaults.shapes(ButtonGroupDefaults.connectedLeadingButtonShapes().shape, ButtonGroupDefaults.connectedLeadingButtonShapes().pressedShape),
                        onClick = { onSsh(name) },
                        modifier = Modifier.weight(1.2f),
                    ) {
                        Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ssh))
                    }
                    FilledTonalButton(
                        shapes = ButtonDefaults.shapes(ButtonGroupDefaults.connectedMiddleButtonShapes().shape, ButtonGroupDefaults.connectedMiddleButtonShapes().pressedShape),
                        onClick = { context.openService("http://$name/", name, onOpenInApp) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Language, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.open_web))
                    }
                    FilledTonalButton(
                        shapes = ButtonDefaults.shapes(ButtonGroupDefaults.connectedTrailingButtonShapes().shape, ButtonGroupDefaults.connectedTrailingButtonShapes().pressedShape),
                        onClick = {
                            val ip = node?.ipv4 ?: return@FilledTonalButton
                            pinging = true
                            scope.launch {
                                ping = runCatching {
                                    val r = app.tailscale.ping(ip)
                                    val err = r.optString("error")
                                    if (err.isNotEmpty()) err else {
                                        val via = r.optString("endpoint").ifEmpty { null }?.let { context.getString(R.string.ping_via_direct) + " " + it }
                                            ?: context.getString(R.string.ping_via_derp, r.optString("derp"))
                                        context.getString(R.string.ping_result, r.optDouble("latencyMs"), via)
                                    }
                                }.getOrElse { it.message }
                                pinging = false
                            }
                        },
                        enabled = node != null && !pinging,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (pinging) LoadingIndicator(Modifier.size(20.dp))
                        else Icon(Icons.Outlined.NetworkPing, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ping))
                    }
                }
                AnimatedVisibility(ping != null, enter = expandVertically(Motion.slow()) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    AnimatedContent(ping.orEmpty(), transitionSpec = { fadeIn(Motion.fade()).togetherWith(fadeOut(Motion.fadeFast())) }, label = "ping") {
                        Text(it, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            item { HerdrSummary(name) { onAgents(name) } }

            if (node != null) item { NodeInfo(node) }

            item {
                GroupHeader(stringResource(R.string.services)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        shapes = ButtonDefaults.shapes(),
                        onClick = {
                            scanning = true
                            scanResult = null
                            scope.launch {
                                scanResult = runCatching { app.tailscale.probe(name, COMMON_PORTS) }.getOrDefault(emptyList())
                                scanning = false
                            }
                        },
                        enabled = !scanning && node?.online == true,
                    ) {
                        if (scanning) LoadingIndicator(Modifier.size(20.dp))
                        else Icon(Icons.Outlined.Radar, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (scanning) R.string.scanning else R.string.scan_ports))
                    }
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = { draft = ServiceDraft(null, "", "http", "", "/") }) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.add_service))
                    }
                }
            }

            scanResult?.let { ports ->
                item(key = "scan") {
                    val known = services.mapNotNull { portOf(it.url) }.toSet()
                    val fresh = ports.filterNot { it in known }
                    Column(Modifier.animateItem().padding(horizontal = 24.dp).animateContentSize(Motion.slow())) {
                        if (ports.isEmpty()) {
                            Text(stringResource(R.string.scan_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(stringResource(R.string.scan_found), style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fresh.forEach { port ->
                                    AssistChip(
                                        onClick = {
                                            val scheme = if (port in HTTPS_PORTS) "https" else "http"
                                            draft = ServiceDraft(null, "", scheme, port.toString(), "/")
                                        },
                                        label = { Text("$port") },
                                        leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (services.isEmpty() && scanResult == null) {
                item {
                    Text(
                        stringResource(R.string.no_services),
                        Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            segmentedItems(services, key = { it.id }) { svc, shapes ->
                ServiceRow(
                    svc,
                    shapes,
                    onOpen = { context.openService(svc.url, svc.name, onOpenInApp) },
                    onOpenInApp = { onOpenInApp(svc.url, svc.name) },
                    onOpenInBrowser = { context.openInBrowser(svc.url) },
                    onCopyLink = { context.copyBrowserLink(svc.url) },
                    onEdit = { draft = draftOf(svc) },
                    onDelete = { app.store.deleteService(svc.id) },
                )
            }
        }
    }

    draft?.let { d ->
        ServiceDialog(d, onDismiss = { draft = null }) { saved ->
            val path = saved.path.trim().let { if (it.startsWith("/")) it else "/$it" }
            val url = "${saved.scheme}://$name:${saved.port.trim()}$path"
            app.store.upsertService(name, saved.name.trim().ifEmpty { "${saved.port}" }, url, saved.id)
            draft = null
        }
    }
}

private fun portOf(url: String): Int? = runCatching { java.net.URI(url).port.takeIf { it > 0 } }.getOrNull()

private fun draftOf(svc: Service): ServiceDraft {
    val uri = runCatching { java.net.URI(svc.url) }.getOrNull()
    return ServiceDraft(
        svc.id,
        svc.name,
        uri?.scheme ?: "http",
        uri?.port?.takeIf { it > 0 }?.toString() ?: "",
        (uri?.rawPath ?: "/").ifEmpty { "/" } + (uri?.rawQuery?.let { "?$it" } ?: ""),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeInfo(node: Node) {
    val context = LocalContext.current
    val rows = mutableListOf<Segment>()
    val addresses = (listOf(node.dnsName) + node.ips).filter { it.isNotEmpty() }
    rows += { s ->
        InfoRow(stringResource(R.string.node_addresses), s, onClick = { context.copy(node.ipv4 ?: addresses.first()) }) {
            Column { addresses.forEach { addr -> Text(addr, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { context.copy(addr) }) } }
        }
    }
    node.user?.let { u -> rows += { s -> InfoRow(stringResource(R.string.node_owner), s) { Text(u) } } }
        val connection = when {
            !node.online -> relativeTime(node.lastSeen)?.let { stringResource(R.string.last_seen, it) } ?: stringResource(R.string.offline)
            node.curAddr != null -> stringResource(R.string.direct, node.curAddr)
            node.active && node.relay != null -> stringResource(R.string.relay, node.relay)
            else -> stringResource(R.string.idle)
        }
    rows += { s -> InfoRow(stringResource(R.string.node_connection), s) { Text(connection) } }
    if (node.tags.isNotEmpty() || node.exitNodeOption) {
        rows += { s ->
            InfoRow(stringResource(R.string.node_tags), s) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    node.tags.forEach { FilterChip(selected = false, onClick = {}, label = { Text(it) }) }
                    if (node.exitNodeOption) FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.exit_node_option)) })
                }
            }
        }
    }
    if (node.routes.isNotEmpty()) {
        rows += { s -> InfoRow(stringResource(R.string.node_routes), s) { Text(node.routes.joinToString("\n"), fontFamily = FontFamily.Monospace) } }
    }
    node.keyExpiry?.let { k -> rows += { s -> InfoRow(stringResource(R.string.node_key_expiry), s) { Text(k.substringBefore('T')) } } }
    if (node.rxBytes > 0 || node.txBytes > 0) {
        rows += { s -> InfoRow(stringResource(R.string.node_traffic), s) { Text("↓ ${formatBytes(node.rxBytes)}   ↑ ${formatBytes(node.txBytes)}") } }
    }
    GroupHeader(stringResource(R.string.node_details))
    SegmentedGroup(rows)
}

@Composable
private fun InfoRow(label: String, shapes: ListItemShapes, onClick: () -> Unit = {}, content: @Composable () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = Surfaces.itemColors(),
        overlineContent = { Text(label) },
        content = content,
    )
}

@Composable
private fun ServiceRow(
    svc: Service,
    shapes: ListItemShapes,
    onOpen: () -> Unit,
    onOpenInApp: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = onOpen,
        shapes = shapes,
        colors = Surfaces.itemColors(),
        content = { Text(svc.name) },
        supportingContent = { Text(svc.url, fontFamily = FontFamily.Monospace) },
        trailingContent = {
            IconButton(shapes = IconButtonDefaults.shapes(), onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
            GroupMenu(
                menu,
                { menu = false },
                {
                    MenuItem(text = { Text(stringResource(R.string.open_in_app)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, onClick = { menu = false; onOpenInApp() })
                    MenuItem(text = { Text(stringResource(R.string.open_in_browser)) }, leadingIcon = { Icon(Icons.Outlined.Language, null) }, onClick = { menu = false; onOpenInBrowser() })
                    MenuItem(text = { Text(stringResource(R.string.copy_link)) }, leadingIcon = { Icon(Icons.Outlined.Link, null) }, onClick = { menu = false; onCopyLink() })
                },
                {
                    MenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                    MenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                },
            )
        },
    )
}

@Composable
private fun ServiceDialog(initial: ServiceDraft, onDismiss: () -> Unit, onSave: (ServiceDraft) -> Unit) {
    var d by rememberSaveable(initial) { mutableStateOf(initial.name to initial.port) }
    var scheme by rememberSaveable(initial) { mutableStateOf(initial.scheme) }
    var path by rememberSaveable(initial) { mutableStateOf(initial.path) }
    val portValid = d.second.toIntOrNull()?.let { it in 1..65535 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == null) R.string.add_service else R.string.edit_service)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(d.first, { d = it to d.second }, label = { Text(stringResource(R.string.service_name)) }, singleLine = true)
                ChoiceGroup(listOf("http", "https"), scheme, onSelect = { scheme = it }) { Text(it) }
                OutlinedTextField(
                    d.second,
                    { d = d.first to it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.service_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.service_path)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = { onSave(ServiceDraft(initial.id, d.first, scheme, d.second, path)) }, enabled = portValid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
