package top.bangwu.taildeck.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.bangwu.taildeck.R
import top.bangwu.taildeck.SshIdentity
import top.bangwu.taildeck.SshKeys
import top.bangwu.taildeck.TaildeckApp

private const val MAX_KEY_FILE = 64 * 1024

private fun Context.readSmallText(uri: Uri): String? {
    contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= MAX_KEY_FILE) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        if (bytes.size > MAX_KEY_FILE) {
            Toast.makeText(this, R.string.file_too_large, Toast.LENGTH_SHORT).show()
            return null
        }
        return bytes.toString(Charsets.UTF_8)
    }
    return null
}

private fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

@Composable
fun KeyImportDialog(onDismiss: () -> Unit, onImported: (SshIdentity) -> Unit) {
    val keys = TaildeckApp.instance.keys
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var cert by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var needsPassphrase by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.readSmallText(uri)?.let { key = it.trim() }
        if (name.isBlank()) name = context.displayName(uri).orEmpty()
    }
    val pickCert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.readSmallText(uri)?.let { cert = it.trim() }
    }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_key)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.key_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FieldHeader(stringResource(R.string.private_key)) { pickKey.launch(arrayOf("*/*")) }
                OutlinedTextField(
                    key,
                    { key = it; error = null },
                    placeholder = { Text(stringResource(R.string.private_key_hint)) },
                    textStyle = mono,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 160.dp),
                )
                OutlinedTextField(
                    passphrase,
                    { passphrase = it; error = null },
                    label = { Text(stringResource(R.string.passphrase)) },
                    singleLine = true,
                    isError = needsPassphrase && passphrase.isEmpty(),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldHeader(stringResource(R.string.certificate)) { pickCert.launch(arrayOf("*/*")) }
                OutlinedTextField(
                    cert,
                    { cert = it; error = null },
                    placeholder = { Text(stringResource(R.string.certificate_hint)) },
                    textStyle = mono,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 120.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { keys.import(name.trim(), key, passphrase, cert) }
                            .onSuccess(onImported)
                            .onFailure {
                                needsPassphrase = SshKeys.isPassphraseError(it)
                                error = if (needsPassphrase) context.getString(R.string.passphrase_required) else it.message
                            }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FieldHeader(label: String, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onPick) {
            Icon(Icons.Outlined.FolderOpen, null)
            Text(" " + stringResource(R.string.pick_file))
        }
    }
}

@Composable
fun identitySubtitle(id: SshIdentity): String = buildList {
    add(id.type.removePrefix("ssh-"))
    add(id.fingerprint.removePrefix("SHA256:").take(16) + "…")
    if (id.hasCert) {
        add(stringResource(R.string.cert))
        if (id.certPrincipals.isNotEmpty()) add(stringResource(R.string.cert_principals, id.certPrincipals.joinToString(",")))
        id.certValidBefore?.let { add(stringResource(R.string.cert_valid_until, it.substringBefore('T'))) }
    }
}.joinToString(" · ")
