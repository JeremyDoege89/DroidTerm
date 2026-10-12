package dev.dreamz.droidterm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamz.droidterm.data.AppDb
import dev.dreamz.droidterm.data.BookmarkEntity
import dev.dreamz.droidterm.data.StepEntity
import dev.dreamz.droidterm.tasks.CommandParser
import dev.dreamz.droidterm.term.Sessions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun TasksScreen() {
    val dao = AppDb.db.tasks()
    val tasks by dao.summaries().collectAsState(initial = emptyList())
    var importing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Tasks", "Copy commands from a guide, import them, tick them off as you go")
            if (tasks.isEmpty()) {
                Text(
                    "No tasks yet.\n\nCopy the commands from a web page or README, then tap \"Import from clipboard\". " +
                        "They become a checklist you can run step by step.",
                    modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                items(tasks, key = { it.id }) { t ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        onClick = { Nav.openTaskId = t.id },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (t.pinned) Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.done}/${t.total}", color = if (t.total > 0 && t.done == t.total) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.size(8.dp))
                            LinearProgressIndicator(progress = { if (t.total == 0) 0f else t.done.toFloat() / t.total }, modifier = Modifier.fillMaxWidth())
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.createdAt)),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { importing = true },
            icon = { Icon(Icons.Filled.ContentPaste, null) },
            text = { Text("Import from clipboard") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (importing) ImportDialog(taskId = null, onDismiss = { importing = false })
}

/** Import (or append, when [taskId] is set) commands from the clipboard. The text stays editable before saving. */
@Composable
fun ImportDialog(taskId: Long?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dao = AppDb.db.tasks()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(context.clipboardText().orEmpty()) }
    var keepNotes by remember { mutableStateOf(true) }
    val items = remember(text, keepNotes) { CommandParser.parse(text, keepNotes) }
    var name by remember { mutableStateOf("") }
    val suggested = CommandParser.suggestName(items)
    val commandCount = items.count { !it.isNote }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (taskId == null) "New task from clipboard" else "Add steps from clipboard") },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                if (taskId == null) {
                    OutlinedTextField(name, { name = it }, label = { Text("What are you trying to do?") },
                        placeholder = { Text(suggested ?: "e.g. Root my Pixel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(8.dp))
                }
                OutlinedTextField(
                    text, { text = it }, label = { Text("Copied text") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keepNotes, { keepNotes = it })
                    Text("Keep # comments as notes", fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { text = context.clipboardText().orEmpty() }) { Text("Re-paste") }
                }
                Text("$commandCount command${if (commandCount == 1) "" else "s"} found", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    itemsIndexed(items) { i, it ->
                        Text(
                            if (it.isNote) "# ${it.text}" else "${i + 1}. ${it.text}",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = if (it.isNote) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            fontStyle = if (it.isNote) FontStyle.Italic else FontStyle.Normal,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = commandCount > 0, onClick = {
                val steps = items.map { it.text to it.isNote }
                scope.launch {
                    if (taskId == null) {
                        val finalName = name.trim().ifEmpty { suggested ?: "Task ${DateFormat.getDateInstance().format(Date())}" }
                        val id = dao.createTask(finalName, steps)
                        Nav.openTaskId = id
                    } else dao.appendSteps(taskId, steps)
                }
                onDismiss()
            }) { Text(if (taskId == null) "Create task" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskDetailScreen(taskId: Long) {
    val context = LocalContext.current
    val dao = AppDb.db.tasks()
    val task by remember(taskId) { dao.task(taskId) }.collectAsState(initial = null)
    val steps by remember(taskId) { dao.steps(taskId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var appending by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StepEntity?>(null) }
    var addingStep by remember { mutableStateOf(false) }
    val t = task ?: return
    val commands = steps.filter { !it.isNote }
    val done = commands.count { it.done }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.openTaskId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$done of ${commands.size} done", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { scope.launch { if (t.pinned) dao.unpinAll() else dao.pin(t.id) } }) {
                Icon(Icons.Filled.PushPin, if (t.pinned) "Unpin from terminal" else "Pin to terminal",
                    tint = if (t.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Add steps from clipboard") }, onClick = { menu = false; appending = true })
                    DropdownMenuItem(text = { Text("Add a step") }, onClick = { menu = false; addingStep = true })
                    DropdownMenuItem(text = { Text("Reset progress") }, onClick = { menu = false; scope.launch { dao.resetProgress(t.id) } })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Copy all commands") }, onClick = {
                        menu = false; context.copyText(t.name, commands.joinToString("\n") { it.text }); context.toast("Copied")
                    })
                    DropdownMenuItem(text = { Text("Delete task") }, onClick = { menu = false; deleting = true })
                }
            }
        }
        LinearProgressIndicator(progress = { if (commands.isEmpty()) 0f else done.toFloat() / commands.size }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Text("Tap a step to check it off. ▶ types it into the terminal and runs it. Long-press for more.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(steps, key = { it.id }) { s ->
                if (s.isNote) {
                    Text(s.text, fontStyle = FontStyle.Italic, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { editing = s }).padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
                } else {
                    StepRow(s, onToggle = { scope.launch { dao.setDone(s.id, !s.done, if (!s.done) System.currentTimeMillis() else null) } },
                        onRun = {
                            Sessions.type(s.text, enter = true)
                            scope.launch { dao.setDone(s.id, true, System.currentTimeMillis()) }
                            Nav.screen = Screen.TERMINAL
                        },
                        onLong = { editing = s })
                }
            }
        }
    }

    if (renaming) TextPromptDialog("Rename task", t.name, onDismiss = { renaming = false }) { scope.launch { dao.rename(t.id, it) } }
    if (deleting) ConfirmDialog("Delete \"${t.name}\"?", "Its steps and progress are removed.", onConfirm = {
        scope.launch { dao.deleteTask(t.id) }; Nav.openTaskId = null
    }, onDismiss = { deleting = false })
    if (appending) ImportDialog(taskId = t.id, onDismiss = { appending = false })
    if (addingStep) TextPromptDialog("Add a step", "", monospace = true, onDismiss = { addingStep = false }) {
        scope.launch { dao.appendSteps(t.id, listOf(it to false)) }
    }
    editing?.let { s -> StepActionsDialog(s, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepRow(s: StepEntity, onToggle: () -> Unit, onRun: () -> Unit, onLong: () -> Unit) {
    val alpha = if (s.done) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLong)
            .background(if (s.done) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (s.done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, if (s.done) "Done" else "Not done",
            tint = if (s.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        Text(
            s.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            textDecoration = if (s.done) TextDecoration.LineThrough else null,
        )
        IconButton(onClick = onRun) { Icon(Icons.Filled.PlayArrow, "Run in terminal", tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun StepActionsDialog(s: StepEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = AppDb.db.tasks()
    var edit by remember { mutableStateOf(false) }
    if (edit) {
        TextPromptDialog("Edit step", s.text, monospace = !s.isNote, onDismiss = onDismiss) { scope.launch { dao.updateStep(s.copy(text = it)) } }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (s.isNote) "Note" else "Step", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(8.dp))
                if (!s.isNote) {
                    TextButton(onClick = { Sessions.type(s.text, enter = false); Nav.screen = Screen.TERMINAL; onDismiss() }) { Text("Type into terminal (don't run)") }
                    TextButton(onClick = { context.copyText("command", s.text); context.toast("Copied"); onDismiss() }) { Text("Copy") }
                    TextButton(onClick = {
                        scope.launch { AppDb.db.bookmarks().insert(BookmarkEntity(label = s.text.take(40), command = s.text)) }
                        context.toast("Bookmarked"); onDismiss()
                    }) { Text("Bookmark") }
                }
                TextButton(onClick = { edit = true }) { Text("Edit") }
                TextButton(onClick = { scope.launch { dao.deleteStep(s) }; onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun TextPromptDialog(title: String, initial: String, monospace: Boolean = false, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(v, { v = it }, modifier = Modifier.fillMaxWidth(),
                textStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge)
        },
        confirmButton = { TextButton(enabled = v.isNotBlank(), onClick = { onSave(v.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
