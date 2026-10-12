package dev.dreamz.droidterm.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.dreamz.droidterm.adb.AdbTools
import dev.dreamz.droidterm.adb.Usb
import dev.dreamz.droidterm.adb.UsbAdbBackend
import dev.dreamz.droidterm.adb.UsbMode
import dev.dreamz.droidterm.adb.UsbTarget
import dev.dreamz.droidterm.adb.WirelessDebugging
import dev.dreamz.droidterm.term.Sessions
import dev.dreamz.droidterm.term.TabKind
import dev.dreamz.droidterm.term.TermEnv
import dev.dreamz.droidterm.term.TermService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ACTION_USB_PERMISSION = "dev.dreamz.droidterm.USB_PERMISSION"

@Composable
fun AdbScreen() {
    var fastbootTarget by remember { mutableStateOf<UsbTarget?>(null) }
    val fb = fastbootTarget
    if (fb != null) { FastbootScreen(fb, onBack = { fastbootTarget = null }); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("ADB / USB", "Bundled adb + fastboot, Wireless debugging and USB OTG")
        if (!TermEnv.adbAvailable) {
            Text("Bundled adb isn't available for this CPU. USB adb/fastboot below still works.",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        val context = LocalContext.current
        val wd = remember { WirelessDebugging(context) }
        DisposableEffect(Unit) { wd.start(); onDispose { wd.stop() } }
        DevicesCard()
        ThisPhoneCard(wd)
        NetworkCard(wd)
        UsbCard(onFastboot = { fastbootTarget = it })
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            content()
        }
    }
}

/** Shared status line under a card's buttons. */
@Composable
private fun Status(text: String?) {
    if (!text.isNullOrBlank()) Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp))
}

