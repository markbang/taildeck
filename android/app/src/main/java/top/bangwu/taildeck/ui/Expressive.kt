package top.bangwu.taildeck.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import top.bangwu.taildeck.AgentStatus
import top.bangwu.taildeck.Node

/** Page and grouped-item surfaces: items sit on the brighter surface so groups read as containers. */
object Surfaces {
    val page: Color @Composable get() = MaterialTheme.colorScheme.run { if (surface.luminance() < 0.5f) surface else surfaceContainer }
    val item: Color @Composable get() = MaterialTheme.colorScheme.run { if (surface.luminance() < 0.5f) surfaceContainerHigh else surfaceBright }

    @Composable
    fun bar(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(containerColor = page, scrolledContainerColor = page)

    @Composable
    fun itemColors(container: Color = item): ListItemColors = ListItemDefaults.segmentedColors(containerColor = container)
}

/**
 * A MaterialShapes polygon filled with [container]. Changing [polygon] morphs to it;
 * [spin] rotates it slowly, used for things in progress.
 */
@Composable
fun ShapeBadge(
    polygon: RoundedPolygon,
    container: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    spin: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    var from by remember { mutableStateOf(polygon) }
    var to by remember { mutableStateOf(polygon) }
    val progress = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(polygon) {
        if (polygon !== to) {
            from = to
            to = polygon
            progress.snapTo(0f)
            progress.animateTo(1f, spec)
        }
    }
    val morph = remember(from, to) { Morph(from, to) }
    val color by animateColorAsState(container, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "badge")
    // Read in the layer block so spinning redraws without recomposing.
    val angle = if (spin) {
        rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "a")
    } else null
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { rotationZ = angle?.value ?: 0f }
                .drawWithCache {
                    val path = Path()
                    val scale = Matrix().apply { scale(size.toPx(), size.toPx()) }
                    onDrawBehind {
                        path.rewind()
                        morph.toPath(progress.value, path)
                        path.transform(scale)
                        drawPath(path, color)
                    }
                },
        )
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides contentColorFor(container)) { content() }
    }
}

@Composable
fun NodeAvatar(node: Node, size: Dp = 40.dp) {
    val c = MaterialTheme.colorScheme
    val os = node.os.lowercase()
    val (shape, icon) = when {
        os == "android" -> MaterialShapes.Cookie7Sided to Icons.Outlined.PhoneAndroid
        os == "ios" -> MaterialShapes.Cookie6Sided to Icons.Outlined.PhoneIphone
        os == "macos" -> MaterialShapes.Clover4Leaf to Icons.Outlined.LaptopMac
        os == "windows" -> MaterialShapes.Cookie4Sided to Icons.Outlined.DesktopWindows
        os == "linux" -> MaterialShapes.Cookie9Sided to Icons.Outlined.Dns
        else -> MaterialShapes.Circle to Icons.Outlined.Computer
    }
    ShapeBadge(shape, if (node.online) c.primaryContainer else c.surfaceContainerHighest, size = size) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = if (node.online) c.onPrimaryContainer else c.onSurfaceVariant)
    }
}

private fun statusShape(status: AgentStatus): RoundedPolygon = when (status) {
    AgentStatus.WORKING -> MaterialShapes.Cookie9Sided
    AgentStatus.BLOCKED -> MaterialShapes.SoftBurst
    AgentStatus.DONE -> MaterialShapes.Clover4Leaf
    AgentStatus.IDLE, AgentStatus.UNKNOWN -> MaterialShapes.Circle
}

/** Agent identity: its harness initial inside a shape that morphs with its status. */
@Composable
fun AgentAvatar(kind: String, status: AgentStatus, size: Dp = 40.dp) {
    val c = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        AgentStatus.WORKING -> c.primaryContainer to c.onPrimaryContainer
        AgentStatus.BLOCKED -> c.tertiaryContainer to c.onTertiaryContainer
        AgentStatus.DONE -> c.secondaryContainer to c.onSecondaryContainer
        else -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
    val letter = when (kind) {
        "pi" -> "π"
        else -> kind.firstOrNull()?.uppercase() ?: "?"
    }
    ShapeBadge(statusShape(status), container, size = size, spin = status == AgentStatus.WORKING) {
        Text(letter, style = if (size >= 40.dp) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.labelLargeEmphasized, color = content)
    }
}

/** A rounded group label, set in from the group's edge. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
        trailing()
    }
}

/** Items laid out as one connected group: rounded ends, square joins, a small gap between. */
fun <T> LazyListScope.segmentedItems(
    items: List<T>,
    key: (T) -> Any,
    content: @Composable LazyItemScope.(item: T, shapes: ListItemShapes) -> Unit,
) {
    itemsIndexed(items, key = { _, it -> key(it) }) { i, item ->
        Box(Modifier.animateItem().padding(horizontal = 16.dp).padding(bottom = if (i < items.lastIndex) ListItemDefaults.SegmentedGap else 0.dp)) {
            content(item, ListItemDefaults.segmentedShapes(i, items.size))
        }
    }
}

typealias Segment = @Composable (ListItemShapes) -> Unit

/** Non-lazy version of [segmentedItems]. */
@Composable
fun SegmentedGroup(segments: List<Segment>, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        segments.forEachIndexed { i, s -> s(ListItemDefaults.segmentedShapes(i, segments.size)) }
    }
}

/** A dropdown laid out as expressive menu groups; each lambda in [groups] is one group. */
@Composable
fun GroupMenu(expanded: Boolean, onDismissRequest: () -> Unit, vararg groups: @Composable ColumnScope.() -> Unit) {
    DropdownMenuPopup(expanded, onDismissRequest) {
        groups.forEachIndexed { i, g ->
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(i, groups.size)) { g() }
            if (i < groups.lastIndex) androidx.compose.foundation.layout.Spacer(Modifier.size(MenuDefaults.GroupSpacing))
        }
    }
}

@Composable
fun GroupMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    GroupMenu(expanded, onDismissRequest, groups = arrayOf(content))

@Composable
fun MenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) = DropdownMenuItem(
    onClick = onClick,
    text = text,
    shape = MenuDefaults.itemShapes().shape,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    enabled = enabled,
)
