package top.bangwu.taildeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import top.bangwu.taildeck.EngineState
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
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    HomeContent(state, services, app.tailscale::refreshNow, onNode, onOpenInApp, onSettings)
}

/** Home without app singletons, so it can be rendered from plain data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: EngineState,
    services: List<Service>,
    onRefresh: () -> Unit,
    onNode: (String) -> Unit,
    onOpenInApp: (Service) -> Unit,
    onSettings: () -> Unit,
    initialTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val status = state.status
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // On wide screens the whole screen keeps a phone-to-tablet column instead of stretching rows.
    Box(Modifier.fillMaxSize().background(Surfaces.page), contentAlignment = Alignment.TopCenter) {
        Scaffold(
            modifier = Modifier.widthIn(max = 720.dp).nestedScroll(bar.nestedScrollConnection),
            containerColor = Surfaces.page,
            topBar = {
                Column {
                    LargeFlexibleTopAppBar(
                        scrollBehavior = bar,
                        colors = Surfaces.bar(),
                        title = { Text(status?.tailnet ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                        modifier = Modifier.testTag("tab-nodes"),
                    )
                    ShortNavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Outlined.Widgets, null) },
                        label = { Text(stringResource(R.string.tab_services)) },
                        modifier = Modifier.testTag("tab-services"),
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
                    0 -> NodesTab(padding, status?.self, status?.peers.orEmpty(), state.phase == Phase.RUNNING, onRefresh, onNode)
                    else -> ServicesTab(padding, services, status?.peers.orEmpty(), onOpenInApp)
                }
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
    onRefresh: () -> Unit,
    onNode: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var refreshing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            onRefresh()
            delay(600)
            refreshing = false
        }
    }
    val filtered = peers.filter {
        query.isBlank() || it.name.contains(query, true) || it.ips.any { ip -> ip.startsWith(query) } ||
            it.tags.any { t -> t.contains(query, true) } || it.os.contains(query, true)
    }
    val (online, offline) = filtered.partition { it.online }

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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("node-search"),
                )
            }
            if (self != null && query.isBlank()) {
                item(key = "links") { LinkMap(self, peers, running, onCopyIp = { self.ipv4?.let(context::copy) }, onPeer = onNode) }
            }
            if (!running && peers.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.starting))
                    }
                }
            } else if (peers.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_nodes), Modifier.padding(horizontal = 32.dp, vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Online and offline read as two groups by space alone.
            item(key = "gap-online") { Spacer(Modifier.height(8.dp)) }
            segmentedItems(online, key = { it.id }) { node, shapes -> NodeRow(node, shapes) { onNode(node.name) } }
            if (online.isNotEmpty() && offline.isNotEmpty()) item(key = "gap-offline") { Spacer(Modifier.height(16.dp)) }
            segmentedItems(offline, key = { it.id }) { node, shapes -> NodeRow(node, shapes) { onNode(node.name) } }
        }
    }
}

@Composable
private fun NodeRow(node: Node, shapes: ListItemShapes, onClick: () -> Unit) {
    val state = linkLabel(node)
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = Surfaces.itemColors(muted = !node.online),
        modifier = Modifier.testTag("node:${node.name}").semantics { stateDescription = state },
        content = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val seen = if (node.online) null else relativeTime(node.lastSeen)
            Text(listOfNotNull(node.ipv4, seen).joinToString(" · "), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { NodeAvatar(node) },
        trailingContent = { LinkMark(node.link) },
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
    val byName = peers.associateBy { it.name }
    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        services.groupBy { it.node }.toSortedMap().forEach { (nodeName, list) ->
            val node = byName[nodeName]
            item(key = "h:$nodeName") {
                GroupHeader(nodeName, Modifier.animateItem(), muted = node?.online != true) {
                    Spacer(Modifier.width(12.dp))
                    LinkMark(node?.link ?: Link.OFFLINE)
                }
            }
            segmentedItems(list, key = { it.id }) { svc, shapes ->
                var menu by remember { mutableStateOf(false) }
                SegmentedListItem(
                    onClick = { context.openService(svc.url, svc.name) { _, _ -> onOpenInApp(svc) } },
                    shapes = shapes,
                    colors = Surfaces.itemColors(muted = node?.online != true),
                    modifier = Modifier.testTag("service:${svc.id}"),
                    leadingContent = { ServiceIcon(svc) },
                    content = { Text(svc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(serviceAddress(svc.url), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(shapes = IconButtonDefaults.shapes(), onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options_for, svc.name))
                        }
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

/** `nas:5001`, plus the path when it isn't the root; the scheme and node are already implied. */
private fun serviceAddress(url: String): String = runCatching {
    val u = java.net.URI(url)
    val port = if (u.port > 0) ":${u.port}" else ""
    val path = (u.rawPath ?: "").takeIf { it.length > 1 }.orEmpty()
    "${u.host}$port$path"
}.getOrDefault(url)
