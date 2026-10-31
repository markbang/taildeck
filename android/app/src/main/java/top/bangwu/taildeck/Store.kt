package top.bangwu.taildeck

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// App-private SharedPreferences; passwords are only stored when the user
// explicitly asks to remember them.
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("taildeck", Context.MODE_PRIVATE)

    var configured: Boolean
        get() = prefs.getBoolean("configured", false)
        set(v) = prefs.edit().putBoolean("configured", v).apply()

    var hostname: String
        get() = prefs.getString("hostname", "") ?: ""
        set(v) = prefs.edit().putString("hostname", v).apply()

    var controlUrl: String
        get() = prefs.getString("controlUrl", "") ?: ""
        set(v) = prefs.edit().putString("controlUrl", v).apply()

    var gatewayPort: Int
        get() = prefs.getInt("gatewayPort", DEFAULT_GATEWAY_PORT)
        set(v) = prefs.edit().putInt("gatewayPort", v).apply()

    var identities: List<SshIdentity>
        get() {
            val arr = JSONArray(prefs.getString("identities", "[]"))
            return (0 until arr.length()).mapNotNull { runCatching { SshIdentity.parse(arr.getJSONObject(it)) }.getOrNull() }
        }
        set(v) = prefs.edit().putString("identities", JSONArray(v.map { it.toJson() }).toString()).apply()

    private val _services = MutableStateFlow(loadServices())
    val services: StateFlow<List<Service>> = _services

    private fun loadServices(): List<Service> {
        val arr = JSONArray(prefs.getString("services", "[]"))
        return (0 until arr.length()).mapNotNull { runCatching { Service.parse(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun saveServices(list: List<Service>) {
        prefs.edit().putString("services", JSONArray(list.map { it.toJson() }).toString()).apply()
        _services.value = list
    }

    fun upsertService(node: String, name: String, url: String, id: String? = null) {
        val list = _services.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val svc = Service(id ?: UUID.randomUUID().toString(), node, name, url)
        if (idx >= 0) list[idx] = svc else list += svc
        saveServices(list)
    }

    fun deleteService(id: String) = saveServices(_services.value.filterNot { it.id == id })

    fun sshProfile(node: String): SshProfile? =
        prefs.getString("ssh:$node", null)?.let { runCatching { SshProfile.parse(JSONObject(it)) }.getOrNull() }

    fun saveSshProfile(node: String, profile: SshProfile) =
        prefs.edit().putString("ssh:$node", profile.toJson().toString()).apply()

    companion object {
        // Fixed so gateway URLs stay bookmarkable across restarts.
        const val DEFAULT_GATEWAY_PORT = 18680
    }
}
