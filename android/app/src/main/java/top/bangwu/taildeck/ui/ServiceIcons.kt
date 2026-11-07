package top.bangwu.taildeck.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.bangwu.taildeck.Service
import top.bangwu.taildeck.TaildeckApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Where a service's own icon comes from; swapped out where there is no tailnet. */
interface ServiceIcons {
    fun cached(url: String): ImageBitmap?
    suspend fun load(url: String): ImageBitmap?
}

val LocalServiceIcons = compositionLocalOf<ServiceIcons> { GatewayIcons }

/** The service's favicon, or its initial while there is none. */
@Composable
fun ServiceIcon(service: Service, modifier: Modifier = Modifier) {
    val icons = LocalServiceIcons.current
    val icon by produceState(icons.cached(service.url), service.url) { if (value == null) value = icons.load(service.url) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(40.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp, null, Modifier.padding(6.dp).size(28.dp))
        } else {
            Text(
                service.name.firstOrNull()?.uppercase() ?: "?",
                Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Fetches favicons through the loopback gateway, which already reaches tailnet
 * services (and skips their self-signed TLS). Results are kept on disk; misses
 * are remembered for the session so offline nodes aren't retried on every scroll.
 */
object GatewayIcons : ServiceIcons {
    private val memory = LruCache<String, ImageBitmap>(64)
    private val misses = ConcurrentHashMap.newKeySet<String>()

    override fun cached(url: String): ImageBitmap? = memory.get(url)

    override suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        if (url in misses) return@withContext null
        val app = TaildeckApp.instance
        val file = File(app.cacheDir, "favicons/${sha1(url)}.png")
        val bmp = runCatching {
            if (file.exists()) BitmapFactory.decodeFile(file.path)
            else fetch(app.tailscale::localUrl, url)?.also { b ->
                file.parentFile?.mkdirs()
                file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.getOrNull()
        if (bmp == null) {
            misses += url
            null
        } else bmp.asImageBitmap().also { memory.put(url, it) }
    }

    private fun fetch(local: (String) -> String, url: String): Bitmap? {
        val page = URI(local(url))
        val (finalUri, html) = get(page, local) ?: return null
        val candidates = iconLinks(String(html.second, Charsets.UTF_8)).map { finalUri.resolve(it) } + finalUri.resolve("/favicon.ico")
        for (c in candidates.distinct()) {
            val (_, res) = get(c, local) ?: continue
            if (res.first !in 200..299) continue
            val b = BitmapFactory.decodeByteArray(res.second, 0, res.second.size) ?: continue
            if (b.width >= 16) return b
        }
        return null
    }

    /** `<link rel=...icon...>` hrefs, larger and apple-touch icons first; SVGs are skipped. */
    private fun iconLinks(html: String): List<String> {
        val links = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html.take(256 shl 10)).map { it.value }
        fun attr(tag: String, name: String) =
            Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)
                ?.groupValues?.drop(2)?.firstOrNull { it.isNotEmpty() }
        return links.mapNotNull { tag ->
            val rel = attr(tag, "rel")?.lowercase() ?: return@mapNotNull null
            if ("icon" !in rel) return@mapNotNull null
            val href = attr(tag, "href")?.takeIf { !it.endsWith(".svg", true) && !it.startsWith("data:") } ?: return@mapNotNull null
            val size = attr(tag, "sizes")?.substringBefore('x')?.toIntOrNull() ?: if ("apple" in rel) 180 else 32
            href.replace("&amp;", "&") to size
        }.sortedByDescending { it.second }.map { it.first }.toList()
    }

    /**
     * A plain HTTP/1.0 GET against the gateway, routed by Host header, following
     * up to three redirects. Returns the final URI and (status, body).
     */
    private fun get(start: URI, local: (String) -> String): Pair<URI, Pair<Int, ByteArray>>? {
        var uri = start
        repeat(4) {
            val res = request(uri) ?: return null
            val location = res.headers["location"]
            if (res.status !in 300..399 || location == null) return uri to (res.status to res.body)
            val next = uri.resolve(location)
            uri = if (next.host?.endsWith(".localhost") == true) next else URI(local(next.toString()))
        }
        return null
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(uri: URI): Response? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", uri.port), 3000)
            s.soTimeout = 5000
            val path = (uri.rawPath ?: "").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            s.getOutputStream().write(
                "GET $path HTTP/1.0\r\nHost: ${uri.host}:${uri.port}\r\nUser-Agent: Mozilla/5.0 (Linux; Android) Taildeck\r\nAccept: */*\r\n\r\n".toByteArray(),
            )
            val raw = ByteArrayOutputStream()
            val buf = ByteArray(16 shl 10)
            val input = s.getInputStream()
            while (raw.size() < (1 shl 20)) {
                val n = input.read(buf)
                if (n < 0) break
                raw.write(buf, 0, n)
            }
            val bytes = raw.toByteArray()
            val split = (0..bytes.size - 4).firstOrNull { bytes[it] == '\r'.code.toByte() && bytes[it + 1] == '\n'.code.toByte() && bytes[it + 2] == '\r'.code.toByte() && bytes[it + 3] == '\n'.code.toByte() }
                ?: return@use null
            val head = String(bytes, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val status = head.first().split(' ').getOrNull(1)?.toIntOrNull() ?: return@use null
            val headers = head.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
            var body = bytes.copyOfRange(split + 4, bytes.size)
            if (headers["content-encoding"]?.contains("gzip") == true) body = GZIPInputStream(body.inputStream()).readBytes()
            Response(status, headers, body)
        }
    }.getOrNull()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
