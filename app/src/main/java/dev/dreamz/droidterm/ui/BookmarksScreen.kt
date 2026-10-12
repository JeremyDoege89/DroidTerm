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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamz.droidterm.data.AppDb
import dev.dreamz.droidterm.data.BookmarkEntity
import dev.dreamz.droidterm.term.Sessions
import kotlinx.coroutines.launch

private val STARTERS = listOf(
    "List adb devices" to "adb devices -l",
    "Reboot to bootloader" to "adb reboot bootloader",
    "Fastboot devices" to "fastboot devices",
    "Battery status" to "dumpsys battery",
    "Disk usage" to "df -h",
    "IP addresses" to "ip -br addr",
    "Running processes" to "ps -A | head -40",
    "Installed packages" to "pm list packages -3",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen() {
    val dao = AppDb.db.bookmarks()
    val marks by dao.all().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Bookmarks", "Tap to type into the terminal, ▶ to run, long-press to edit")
            val list = marks ?: return@Column
            if (list.isEmpty()) {
                Text("No bookmarks yet.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    scope.launch { STARTERS.forEach { (l, c) -> dao.insert(BookmarkEntity(label = l, command = c)) } }
                }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Add a starter set (adb, fastboot, system info)") }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(list, key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { Sessions.type(b.command, enter = false); scope.launch { dao.used(b.id) }; Nav.screen = Screen.TERMINAL },
                            onLongClick = { editing = b },
                        ).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.label, fontWeight = FontWeight.Medium)
                            Text(b.command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { Sessions.type(b.command, enter = true); scope.launch { dao.used(b.id) }; Nav.screen = Screen.TERMINAL }) {
                            Icon(Icons.Filled.PlayArrow, "Run", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, "Add bookmark")
        }
    }
    if (creating) BookmarkDialog(null, onDismiss = { creating = false })
    editing?.let { BookmarkDialog(it, onDismiss = { editing = null }) }
}

@Composable
private fun BookmarkDialog(existing: BookmarkEntity?, onDismiss: () -> Unit) {
    val dao = AppDb.db.bookmarks()
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var command by remember { mutableStateOf(existing?.command.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New bookmark" else "Edit bookmark") },
        text = {
            Column {
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(command, { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                if (existing != null) {
                    TextButton(onClick = { scope.launch { dao.delete(existing) }; onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = command.isNotBlank(), onClick = {
                val l = label.trim().ifEmpty { command.trim().take(40) }
                scope.launch {
                    if (existing == null) dao.insert(BookmarkEntity(label = l, command = command.trim()))
                    else dao.update(existing.copy(label = l, command = command.trim()))
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
