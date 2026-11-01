package top.bangwu.taildeck.ui

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.bangwu.taildeck.TaildeckApp
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Schemes the WebView loads itself; everything else belongs to another app. */
val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

sealed interface ExternalResult {
    data object Opened : ExternalResult
    data class Fallback(val url: String) : ExternalResult
    data object NoApp : ExternalResult
}

/**
 * Opens a non-web link the way Chrome does: intent: URIs are parsed and
 * sanitized (no explicit component, BROWSABLE only), falling back to
 * browser_fallback_url or the app's store page.
 */
fun Context.openExternal(uri: Uri): ExternalResult {
    val intent = if (uri.scheme == "intent") {
        runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return ExternalResult.NoApp
    } else {
        Intent(Intent.ACTION_VIEW, uri)
    }
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    try {
        startActivity(intent)
        return ExternalResult.Opened
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
    intent.getStringExtra("browser_fallback_url")?.takeIf { URLUtil.isNetworkUrl(it) }?.let { return ExternalResult.Fallback(it) }
    intent.`package`?.let { pkg ->
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ExternalResult.Opened
        } catch (_: ActivityNotFoundException) {
        }
    }
    return ExternalResult.NoApp
}

/** Builds a picker honoring accept= (MIME types or extensions), multiple and capture. */
class FileChooser(private val context: Context, params: WebChromeClient.FileChooserParams) {
    private var cameraFile: File? = null
    val intent: Intent

    init {
        val mimes = params.acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) ?: "*/*" else it }
            .distinct()
        val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
        if (mimes.size == 1 && mimes[0] != "*/*") {
            pick.type = mimes[0]
        } else {
            pick.type = "*/*"
            if (mimes.isNotEmpty() && "*/*" !in mimes) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
        }
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        val wantsImage = mimes.isEmpty() || mimes.any { it == "*/*" || it.startsWith("image/") }
        val camera = if (wantsImage) cameraIntent() else null
        intent = when {
            camera != null && params.isCaptureEnabled -> camera
            camera != null -> Intent.createChooser(pick, params.title).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            else -> Intent.createChooser(pick, params.title)
        }
    }

    private fun cameraIntent(): Intent? {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (intent.resolveActivity(context.packageManager) == null) return null
        cameraFile = file
        return intent
    }

    fun result(ok: Boolean, data: Intent?): Array<Uri>? {
        if (!ok) return null
        val clip = data?.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
        data?.data?.let { return arrayOf(it) }
        val photo = cameraFile?.takeIf { it.length() > 0 } ?: return null
        return arrayOf(FileProvider.getUriForFile(context, "${context.packageName}.files", photo))
    }
}

class SavedFile(val name: String, val uri: Uri, val mime: String)

object Downloads {
    /**
     * Downloads through the gateway proxy, since neither DownloadManager nor
     * another app can reach the tailnet. Cookies and UA match the WebView.
     */
    suspend fun fetch(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mime: String?,
        referer: String?,
        trustedHosts: Set<String>,
    ): SavedFile = withContext(Dispatchers.IO) {
        val port = TaildeckApp.instance.tailscale.gatewayPort
        val conn = URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
        if (conn is HttpsURLConnection && conn.url.host in trustedHosts) trustAll(conn)
        conn.setRequestProperty("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        referer?.let { conn.setRequestProperty("Referer", it) }
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val type = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: conn.contentType?.substringBefore(';')
            val name = URLUtil.guessFileName(url, contentDisposition ?: conn.getHeaderField("Content-Disposition"), type)
            conn.inputStream.use { save(context, name, type ?: "application/octet-stream", it) }
        } finally {
            conn.disconnect()
        }
    }

    /** Saves a data: URL (also used for blob: downloads read by page JS). */
    suspend fun saveDataUrl(context: Context, dataUrl: String, suggestedName: String?, mime: String?): SavedFile = withContext(Dispatchers.IO) {
        val header = dataUrl.substringBefore(',')
        val payload = dataUrl.substringAfter(',')
        val type = mime?.takeIf { it.isNotBlank() } ?: header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = if (header.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
        val name = suggestedName?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(null, null, type)
        save(context, name, type, bytes.inputStream())
    }

    private fun save(context: Context, name: String, mime: String, input: InputStream): SavedFile {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("cannot create $name")
            try {
                resolver.openOutputStream(uri)?.use { input.copyTo(it) } ?: throw IllegalStateException("cannot write $name")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedFile(name, uri, mime)
        }
        // Android 8-9: app-specific Downloads needs no storage permission.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(dir, name)
        file.outputStream().use { input.copyTo(it) }
        return SavedFile(name, FileProvider.getUriForFile(context, "${context.packageName}.files", file), mime)
    }

    fun open(context: Context, file: SavedFile) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, file.name)) }
    }

    // Only for hosts the user already accepted an invalid certificate for.
    private fun trustAll(conn: HttpsURLConnection) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        conn.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }.socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }
}

/** JS that reads a blob: URL in the page and hands it to the Android bridge. */
fun blobDownloadScript(blobUrl: String, token: String, name: String, mime: String): String {
    fun q(s: String) = org.json.JSONObject.quote(s)
    return """
        (async () => {
          try {
            const b = await (await fetch(${q(blobUrl)})).blob();
            const r = new FileReader();
            r.onload = () => TaildeckDownload.save(${q(token)}, r.result, ${q(name)}, b.type || ${q(mime)});
            r.readAsDataURL(b);
          } catch (e) { TaildeckDownload.fail(${q(token)}, String(e)); }
        })();
    """.trimIndent()
}
