package top.bangwu.taildeck.ui

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
import androidx.compose.foundation.layout.Box
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
    Box(
        modifier
            .size(8.dp)
            .background(if (online) OnlineGreen else MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
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

/** Opens a tailnet URL in the default browser through the loopback gateway. */
fun Context.openInBrowser(tailnetUrl: String) {
    val url = runCatching { TaildeckApp.instance.tailscale.localUrl(tailnetUrl) }.getOrElse {
        Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
        return
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
