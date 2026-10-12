package dev.dreamz.droidterm.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamz.droidterm.adb.Fastboot
import dev.dreamz.droidterm.adb.Usb
import dev.dreamz.droidterm.adb.UsbTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** fastboot over USB OTG: device info, reboot targets, flash/erase, raw commands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FastbootScreen(target: UsbTarget, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    val lock = remember { Mutex() }
    var fb by remember { mutableStateOf<Fastboot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var partition by remember { mutableStateOf("boot") }
    var raw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val listState = rememberLazyListState()

    fun out(s: String) { log += s }

    fun run(label: String, block: suspend (Fastboot) -> Unit) {
        val f = fb ?: return
        scope.launch {
            lock.withLock {
                busy = true
                out("> $label")
                try { withContext(Dispatchers.IO) { block(f) } } catch (e: Exception) { out("error: ${e.message}") }
                busy = false; progress = null
            }
        }
    }

    fun cmd(c: String, label: String = c) = run(label) { f ->
        val r = f.command(c, 60_000) { info -> scope.launch { out("  $info") } }
        withContext(Dispatchers.Main) { out(if (r.ok) "OKAY ${r.message}" else "FAILED ${r.message}") }
    }

    LaunchedEffect(target) {
        try {
            val f = withContext(Dispatchers.IO) { Fastboot(Usb.open(context, target)) }
            fb = f
            out("Connected to ${target.label}")
            run("device info") { f2 ->
                for (v in listOf("product", "serialno", "unlocked", "current-slot", "is-userspace", "max-download-size", "version-bootloader")) {
                    val value = f2.getvar(v)
                    withContext(Dispatchers.Main) { out("  $v: ${value ?: "-"}") }
                }
            }
        } catch (e: Exception) {
            out("Couldn't open device: ${e.message}")
        }
    }
    DisposableEffect(Unit) { onDispose { fb?.close() } }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, size) = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst()
            c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
        } ?: ("image" to -1L)
        val part = partition.trim()
        confirm = "Flash $name (${size / 1024} KB) to \"$part\"?" to {
            run("flash $part $name") { f ->
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val r = f.flash(part, input, size, onInfo = { scope.launch { out("  $it") } }, progress = { sent ->
                        progress = sent.toFloat() / size
                    })
                    withContext(Dispatchers.Main) { out(if (r.ok) "OKAY flashed $part" else "FAILED ${r.message}") }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Fastboot · ${target.label}", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.padding(horizontal = 12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(enabled = !busy, onClick = { run("getvar all") { f -> val l = f.getvarAll(); withContext(Dispatchers.Main) { l.forEach { out("  $it") } } } }) { Text("getvar all") }
                OutlinedButton(enabled = !busy, onClick = { cmd("reboot") }) { Text("Reboot") }
                OutlinedButton(enabled = !busy, onClick = { cmd("reboot-bootloader") }) { Text("Bootloader") }
                OutlinedButton(enabled = !busy, onClick = { cmd("reboot-recovery") }) { Text("Recovery") }
                OutlinedButton(enabled = !busy, onClick = { cmd("reboot-fastboot") }) { Text("fastbootd") }
                OutlinedButton(enabled = !busy, onClick = { cmd("set_active:a") }) { Text("Slot A") }
                OutlinedButton(enabled = !busy, onClick = { cmd("set_active:b") }) { Text("Slot B") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(partition, { partition = it.trim() }, label = { Text("Partition") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(6.dp))
                Button(enabled = !busy && partition.isNotBlank(), onClick = { picker.launch(arrayOf("*/*")) }) { Text("Flash…") }
                Spacer(Modifier.size(6.dp))
                OutlinedButton(enabled = !busy && partition.isNotBlank(), onClick = {
                    confirm = "Erase \"$partition\"? Its contents are lost." to { cmd("erase:$partition") }
                }) { Text("Erase") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(raw, { raw = it }, label = { Text("Raw command (e.g. oem device-info, flashing unlock)") }, singleLine = true,
                    modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                Spacer(Modifier.size(6.dp))
                Button(enabled = !busy && raw.isNotBlank(), onClick = {
                    val c = raw.trim().replaceFirst(Regex("^fastboot\\s+"), "").let { fastbootCommand(it) }
                    if (c.startsWith("flashing") || c.startsWith("oem unlock") || c.startsWith("erase")) confirm = "Send \"$c\"? This can wipe the device." to { cmd(c) }
                    else cmd(c)
                }) { Text("Send") }
            }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(8.dp).background(Color.Black).padding(8.dp), state = listState) {
            items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFD1D5DB)) }
        }
    }
    confirm?.let { (text, action) ->
        ConfirmDialog("Are you sure?", text, confirm = "Yes", onConfirm = action, onDismiss = { confirm = null })
    }
}

/** Maps CLI-style input ("getvar product", "oem unlock") to protocol commands ("getvar:product", "oem unlock"). */
fun fastbootCommand(input: String): String {
    val parts = input.trim().split(Regex("\\s+"))
    return when (parts[0]) {
        "getvar", "erase", "set_active", "boot" -> parts[0] + ":" + parts.drop(1).joinToString(" ")
        "--set-active" -> "set_active:" + parts.drop(1).joinToString(" ")
        "reboot" -> if (parts.size > 1) "reboot-${parts[1]}" else "reboot"
        else -> parts.joinToString(" ")
    }
}
