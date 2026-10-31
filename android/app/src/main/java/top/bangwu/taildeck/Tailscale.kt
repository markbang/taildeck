package top.bangwu.taildeck

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.bangwu.taildeck.tsbridge.Platform
import top.bangwu.taildeck.tsbridge.StateListener
import top.bangwu.taildeck.tsbridge.Tsbridge
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume

enum class Phase { STOPPED, STARTING, NEEDS_LOGIN, RUNNING, ERROR }

data class EngineState(
    val phase: Phase = Phase.STOPPED,
    val authUrl: String? = null,
    val error: String? = null,
    val status: TsStatus? = null,
)

class Tailscale(private val context: Context, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state
    private var poller: Job? = null
    private var proxyPort = 0L
    private val stateDir = File(context.filesDir, "tailscale").apply { mkdirs() }
    val keyPath: String = File(context.filesDir, "id_ed25519").absolutePath

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val platform = object : Platform {
        override fun interfaces(): String = interfacesJson()
        override fun defaultInterface(): String =
            connectivity.getLinkProperties(connectivity.activeNetwork)?.interfaceName ?: ""

        override fun lookup(host: String): String = try {
            InetAddress.getAllByName(host).joinToString(",") { it.hostAddress ?: "" }
        } catch (e: Exception) {
            ""
        }
    }

    private val listener = object : StateListener {
        override fun onState(state: String, authURL: String) {
            val phase = when (state) {
                "Running" -> Phase.RUNNING
                "NeedsLogin", "NeedsMachineAuth" -> Phase.NEEDS_LOGIN
                "Stopped" -> Phase.STOPPED
                else -> Phase.STARTING
            }
            _state.update { it.copy(phase = phase, authUrl = authURL.ifEmpty { null }, error = null) }
            if (phase == Phase.RUNNING) {
                store.configured = true
                refreshNow()
            }
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = Tsbridge.networkChanged()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = Tsbridge.networkChanged()
            override fun onLost(network: Network) = Tsbridge.networkChanged()
        })
    }

    fun start(authKey: String = "") {
        scope.launch {
            lock.withLock {
                if (_state.value.phase in setOf(Phase.STARTING, Phase.NEEDS_LOGIN, Phase.RUNNING)) return@withLock
                _state.value = EngineState(phase = Phase.STARTING)
                val hostname = store.hostname.ifBlank { defaultHostname() }
                try {
                    Tsbridge.start(stateDir.absolutePath, hostname, store.controlUrl.trim(), authKey.trim(), platform, listener)
                    proxyPort = Tsbridge.proxyPort()
                    startPolling()
                } catch (e: Exception) {
                    Log.e(TAG, "start failed", e)
                    _state.value = EngineState(phase = Phase.ERROR, error = e.message)
                }
            }
        }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                poller?.cancel()
                Tsbridge.stop()
                _state.value = EngineState()
            }
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        lock.withLock {
            poller?.cancel()
            runCatching { Tsbridge.logout() }
            Tsbridge.stop()
            store.configured = false
            _state.value = EngineState()
        }
    }

    fun refreshNow() {
        scope.launch { refresh() }
    }

    private fun refresh() {
        try {
            val st = TsStatus.parse(Tsbridge.status())
            _state.update { it.copy(status = st, authUrl = st.authUrl ?: it.authUrl) }
        } catch (e: Exception) {
            Log.w(TAG, "status: ${e.message}")
        }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                refresh()
                delay(4000)
            }
        }
    }

    suspend fun ping(ip: String): JSONObject = withContext(Dispatchers.IO) { JSONObject(Tsbridge.ping(ip)) }

    suspend fun probe(host: String, ports: List<Int>): List<Int> = withContext(Dispatchers.IO) {
        val arr = JSONArray(Tsbridge.probe(host, ports.joinToString(",")))
        (0 until arr.length()).map { arr.getInt(it) }.sorted()
    }

    suspend fun publicKey(): String = withContext(Dispatchers.IO) { Tsbridge.ensureKey(keyPath, "taildeck") }

    fun forgetHostKeys() = Tsbridge.forgetHostKeys(stateDir.absolutePath)

    fun logs(): String = Tsbridge.logs()

    private var appliedProxyPort = 0L

    // Routes every WebView request through the in-process tailnet proxy.
    suspend fun ensureWebProxy(): Boolean {
        val port = proxyPort
        if (port == 0L || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        if (appliedProxyPort == port) return true
        val config = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                ProxyController.getInstance().setProxyOverride(config, { it.run() }) { cont.resume(Unit) }
            }
        }
        appliedProxyPort = port
        return true
    }

    private fun interfacesJson(): String {
        val arr = JSONArray()
        val list = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (nif in list) {
            val addrs = JSONArray()
            nif.interfaceAddresses.forEach { ia ->
                val host = ia.address?.hostAddress?.substringBefore('%') ?: return@forEach
                addrs.put("$host/${ia.networkPrefixLength}")
            }
            arr.put(
                JSONObject()
                    .put("name", nif.name)
                    .put("index", nif.index)
                    .put("mtu", runCatching { nif.mtu }.getOrDefault(1500))
                    .put("up", runCatching { nif.isUp }.getOrDefault(false))
                    .put("loopback", runCatching { nif.isLoopback }.getOrDefault(false))
                    .put("addrs", addrs),
            )
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "Taildeck"

        fun defaultHostname(): String =
            "taildeck-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
    }
}