object AdbState {
    var devices by mutableStateOf<List<AdbTools.Device>>(emptyList())
    var serverStatus by mutableStateOf<String?>(null)
    suspend fun refresh() {
        val r = AdbTools.adb("devices", "-l")
        devices = AdbTools.parseDevices(r.output)
        serverStatus = if (r.ok) null else r.output
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicesCard() {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { AdbState.refresh() }
    SectionCard("adb devices") {
        if (AdbState.devices.isEmpty()) Text("No devices connected to the on-phone adb server yet.", fontSize = 13.sp)
        AdbState.devices.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.model ?: d.serial, fontWeight = FontWeight.Medium)
                    Text("${d.serial} · ${d.state}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (d.state == "device") Button(onClick = { Sessions.newAdbShell(d.serial, d.model ?: d.serial); Nav.screen = Screen.TERMINAL }) { Text("Shell") }
                if (d.isNetwork) TextButton(onClick = { scope.launch { AdbTools.disconnect(d.serial); AdbState.refresh() } }) { Text("Drop") }
            }
            HorizontalDivider()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { AdbState.refresh() } }) { Text("Refresh") }
            OutlinedButton(onClick = { scope.launch { AdbState.serverStatus = AdbTools.startServer().output.ifBlank { "Server running" }; AdbState.refresh() } }) { Text("Start server") }
            OutlinedButton(onClick = { scope.launch { AdbState.serverStatus = AdbTools.killServer().output.ifBlank { "Server stopped" }; AdbState.devices = emptyList() } }) { Text("Kill server") }
        }
        Status(AdbState.serverStatus)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThisPhoneCard(wd: WirelessDebugging) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val endpoints by wd.endpoints.collectAsState()
    val pairingStatus by TermService.pairing.collectAsState()
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val localPair = endpoints.firstOrNull { it.isLocal && it.isPairing }
    val localConnect = endpoints.firstOrNull { it.isLocal && !it.isPairing }
    LaunchedEffect(localPair?.port) { localPair?.let { port = it.port.toString() } }

    SectionCard("This phone (Wireless debugging)") {
        Text(
            "1. Developer options → Wireless debugging → on.\n2. Tap \"Pair device with pairing code\".\n" +
                "3. Tap \"Pair via notification\" here and type the code into the notification (the dialog closes if you switch apps). " +
                "Or use split screen and enter it below.",
            fontSize = 13.sp,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            when {
                localConnect != null -> "Wireless debugging is on (port ${localConnect.port})"
                else -> "Wireless debugging port not detected"
            } + (localPair?.let { " · pairing dialog open (port ${it.port})" } ?: ""),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Developer options") }
            Button(onClick = { TermService.startPairing(context) }) { Text("Pair via notification") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Pair port") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.size(6.dp))
            OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = port.isNotEmpty() && code.length >= 6, onClick = {
                scope.launch {
                    status = "Pairing…"
                    status = AdbTools.pair("127.0.0.1", port.toInt(), code).output
                }
            }) { Text("Pair") }
            Button(enabled = localConnect != null, onClick = {
                scope.launch {
                    val c = localConnect ?: return@launch
                    status = "Connecting…"
                    val r = AdbTools.connect("127.0.0.1", c.port)
                    status = r.output
                    AdbState.refresh()
                    if (AdbTools.looksSuccessful(r)) {
                        Sessions.newAdbShell("127.0.0.1:${c.port}", "this phone")
                        Nav.screen = Screen.TERMINAL
                    }
                }
            }) { Text("Connect + shell") }
        }
        Status(pairingStatus ?: status)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkCard(wd: WirelessDebugging) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("adb", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "").orEmpty()) }
    var port by remember { mutableStateOf(prefs.getString("port", "5555").orEmpty()) }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val found by wd.endpoints.collectAsState()
    val remote = found.filter { !it.isLocal }

    SectionCard("Another device on the network") {
        Text("Pair (Android 11+ Wireless debugging) once, then connect. For adb tcpip 5555 devices just connect.", fontSize = 13.sp)
        remote.forEach { ep ->
            TextButton(onClick = {
                host = ep.host
                if (ep.isPairing) pairPort = ep.port.toString() else port = ep.port.toString()
            }) { Text("${if (ep.isPairing) "Pairing" else "Debugging"}: ${ep.host}:${ep.port}", fontSize = 12.sp) }
        }
        OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Row {
            OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit) }, label = { Text("Pair port") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.size(6.dp))
            OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.size(6.dp))
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = host.isNotEmpty() && pairPort.isNotEmpty() && code.length >= 6, onClick = {
                scope.launch { status = "Pairing…"; status = AdbTools.pair(host, pairPort.toInt(), code).output }
            }) { Text("Pair") }
            Button(enabled = host.isNotEmpty() && port.isNotEmpty(), onClick = {
                prefs.edit().putString("host", host).putString("port", port).apply()
                scope.launch {
                    status = "Connecting…"
                    val r = AdbTools.connect(host, port.toInt())
                    status = r.output
                    AdbState.refresh()
                    if (AdbTools.looksSuccessful(r)) { Sessions.newAdbShell("$host:$port"); Nav.screen = Screen.TERMINAL }
                }
            }) { Text("Connect + shell") }
        }
        Status(status)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsbCard(onFastboot: (UsbTarget) -> Unit) {
    val context = LocalContext.current
    val um = remember { context.getSystemService(UsbManager::class.java) }
    var targets by remember { mutableStateOf(Usb.list(context)) }
    var tick by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { targets = Usb.list(context); tick++ }
        }
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(context, r, f, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(r) }
    }
    LaunchedEffect(Unit) { while (true) { delay(3000); targets = Usb.list(context) } }

    fun withPermission(t: UsbTarget, action: () -> Unit) {
        if (um.hasPermission(t.device)) { action(); return }
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
        um.requestPermission(t.device, pi)
        context.toast("Allow USB access, then tap again")
    }

    SectionCard("USB (OTG)") {
        Text("Plug another phone in with an OTG cable. adb shell needs USB debugging on that phone; fastboot works in bootloader/fastbootd.", fontSize = 13.sp)
        if (targets.isEmpty()) Text("No USB devices.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        targets.forEach { t ->
            androidx.compose.runtime.key(tick) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(t.label, fontWeight = FontWeight.Medium)
                    Text(
                        "${t.mode.name.lowercase()} · %04x:%04x".format(t.device.vendorId, t.device.productId) +
                            (if (um.hasPermission(t.device)) "" else " · needs permission"),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (t.mode) {
                            UsbMode.ADB -> Button(onClick = {
                                withPermission(t) {
                                    Sessions.newStream(t.label, UsbAdbBackend(context.applicationContext, t), TabKind.USB_ADB)
                                    Nav.screen = Screen.TERMINAL
                                }
                            }) { Text("adb shell") }
                            UsbMode.FASTBOOT -> Button(onClick = { withPermission(t) { onFastboot(t) } }) { Text("Fastboot tools") }
                            UsbMode.OTHER -> Text("Not in adb or fastboot mode", fontSize = 12.sp)
                        }
                        if (!um.hasPermission(t.device) && t.mode != UsbMode.OTHER) OutlinedButton(onClick = { withPermission(t) {} }) { Text("Grant access") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
