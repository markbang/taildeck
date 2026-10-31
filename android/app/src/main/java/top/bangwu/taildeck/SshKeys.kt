package top.bangwu.taildeck

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import top.bangwu.taildeck.tsbridge.Tsbridge
import java.io.File
import java.util.UUID

class KeyPaths(val key: String, val passphrase: String, val cert: String)

// Private keys and certificates live in app-private storage; the list with
// metadata (and passphrases, if the key has one) lives in Store.
class SshKeys(context: Context, private val store: Store) {
    private val dir = File(context.filesDir, "keys").apply { mkdirs() }
    private val builtinPath = File(context.filesDir, "id_ed25519")

    private val _list = MutableStateFlow(store.identities)
    val list: StateFlow<List<SshIdentity>> = _list

    suspend fun ensureBuiltin(): SshIdentity = withContext(Dispatchers.IO) {
        synchronized(this@SshKeys) {
            _list.value.firstOrNull { it.builtin }?.let { return@withContext it }
            Tsbridge.ensureKey(builtinPath.absolutePath, "taildeck")
            val info = Tsbridge.inspectKey(builtinPath.readText(), "", "")
            val id = SshIdentity.fromInspect(SshIdentity.BUILTIN, "Taildeck", "", info)
            save(listOf(id) + _list.value)
            id
        }
    }

    /** Validates and stores a key; throws with the bridge's message on failure. */
    suspend fun import(name: String, key: String, passphrase: String, cert: String): SshIdentity = withContext(Dispatchers.IO) {
        val info = Tsbridge.inspectKey(key, passphrase, cert)
        val id = UUID.randomUUID().toString()
        val keyDir = File(dir, id).apply { mkdirs() }
        File(keyDir, "key").writeText(key.trim() + "\n")
        if (cert.isNotBlank()) File(keyDir, "cert.pub").writeText(cert.trim() + "\n")
        val identity = SshIdentity.fromInspect(id, name.ifBlank { "Key" }, passphrase, info)
        synchronized(this@SshKeys) { save(_list.value + identity) }
        identity
    }

    @Synchronized
    fun delete(id: String) {
        if (id == SshIdentity.BUILTIN) return
        File(dir, id).deleteRecursively()
        save(_list.value.filterNot { it.id == id })
    }

    fun paths(id: String): KeyPaths? {
        val identity = _list.value.firstOrNull { it.id == id } ?: return null
        if (identity.builtin) return KeyPaths(builtinPath.absolutePath, "", "")
        val keyDir = File(dir, id)
        val cert = File(keyDir, "cert.pub")
        return KeyPaths(File(keyDir, "key").absolutePath, identity.passphrase, if (cert.exists()) cert.absolutePath else "")
    }

    private fun save(list: List<SshIdentity>) {
        store.identities = list
        _list.value = list
    }

    companion object {
        fun isPassphraseError(e: Throwable) = e.message?.contains("PASSPHRASE_REQUIRED") == true
    }
}
