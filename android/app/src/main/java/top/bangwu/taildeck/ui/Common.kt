package top.bangwu.taildeck.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.unit.dp
import top.bangwu.taildeck.R
import top.bangwu.taildeck.TaildeckApp
import java.time.OffsetDateTime

@Composable
fun StatusDot(online: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        if (online) OnlineGreen else MaterialTheme.colorScheme.outlineVariant,
        Motion.fade(),
        label = "status",
    )
    Box(modifier.size(8.dp).background(color, CircleShape))
}

fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("taildeck", text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

fun relativeTime(iso: String?): String? {
    if (iso == null) return null
    return runCatching {
        val t = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }.getOrNull()
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    else -> "%.2f GB".format(b / 1024.0 / 1024 / 1024)
}

fun osLabel(os: String): String = when (os.lowercase()) {
    "linux" -> "Linux"
    "windows" -> "Windows"
    "macos" -> "macOS"
    "ios" -> "iOS"
    "android" -> "Android"
    "freebsd" -> "FreeBSD"
    "" -> "—"
    else -> os
}

/** Opens a URL in the default browser; tailnet URLs go through the loopback gateway. */
fun Context.openInBrowser(target: String) {
    val ts = TaildeckApp.instance.tailscale
    val host = runCatching { Uri.parse(target).host }.getOrNull().orEmpty()
    val url = if (ts.isTailnetHost(host) || !host.contains('.')) {
        runCatching { ts.localUrl(target) }.getOrElse {
            Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
            return
        }
    } else {
        target
    }
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}

fun Context.copyBrowserLink(tailnetUrl: String) {
    runCatching { TaildeckApp.instance.tailscale.localUrl(tailnetUrl) }.onSuccess(::copy)
}

/** Opens a service the way the user chose in settings. */
fun Context.openService(url: String, title: String, openInApp: (String, String) -> Unit) {
    if (TaildeckApp.instance.store.openInApp) openInApp(url, title) else openInBrowser(url)
}

/** Pull to refresh with the expressive loading indicator. */
@Composable
fun RefreshBox(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        refreshing, onRefresh, modifier, state = state,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(
                state, refreshing, Modifier.align(androidx.compose.ui.Alignment.TopCenter),
            )
        },
        content = content,
    )
}

/** Single choice as a connected button group; long option lists scroll instead of squeezing. */
@Composable
fun <T> ChoiceGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (T) -> Unit,
) {
    val scroll = options.size > 3
    androidx.compose.foundation.layout.Row(
        modifier.then(
            if (scroll) Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
            else Modifier.fillMaxWidth(),
        ),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, option ->
            androidx.compose.material3.ToggleButton(
                checked = option == selected,
                onCheckedChange = { if (option != selected) onSelect(option) },
                enabled = enabled,
                shapes = when (i) {
                    0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = if (scroll) Modifier else Modifier.weight(1f),
            ) { label(option) }
        }
    }
}
