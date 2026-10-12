package dev.dreamz.droidterm.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamz.droidterm.data.AppDb
import dev.dreamz.droidterm.data.SecretBox
import dev.dreamz.droidterm.data.SshHostEntity
import dev.dreamz.droidterm.data.SshKeyEntity
import dev.dreamz.droidterm.ssh.SshBackend
import dev.dreamz.droidterm.ssh.SshKeys
import dev.dreamz.droidterm.ssh.SshTarget
import dev.dreamz.droidterm.term.Sessions
import dev.dreamz.droidterm.term.TabKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SshScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("SSH", "Saved hosts and keys. Secrets are encrypted with the Android Keystore.")
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Hosts") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Keys") })
        }
        if (tab == 0) HostsTab() else KeysTab()
    }
}

// ---------------- Hosts ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostsTab() {
    val dao = AppDb.db.ssh()
    val hosts by dao.hosts().collectAsState(initial = emptyList())
    val keys by dao.keys().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<SshHostEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf<SshHostEntity?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }

    fun connect(h: SshHostEntity, typedPassword: String?) {
        scope.launch {
            val target = withContext(Dispatchers.Default) {
                runCatching {
                    val key = h.keyId?.let { dao.key(it) }
                    SshTarget(
                        host = h.host, port = h.port, username = h.username,
                        password = typedPassword ?: h.passwordEnc?.let(SecretBox::decrypt),
                        keyName = key?.name,
                        privateKey = key?.let { SecretBox.decryptBytes(it.privateKeyEnc) },
                        publicKey = key?.publicKey?.toByteArray(),
                        passphrase = key?.passphraseEnc?.let(SecretBox::decrypt),
                    )
                }
            }
            target.onFailure { context.toast("Couldn't read saved secrets: ${it.message}") }
            target.onSuccess { t ->
                dao.touch(h.id, System.currentTimeMillis())
                Sessions.newStream(h.name, SshBackend(t), TabKind.SSH)
                Nav.screen = Screen.TERMINAL
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (hosts.isEmpty()) item {
                Text("No saved hosts. Tap + to add one.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(hosts, key = { it.id }) { h ->
                Box {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { if (h.keyId == null && h.passwordEnc == null) askPassword = h else connect(h, null) },
                            onLongClick = { menuFor = h.id },
                        ).padding(16.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name, fontWeight = FontWeight.Medium)
                            Text("${h.username}@${h.host}${if (h.port != 22) ":${h.port}" else ""}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val auth = when {
                            h.keyId != null -> "key: " + (keys.firstOrNull { it.id == h.keyId }?.name ?: "?")
                            h.passwordEnc != null -> "password saved"
                            else -> "asks password"
                        }
                        Text(auth, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(menuFor == h.id, { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuFor = null; editing = h })
                        DropdownMenuItem(text = { Text("Connect with password…") }, onClick = { menuFor = null; askPassword = h })
                        DropdownMenuItem(text = { Text("Forget host key") }, onClick = {
                            menuFor = null
                            scope.launch(Dispatchers.IO) { forgetHostKey(h.host, h.port) }
                            context.toast("Removed ${h.host} from known_hosts")
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; scope.launch { dao.deleteHost(h) } })
                    }
                }
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Filled.Add, "Add host") }
    }
    if (creating) HostDialog(null, keys) { creating = false }
    editing?.let { HostDialog(it, keys) { editing = null } }
    askPassword?.let { h ->
        var pw by remember { mutableStateOf("") }
        var save by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askPassword = null },
            title = { Text("Password for ${h.username}@${h.host}") },
            text = {
                Column {
                    OutlinedTextField(pw, { pw = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(save, { save = it }); Text("Remember (encrypted)") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askPassword = null
                    if (save) scope.launch { dao.updateHost(h.copy(passwordEnc = withContext(Dispatchers.Default) { SecretBox.encrypt(pw) })) }
                    connect(h, pw)
                }) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { askPassword = null }) { Text("Cancel") } },
        )
    }
}

private fun forgetHostKey(host: String, port: Int) {
    val f = dev.dreamz.droidterm.term.TermEnv.knownHosts
    if (!f.exists()) return
    val names = setOf(host, "[$host]:$port")
    f.writeText(f.readLines().filterNot { line -> line.substringBefore(' ').split(',').any { it in names } }.joinToString("\n", postfix = "\n"))
}

