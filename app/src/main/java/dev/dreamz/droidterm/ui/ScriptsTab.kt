package dev.dreamz.droidterm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamz.droidterm.data.AppDb
import dev.dreamz.droidterm.data.ScriptEntity
import dev.dreamz.droidterm.scripts.ScriptWriter
import dev.dreamz.droidterm.scripts.Scripts
import dev.dreamz.droidterm.term.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScriptsTab() {
    val dao = AppDb.db.scripts()
    val scripts by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ScriptEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Scripts.seedIfEmpty() }

    fun run(s: ScriptEntity) {
        scope.launch {
            val cmd = withContext(Dispatchers.IO) { Scripts.prepare(s) }
            Sessions.newLocalShell(s.name.take(20))
            Sessions.type(cmd, enter = true)
            Nav.screen = Screen.TERMINAL
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tap ▶ to run in a new tab, long-press to edit. Saved under ~/scripts.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, "AI settings") }
                }
            }
            items(scripts, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { editing = s }, onLongClick = { editing = s })
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (s.needsRoot) "  · root" else "", fontWeight = FontWeight.Medium)
                        if (s.description.isNotBlank()) Text(s.description, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { run(s) }) { Icon(Icons.Filled.PlayArrow, "Run", tint = MaterialTheme.colorScheme.primary) }
                }
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, "New script")
        }
    }
    if (creating) ScriptEditor(null, onRun = ::run, onDismiss = { creating = false })
    editing?.let { ScriptEditor(it, onRun = ::run, onDismiss = { editing = null }) }
    if (settings) AiSettingsDialog { settings = false }
}

@Composable
private fun ScriptEditor(existing: ScriptEntity?, onRun: (ScriptEntity) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dao = AppDb.db.scripts()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var body by remember { mutableStateOf(existing?.body.orEmpty()) }
    var root by remember { mutableStateOf(existing?.needsRoot ?: false) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiNote by remember { mutableStateOf<String?>(null) }
    var needKey by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    suspend fun save(): ScriptEntity {
        val n = name.trim().ifEmpty { "Untitled script" }
        return if (existing == null) {
            val e = ScriptEntity(name = n, description = description.trim(), body = body, needsRoot = root)
            e.copy(id = dao.insert(e))
        } else {
            existing.copy(name = n, description = description.trim(), body = body, needsRoot = root, updatedAt = System.currentTimeMillis())
                .also { dao.update(it) }
        }
    }

    fun generate() {
        if (!ScriptWriter.hasKey(context)) { needKey = true; return }
        aiBusy = true; aiNote = null
        scope.launch {
            ScriptWriter.write(context, description, existing = body.takeIf { it.isNotBlank() })
                .onSuccess { d ->
                    body = d.script
                    if (name.isBlank() && d.name.isNotBlank()) name = d.name
                    root = d.needsRoot
                    aiNote = (if (d.notes.isNotBlank()) d.notes + "\n" else "") + "Review it before running."
                }
                .onFailure { aiNote = it.message }
            aiBusy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!aiBusy) onDismiss() },
        title = { Text(if (existing == null) "New script" else "Edit script") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(6.dp))
                OutlinedTextField(description, { description = it }, label = { Text("What should it do?") },
                    placeholder = { Text("e.g. set up a Debian chroot and mount my SD card in it") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !aiBusy && description.isNotBlank(), onClick = ::generate) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (body.isBlank()) "Write it for me" else "Rewrite with AI")
                    }
                    if (aiBusy) { Spacer(Modifier.size(10.dp)); CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                }
                aiNote?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 4.dp)) }
                OutlinedTextField(
                    body, { body = it }, label = { Text("Script (sh)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(root, { root = it }); Text("Run as root (su)", fontSize = 13.sp)
                }
                if (existing != null) TextButton(onClick = { deleting = true }) { Text("Delete script", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !aiBusy && body.isNotBlank(), onClick = {
                    scope.launch { val s = save(); onDismiss(); onRun(s) }
                }) { Text("Save & run") }
                TextButton(enabled = !aiBusy && body.isNotBlank(), onClick = { scope.launch { save(); onDismiss() } }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(enabled = !aiBusy, onClick = onDismiss) { Text("Cancel") } },
    )
    if (needKey) AiSettingsDialog { needKey = false }
    if (deleting && existing != null) ConfirmDialog("Delete \"${existing.name}\"?", "The script and its file in ~/scripts are removed.", onConfirm = {
        scope.launch { withContext(Dispatchers.IO) { Scripts.delete(existing) }; dao.delete(existing) }; onDismiss()
    }, onDismiss = { deleting = false })
}

@Composable
fun AiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    val has = remember { ScriptWriter.hasKey(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI script writer") },
        text = {
            Column {
                Text(
                    "Uses Claude through your own Anthropic API key (console.anthropic.com → API keys). The key is encrypted " +
                        "with the Android Keystore and only sent to api.anthropic.com. Each script you generate is billed to that key.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(key, { key = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (has) "Key saved (paste to replace)" else "API key (sk-ant-…)") }, modifier = Modifier.fillMaxWidth())
                if (has) TextButton(onClick = { ScriptWriter.saveKey(context, ""); context.toast("Key removed"); onDismiss() }) {
                    Text("Remove saved key", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = key.startsWith("sk-"), onClick = { ScriptWriter.saveKey(context, key); context.toast("Key saved"); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
