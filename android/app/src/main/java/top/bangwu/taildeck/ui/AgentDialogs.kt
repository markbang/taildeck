package top.bangwu.taildeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import top.bangwu.taildeck.HerdrAgent
import top.bangwu.taildeck.R
import top.bangwu.taildeck.TaildeckApp

// herdr's supported kinds, the common ones first.
private val AGENT_KINDS = listOf(
    "claude", "codex", "pi", "opencode", "gemini", "copilot", "cursor", "amp", "omp", "qwen", "kimi", "droid",
    "grok", "cline", "kilo", "kiro", "devin", "agy", "mastracode", "hermes", "qodercli", "letta", "maki", "muse",
)

private val AGENT_NAME = Regex("^[a-z][a-z0-9_-]{0,31}$")

private fun defaultName(kind: String, taken: Set<String>): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) kind else "$kind-$it" }.first { it !in taken }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAgentDialog(node: String, agents: List<HerdrAgent>, onDismiss: () -> Unit, onStarted: (String) -> Unit) {
    val hub = TaildeckApp.instance.herdr
    val scope = rememberCoroutineScope()
    val taken = remember(agents) { agents.mapNotNull { it.name }.toSet() }
    var kind by remember { mutableStateOf("claude") }
    var name by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var workspaces by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var workspace by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(node) {
        runCatching { hub.workspaces(node) }.onSuccess { list ->
            workspaces = list
            if (workspace == null) workspace = list.firstOrNull()?.first
        }
    }
    val finalName = name.trim().ifEmpty { defaultName(kind, taken) }
    val nameError = when {
        !AGENT_NAME.matches(finalName) -> stringResource(R.string.agent_name_rule)
        finalName in taken -> stringResource(R.string.agent_name_taken)
        else -> null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.agent_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Picker(stringResource(R.string.agent_kind), kind, AGENT_KINDS.map { it to it }) { kind = it }
                OutlinedTextField(
                    name,
                    { name = it.lowercase() },
                    label = { Text(stringResource(R.string.agent_name)) },
                    placeholder = { Text(defaultName(kind, taken)) },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Picker(
                    stringResource(R.string.agent_workspace),
                    workspace,
                    workspaces + (null to stringResource(R.string.agent_new_workspace)),
                ) { workspace = it }
                OutlinedTextField(
                    cwd,
                    { cwd = it },
                    label = { Text(stringResource(R.string.agent_directory)) },
                    placeholder = { Text(stringResource(if (workspace == null) R.string.agent_directory_home else R.string.agent_directory_inherit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && nameError == null,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { hub.startAgent(node, kind, finalName, workspace, cwd) }
                            .onSuccess(onStarted)
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.agent_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: String, value: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            options.firstOrNull { it.first == value }?.second.orEmpty(),
            {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onPick(v); expanded = false })
            }
        }
    }
}

@Composable
fun RenameAgentDialog(node: String, pane: String, current: String?, onDismiss: () -> Unit) {
    val hub = TaildeckApp.instance.herdr
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(current.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = name.isEmpty() || AGENT_NAME.matches(name)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.agent_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    name,
                    { name = it.lowercase().trim() },
                    label = { Text(stringResource(R.string.agent_name)) },
                    isError = !valid,
                    supportingText = { Text(error ?: stringResource(if (valid) R.string.agent_rename_clear_hint else R.string.agent_name_rule)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { hub.rename(node, pane, name.ifEmpty { null }) }
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Full-screen editor for long prompts; shares the draft with the inline composer. */
@Composable
fun FullscreenComposer(title: String, draft: String, onDraft: (String) -> Unit, canSend: Boolean, onSend: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.CloseFullscreen, stringResource(R.string.agent_exit_fullscreen)) }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    FilledIconButton(onClick = onSend, enabled = canSend && draft.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.agent_send))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextField(
                    draft,
                    onDraft,
                    modifier = Modifier.fillMaxSize().focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.agent_message_hint)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}
