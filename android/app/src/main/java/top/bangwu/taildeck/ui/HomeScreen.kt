package top.bangwu.taildeck.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Badge
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        containerColor = Surfaces.page,
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    scrollBehavior = bar,
                    colors = Surfaces.bar(),
                    title = { Text(status?.tailnet ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    subtitle = {
                        if (status != null) Text(stringResource(R.string.n_online, status.peers.count { it.online }, status.peers.size))
                    },
                    actions = {
                        IconButton(shapes = IconButtonDefaults.shapes(), onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
                    },
                )
                AnimatedVisibility(state.phase != Phase.RUNNING, enter = fadeIn(), exit = fadeOut()) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            ShortNavigationBar(containerColor = Surfaces.page) {
                ShortNavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.Dns, null) },
                    label = { Text(stringResource(R.string.tab_nodes)) },
                )
                ShortNavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.Widgets, null) },
                    label = { Text(stringResource(R.string.tab_services)) },
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(Motion.slow()) { dir * it / 8 } + fadeIn(Motion.fade()))
                    .togetherWith(slideOutHorizontally(Motion.fast()) { -dir * it / 8 } + fadeOut(Motion.fadeFast()))
            },
            label = "tab",
        ) { current ->
            when (current) {
                0 -> NodesTab(padding, status?.self, status?.peers.orEmpty(), state.phase == Phase.RUNNING, services, onNode)
                else -> ServicesTab(padding, services, status?.peers.orEmpty(), onOpenInApp)
            }
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

    RefreshBox(refreshing, onRefresh = { refreshing = true }, modifier = Modifier.padding(padding).fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_nodes)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Surfaces.item,
                        unfocusedContainerColor = Surfaces.item,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (self != null && query.isBlank()) {
                item { SelfCard(self, running) { self.ipv4?.let(context::copy) } }
            }
            if (!running && peers.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                        LoadingIndicator(Modifier.size(32.dp))
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
            if (filtered.isNotEmpty()) item(key = "nodes") { GroupHeader(stringResource(R.string.tab_nodes)) }
            segmentedItems(filtered, key = { it.id }) { node, shapes ->
                NodeRow(node, services.count { it.node == node.name }, shapes) { onNode(node.name) }
            }
        }
    }
}

/** This device, the one hero card on the home screen. */
@Composable
private fun SelfCard(self: Node, running: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = c.primaryContainer,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeBadge(MaterialShapes.Cookie12Sided, c.primary, size = 56.dp, spin = !running) {
                Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(28.dp), tint = c.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.this_device), style = MaterialTheme.typography.labelLarge, color = c.onPrimaryContainer.copy(alpha = 0.75f))
                Text(self.name, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(self.ips.joinToString("  "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2)
            }
        }
    }
}

@Composable
private fun NodeRow(node: Node, serviceCount: Int, shapes: ListItemShapes, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = Surfaces.itemColors(),
        content = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = buildList {
                add(osLabel(node.os))
                node.ipv4?.let(::add)
                if (!node.online) relativeTime(node.lastSeen)?.let { add(stringResource(R.string.last_seen, it)) }
                if (node.tags.isNotEmpty()) add(node.tags.joinToString(" ") { it.removePrefix("tag:") })
            }
            Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { NodeAvatar(node) },
        trailingContent = {
            if (serviceCount > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Text("$serviceCount", style = MaterialTheme.typography.labelMediumEmphasized)
                }
            }
        },
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
    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        services.groupBy { it.node }.toSortedMap().forEach { (node, list) ->
            item(key = "h:$node") {
                GroupHeader(node, Modifier.animateItem()) {
                    Spacer(Modifier.width(8.dp))
                    StatusDot(online[node] == true)
                }
            }
            segmentedItems(list, key = { it.id }) { svc, shapes ->
                var menu by remember { mutableStateOf(false) }
                SegmentedListItem(
                    onClick = { context.openService(svc.url, svc.name) { _, _ -> onOpenInApp(svc) } },
                    shapes = shapes,
                    colors = Surfaces.itemColors(),
                    content = { Text(svc.name) },
                    supportingContent = { Text(svc.url, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(shapes = IconButtonDefaults.shapes(), onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                        GroupMenu(menu, onDismissRequest = { menu = false }) {
                            MenuItem(
                                text = { Text(stringResource(R.string.open_in_app)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                                onClick = { menu = false; onOpenInApp(svc) },
                            )
                            MenuItem(
                                text = { Text(stringResource(R.string.open_in_browser)) },
                                leadingIcon = { Icon(Icons.Outlined.Language, null) },
                                onClick = { menu = false; context.openInBrowser(svc.url) },
                            )
                            MenuItem(
                                text = { Text(stringResource(R.string.copy_link)) },
                                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                                onClick = { menu = false; context.copyBrowserLink(svc.url) },
                            )
                        }
                    },
                )
            }
        }
    }
}
