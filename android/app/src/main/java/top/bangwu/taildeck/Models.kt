package top.bangwu.taildeck

import org.json.JSONArray
import org.json.JSONObject

data class Node(
    val id: String,
    val name: String,
    val dnsName: String,
    val os: String,
    val ips: List<String>,
    val online: Boolean,
    val active: Boolean,
    val lastSeen: String?,
    val keyExpiry: String?,
    val tags: List<String>,
    val routes: List<String>,
    val user: String?,
    val curAddr: String?,
    val relay: String?,
    val exitNodeOption: Boolean,
    val rxBytes: Long,
    val txBytes: Long,
) {
    val ipv4: String? get() = ips.firstOrNull { !it.contains(':') } ?: ips.firstOrNull()

    companion object {
        fun parse(o: JSONObject) = Node(
            id = o.optString("id"),
            name = o.optString("name"),
            dnsName = o.optString("dnsName"),
            os = o.optString("os"),
            ips = o.optJSONArray("ips").strings(),
            online = o.optBoolean("online"),
            active = o.optBoolean("active"),
            lastSeen = o.optString("lastSeen").ifEmpty { null },
            keyExpiry = o.optString("keyExpiry").ifEmpty { null },
            tags = o.optJSONArray("tags").strings(),
            routes = o.optJSONArray("routes").strings(),
            user = o.optString("user").ifEmpty { null },
            curAddr = o.optString("curAddr").ifEmpty { null },
            relay = o.optString("relay").ifEmpty { null },
            exitNodeOption = o.optBoolean("exitNodeOption"),
            rxBytes = o.optLong("rxBytes"),
            txBytes = o.optLong("txBytes"),
        )
    }
}

data class TsStatus(
    val state: String,
    val authUrl: String?,
    val tailnet: String?,
    val magicDnsSuffix: String?,
    val health: List<String>,
    val self: Node?,
    val peers: List<Node>,
) {
    companion object {
        fun parse(json: String): TsStatus {
            val o = JSONObject(json)
            val peers = o.optJSONArray("peers") ?: JSONArray()
            return TsStatus(
                state = o.optString("state"),
                authUrl = o.optString("authUrl").ifEmpty { null },
                tailnet = o.optString("tailnet").ifEmpty { null },
                magicDnsSuffix = o.optString("magicDnsSuffix").ifEmpty { null },
                health = o.optJSONArray("health").strings(),
                self = o.optJSONObject("self")?.let(Node::parse),
                peers = (0 until peers.length()).map { Node.parse(peers.getJSONObject(it)) },
            )
        }
    }
}

data class Service(
    val id: String,
    val node: String,
    val name: String,
    val url: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("node", node).put("name", name).put("url", url)

    companion object {
        fun parse(o: JSONObject) = Service(o.getString("id"), o.getString("node"), o.getString("name"), o.getString("url"))
    }
}

enum class SshAuth { TAILSCALE, PASSWORD, KEY }

data class SshProfile(
    val user: String = "root",
    val port: Int = 22,
    val auth: SshAuth = SshAuth.TAILSCALE,
    val password: String = "",
    val keyId: String = SshIdentity.BUILTIN,
) {
    fun toJson(): JSONObject =
        JSONObject().put("user", user).put("port", port).put("auth", auth.name).put("password", password).put("keyId", keyId)

    companion object {
        fun parse(o: JSONObject) = SshProfile(
            user = o.optString("user", "root"),
            port = o.optInt("port", 22),
            auth = runCatching { SshAuth.valueOf(o.optString("auth")) }.getOrDefault(SshAuth.TAILSCALE),
            password = o.optString("password"),
            keyId = o.optString("keyId", SshIdentity.BUILTIN),
        )
    }
}

data class SshIdentity(
    val id: String,
    val name: String,
    val type: String,
    val fingerprint: String,
    val publicKey: String,
    val passphrase: String = "",
    val certKeyId: String? = null,
    val certPrincipals: List<String> = emptyList(),
    val certValidBefore: String? = null,
) {
    val hasCert: Boolean get() = certKeyId != null
    val builtin: Boolean get() = id == BUILTIN

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type).put("fingerprint", fingerprint)
        .put("publicKey", publicKey).put("passphrase", passphrase)
        .put("certKeyId", certKeyId).put("certPrincipals", JSONArray(certPrincipals)).put("certValidBefore", certValidBefore)

    companion object {
        const val BUILTIN = "builtin"

        fun parse(o: JSONObject) = SshIdentity(
            id = o.getString("id"),
            name = o.optString("name"),
            type = o.optString("type"),
            fingerprint = o.optString("fingerprint"),
            publicKey = o.optString("publicKey"),
            passphrase = o.optString("passphrase"),
            certKeyId = if (o.isNull("certKeyId")) null else o.optString("certKeyId"),
            certPrincipals = o.optJSONArray("certPrincipals").strings(),
            certValidBefore = if (o.isNull("certValidBefore")) null else o.optString("certValidBefore").ifEmpty { null },
        )

        /** Builds an identity from the JSON returned by Tsbridge.inspectKey. */
        fun fromInspect(id: String, name: String, passphrase: String, json: String): SshIdentity {
            val o = JSONObject(json)
            val cert = o.optJSONObject("cert")
            return SshIdentity(
                id = id,
                name = name,
                type = o.optString("type"),
                fingerprint = o.optString("fingerprint"),
                publicKey = o.optString("publicKey"),
                passphrase = passphrase,
                certKeyId = cert?.optString("keyId"),
                certPrincipals = cert?.optJSONArray("principals").strings(),
                certValidBefore = cert?.optString("validBefore")?.ifEmpty { null },
            )
        }
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