@Composable
private fun HostDialog(existing: SshHostEntity?, keys: List<SshKeyEntity>, onDismiss: () -> Unit) {
    val dao = AppDb.db.ssh()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(existing?.username.orEmpty()) }
    var keyId by remember { mutableStateOf(existing?.keyId) }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var keyMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New SSH host" else "Edit host") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it.trim() }, label = { Text("Host or IP") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row {
                    OutlinedTextField(user, { user = it.trim() }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(2f))
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Spacer(Modifier.size(8.dp))
                Box {
                    OutlinedButton(onClick = { keyMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Key: " + (keys.firstOrNull { it.id == keyId }?.name ?: "none (password)"))
                    }
                    DropdownMenu(keyMenu, { keyMenu = false }) {
                        DropdownMenuItem(text = { Text("None (password)") }, onClick = { keyId = null; keyMenu = false })
                        keys.forEach { k -> DropdownMenuItem(text = { Text("${k.name} (${k.type})") }, onClick = { keyId = k.id; keyMenu = false }) }
                    }
                }
                OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (existing?.passwordEnc != null) "Password (saved; type to replace)" else "Password (optional, saved encrypted)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (existing?.passwordEnc != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(clearPassword, { clearPassword = it }); Text("Forget saved password", fontSize = 13.sp)
                }
                if (keys.isEmpty()) Text("Tip: generate or import a key in the Keys tab.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank() && user.isNotBlank(), onClick = {
                scope.launch {
                    val enc = when {
                        password.isNotEmpty() -> withContext(Dispatchers.Default) { SecretBox.encrypt(password) }
                        clearPassword -> null
                        else -> existing?.passwordEnc
                    }
                    val h = SshHostEntity(
                        id = existing?.id ?: 0, name = name.trim().ifEmpty { "$user@$host" }, host = host, port = port.toIntOrNull() ?: 22,
                        username = user, keyId = keyId, passwordEnc = enc, lastUsed = existing?.lastUsed ?: 0,
                    )
                    if (existing == null) dao.insertHost(h) else dao.updateHost(h)
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Keys ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeysTab() {
    val dao = AppDb.db.ssh()
    val keys by dao.keys().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var generating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshKeyEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp)) {
            OutlinedButton(onClick = { generating = true }) { Text("Generate key") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { importing = true }) { Text("Import key") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (keys.isEmpty()) item {
                Text("No keys yet. Generate one, then add its public key to ~/.ssh/authorized_keys on your server.",
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(keys, key = { it.id }) { k ->
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { deleting = k }).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.name + if (k.passphraseEnc != null) "  🔒" else "", fontWeight = FontWeight.Medium)
                        Text(k.type, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(k.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { context.copyText("public key", k.publicKey); context.toast("Public key copied") }) {
                        Icon(Icons.Filled.ContentCopy, "Copy public key")
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKey), "Share public key"))
                    }) { Icon(Icons.Filled.Share, "Share public key") }
                }
                HorizontalDivider()
            }
            item {
                Text("Long-press a key to delete it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    }
    if (generating) GenerateKeyDialog { generating = false }
    if (importing) ImportKeyDialog { importing = false }
    deleting?.let { k ->
        ConfirmDialog("Delete key \"${k.name}\"?", "Hosts using it fall back to password login. This can't be undone.", onConfirm = {
            scope.launch { dao.detachKey(k.id); dao.deleteKey(k) }
        }, onDismiss = { deleting = null })
    }
}

private suspend fun saveKey(name: String, m: SshKeys.Material, passphrase: String?) = withContext(Dispatchers.Default) {
    AppDb.db.ssh().insertKey(
        SshKeyEntity(
            name = name, type = m.type, publicKey = m.publicKey, fingerprint = m.fingerprint,
            privateKeyEnc = SecretBox.encryptBytes(m.privateKey),
            passphraseEnc = passphrase?.takeIf { it.isNotEmpty() }?.let(SecretBox::encrypt),
        )
    )
}

@Composable
private fun GenerateKeyDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("droidterm-" + android.os.Build.MODEL.replace(' ', '-').lowercase()) }
    var type by remember { mutableStateOf(SshKeys.Type.ED25519) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Generate SSH key") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name / comment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                SshKeys.Type.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }, modifier = Modifier.padding(end = 4.dp))
                }
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (busy) Text("Generating…", color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { SshKeys.generate(type, name.trim(), passphrase.ifEmpty { null }) } }
                    r.onSuccess {
                        saveKey(name.trim(), it, passphrase)
                        context.copyText("public key", it.publicKey)
                        context.toast("Key created; public key copied")
                        onDismiss()
                    }.onFailure { busy = false; context.toast("Failed: ${it.message}") }
                }
            }) { Text("Generate") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrElse { "" }
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import private key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Paste the private key (OpenSSH / PEM / PuTTY)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp))
                Row {
                    TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Choose file") }
                    TextButton(onClick = { text = context.clipboardText().orEmpty() }) { Text("Paste") }
                }
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase (if the key has one)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.contains("PRIVATE KEY") || text.contains("PuTTY"), onClick = {
                scope.launch {
                    val n = name.trim().ifEmpty { "imported" }
                    val r = withContext(Dispatchers.Default) { runCatching { SshKeys.import(text.trim().toByteArray() + '\n'.code.toByte(), passphrase.ifEmpty { null }, n) } }
                    r.onSuccess { saveKey(n, it, passphrase); context.toast("Key imported"); onDismiss() }
                        .onFailure { error = it.message ?: "Couldn't read that key" }
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
