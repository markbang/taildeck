package top.bangwu.taildeck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.bangwu.taildeck.Node
import top.bangwu.taildeck.R

/** How this device currently reaches a peer. */
enum class Link { DIRECT, RELAYED, IDLE, OFFLINE }

val Node.link: Link
    get() = when {
        !online -> Link.OFFLINE
        active && curAddr != null -> Link.DIRECT
        active && relay != null -> Link.RELAYED
        else -> Link.IDLE
    }

@Composable
fun linkLabel(node: Node): String = when (node.link) {
    Link.DIRECT -> stringResource(R.string.link_direct)
    Link.RELAYED -> stringResource(R.string.link_relayed, node.relay.orEmpty())
    Link.IDLE -> stringResource(R.string.idle)
    Link.OFFLINE -> stringResource(R.string.offline)
}

private val Dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

/** The link mark: solid for direct, dashed for relayed, a lone dot for idle, nothing when offline. */
@Composable
fun LinkMark(link: Link, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier.size(28.dp, 12.dp)) {
        val y = size.height / 2
        val end = Offset(size.width - 3.dp.toPx(), y)
        val w = 2.dp.toPx()
        when (link) {
            Link.DIRECT -> {
                drawLine(c.primary, Offset(0f, y), end, w, StrokeCap.Round)
                drawCircle(c.primary, 3.dp.toPx(), end)
            }
            Link.RELAYED -> {
                drawLine(c.tertiary, Offset(0f, y), end, w, StrokeCap.Round, Dash)
                drawCircle(c.tertiary, 3.dp.toPx(), end)
            }
            Link.IDLE -> drawCircle(c.outline, 3.dp.toPx(), end, style = Stroke(1.5.dp.toPx()))
            Link.OFFLINE -> Unit
        }
    }
}

/**
 * This device and the peers it is moving traffic with right now, drawn as
 * cables off one trunk: solid when direct, dashed through the DERP relay.
 */
@Composable
fun LinkMap(self: Node, peers: List<Node>, running: Boolean, onCopyIp: () -> Unit, onPeer: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val links = peers.filter { it.link == Link.DIRECT || it.link == Link.RELAYED }.sortedByDescending { it.rxBytes + it.txBytes }
    val shown = links.take(4)
    val extra = links.size - shown.size
    val rowH = 40.dp
    Surface(
        color = Surfaces.item,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("link-map"),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            val copy = stringResource(R.string.copy_ip)
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = copy, onClick = onCopyIp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShapeBadge(MaterialShapes.Cookie12Sided, c.primaryContainer, size = 48.dp, spin = !running) {
                    Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(24.dp), tint = c.onPrimaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(self.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    self.ipv4?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.onSurfaceVariant) }
                }
            }
            if (shown.isEmpty()) return@Column
            val rows = shown.size + if (extra > 0) 1 else 0
            Box(Modifier.fillMaxWidth().height(rowH * rows)) {
                // Trunk drops from the badge's center (16 + 24dp); each branch bends right into its row.
                Canvas(Modifier.matchParentSize()) {
                    val x = 40.dp.toPx()
                    val r = 12.dp.toPx()
                    val tip = 64.dp.toPx()
                    val row = rowH.toPx()
                    val w = 2.dp.toPx()
                    val lastY = row * (rows - 1) + row / 2
                    // Starts under the badge, across the identity row's bottom padding.
                    drawLine(c.outlineVariant, Offset(x, -12.dp.toPx()), Offset(x, lastY - r), w)
                    for (i in 0 until rows) {
                        val y = row * i + row / 2
                        val peer = shown.getOrNull(i)
                        val color = when (peer?.link) {
                            Link.DIRECT -> c.primary
                            Link.RELAYED -> c.tertiary
                            else -> c.outlineVariant
                        }
                        val branch = Path().apply {
                            moveTo(x, y - r)
                            quadraticTo(x, y, x + r, y)
                            lineTo(tip, y)
                        }
                        drawPath(branch, color, style = Stroke(w, cap = StrokeCap.Round, pathEffect = if (peer?.link == Link.RELAYED) Dash else null))
                        if (peer != null) drawCircle(color, 3.dp.toPx(), Offset(tip, y))
                    }
                }
                Column {
                    shown.forEach { peer ->
                        val label = linkLabel(peer)
                        Row(
                            Modifier.fillMaxWidth().height(rowH)
                                .clickable(role = Role.Button) { onPeer(peer.name) }
                                .clearAndSetSemantics { contentDescription = "${peer.name}, $label"; role = Role.Button }
                                .testTag("link:${peer.name}")
                                .padding(start = 76.dp, end = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(peer.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (peer.link == Link.RELAYED) {
                                Text(peer.relay.orEmpty(), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = c.tertiary)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                "↓" + formatBytes(peer.rxBytes),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = c.onSurfaceVariant,
                            )
                        }
                    }
                    if (extra > 0) {
                        Box(Modifier.height(rowH).padding(start = 76.dp), contentAlignment = Alignment.CenterStart) {
                            Text(stringResource(R.string.links_more, extra), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
