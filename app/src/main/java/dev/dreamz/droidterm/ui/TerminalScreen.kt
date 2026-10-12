package dev.dreamz.droidterm.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.dreamz.droidterm.data.AppDb
import dev.dreamz.droidterm.term.Sessions
import dev.dreamz.droidterm.term.TabKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sticky CTRL/ALT from the extra-keys row. Tap = next key only, double tap = locked. */
object Modifiers {
    var ctrl by mutableStateOf(false)
    var ctrlLock by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var altLock by mutableStateOf(false)

    fun readCtrl(): Boolean { val v = ctrl || ctrlLock; if (!ctrlLock) ctrl = false; return v }
    fun readAlt(): Boolean { val v = alt || altLock; if (!altLock) alt = false; return v }
}

object TextSize {
    var px by mutableStateOf(0)
}

private class ViewClient(private val context: Context, private val viewProvider: () -> TerminalView?) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val v = viewProvider() ?: return scale
            TextSize.px = (TextSize.px * scale).toInt().coerceIn(dp(context, 7), dp(context, 40))
            v.setTextSize(TextSize.px)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val v = viewProvider() ?: return
        v.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        // Enter on a finished session closes its tab, like Termux.
        if (session != null && !session.isRunning && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            Sessions.tabs.value.firstOrNull { it.session === session }?.let { Sessions.close(it) }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = Modifiers.readCtrl()
    override fun readAltKey() = Modifiers.readAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (session != null && !session.isRunning && (codePoint == '\r'.code || codePoint == '\n'.code)) {
            Sessions.tabs.value.firstOrNull { it.session === session }?.let { Sessions.close(it) }
            return true
        }
        return false
    }

    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val tabs by Sessions.tabs.collectAsState()
    val current by Sessions.current.collectAsState()
    Sessions.revision.collectAsState().value // recompose on title / exit changes
    var showBookmarks by remember { mutableStateOf(false) }
    var termView by remember { mutableStateOf<TerminalView?>(null) }

    LaunchedEffect(Unit) { if (Sessions.tabs.value.isEmpty()) Sessions.newLocalShell() }
    if (TextSize.px == 0) TextSize.px = dp(context, 13)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TabStrip(tabs.map { it.id to it.title }, current?.id, onSelect = { id -> tabs.firstOrNull { it.id == id }?.let(Sessions::select) },
            onClose = { id -> tabs.firstOrNull { it.id == id }?.let(Sessions::close) })
        PinnedTaskBar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        val v = this
                        setTerminalViewClient(ViewClient(ctx) { v })
                        setTextSize(TextSize.px)
                        setTypeface(Typeface.MONOSPACE)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        keepScreenOn = true
                        termView = v
                        Sessions.view = v
                        Sessions.current.value?.let { attachSession(it.session) }
                        requestFocus()
                    }
                },
                update = { v ->
                    val s = current?.session
                    if (s != null && v.currentSession !== s) { v.attachSession(s); v.onScreenUpdated() }
                },
            )
            DisposableEffect(Unit) { onDispose { if (Sessions.view === termView) Sessions.view = null } }
        }
        ExtraKeys(termView, onBookmarks = { showBookmarks = true })
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            QuickCommandsSheet(onDone = { showBookmarks = false })
        }
    }
}

@Composable
private fun TabStrip(tabs: List<Pair<Int, String>>, currentId: Int?, onSelect: (Int) -> Unit, onClose: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEach { (id, title) ->
                val sel = id == currentId
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (sel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onSelect(id) }
                        .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { onClose(id) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "Close tab", Modifier.size(16.dp))
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, "New tab") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Local shell") }, onClick = { menu = false; Sessions.newLocalShell() })
                DropdownMenuItem(text = { Text("SSH host…") }, onClick = { menu = false; Nav.screen = Screen.SSH })
                DropdownMenuItem(text = { Text("ADB device…") }, onClick = { menu = false; Nav.screen = Screen.ADB })
            }
        }
    }
}



/** "Next step" bar for the pinned task: run the next unchecked command or tick it off without leaving the terminal. */
@Composable
private fun PinnedTaskBar() {
    val dao = AppDb.db.tasks()
    val pinned by dao.pinned().collectAsState(initial = null)
    val task = pinned ?: return
    val steps by remember(task.id) { dao.steps(task.id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val commands = steps.filter { !it.isNote }
    val next = commands.firstOrNull { !it.done }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { Nav.openTaskId = task.id; Nav.screen = Screen.TASKS }.padding(vertical = 4.dp)) {
                Text("${task.name} · ${commands.count { it.done }}/${commands.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(next?.text ?: "All steps done", fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (next != null) {
                IconButton(onClick = {
                    Sessions.type(next.text, enter = true)
                    scope.launch { dao.setDone(next.id, true, System.currentTimeMillis()) }
                }) { Icon(Icons.Filled.PlayArrow, "Run next step", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { scope.launch { dao.setDone(next.id, true, System.currentTimeMillis()) } }) {
                    Icon(Icons.Filled.Check, "Mark done")
                }
            }
            IconButton(onClick = { scope.launch { dao.unpinAll() } }) { Icon(Icons.Filled.Close, "Unpin task", Modifier.size(18.dp)) }
        }
    }
}

private sealed class XKey(val label: String) {
    class Code(label: String, val keyCode: Int, val repeat: Boolean = false) : XKey(label)
    class Char(label: String, val text: String = label) : XKey(label)
    object Ctrl : XKey("CTRL")
    object Alt : XKey("ALT")
    object Paste : XKey("PASTE")
    object Marks : XKey("★")
    object Keyboard : XKey("⌨")
}

private val ROW1 = listOf(
    XKey.Code("ESC", KeyEvent.KEYCODE_ESCAPE), XKey.Char("/"), XKey.Char("-"), XKey.Code("HOME", KeyEvent.KEYCODE_MOVE_HOME),
    XKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP, true), XKey.Code("END", KeyEvent.KEYCODE_MOVE_END), XKey.Code("PGUP", KeyEvent.KEYCODE_PAGE_UP),
    XKey.Char("|"), XKey.Marks,
)
private val ROW2 = listOf(
    XKey.Code("TAB", KeyEvent.KEYCODE_TAB), XKey.Ctrl, XKey.Alt, XKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT, true),
    XKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN, true), XKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT, true),
    XKey.Code("PGDN", KeyEvent.KEYCODE_PAGE_DOWN), XKey.Paste, XKey.Keyboard,
)

@Composable
private fun ExtraKeys(view: TerminalView?, onBookmarks: () -> Unit) {
    val context = LocalContext.current
    fun press(k: XKey) {
        val v = view ?: return
        when (k) {
            is XKey.Code -> {
                v.onKeyDown(k.keyCode, KeyEvent(KeyEvent.ACTION_DOWN, k.keyCode))
                v.onKeyUp(k.keyCode, KeyEvent(KeyEvent.ACTION_UP, k.keyCode))
            }
            is XKey.Char -> k.text.codePoints().forEach { v.inputCodePoint(it, false, false) }
            XKey.Ctrl -> if (Modifiers.ctrlLock) { Modifiers.ctrlLock = false; Modifiers.ctrl = false } else if (Modifiers.ctrl) Modifiers.ctrlLock = true else Modifiers.ctrl = true
            XKey.Alt -> if (Modifiers.altLock) { Modifiers.altLock = false; Modifiers.alt = false } else if (Modifiers.alt) Modifiers.altLock = true else Modifiers.alt = true
            XKey.Paste -> {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { v.currentSession?.emulator?.paste(it) }
            }
            XKey.Marks -> onBookmarks()
            XKey.Keyboard -> {
                v.requestFocus()
                context.getSystemService(InputMethodManager::class.java).toggleSoftInput(0, 0)
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF0B0C0E))) {
        listOf(ROW1, ROW2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(40.dp)) {
                row.forEach { k ->
                    val active = (k === XKey.Ctrl && (Modifiers.ctrl || Modifiers.ctrlLock)) || (k === XKey.Alt && (Modifiers.alt || Modifiers.altLock))
                    val locked = (k === XKey.Ctrl && Modifiers.ctrlLock) || (k === XKey.Alt && Modifiers.altLock)
                    KeyButton(k.label, active, locked, repeat = k is XKey.Code && k.repeat, modifier = Modifier.weight(1f)) { press(k) }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, active: Boolean, locked: Boolean, repeat: Boolean, modifier: Modifier, onPress: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        modifier.fillMaxSize()
            .background(if (locked) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent)
            .pointerInput(label, repeat) {
                detectTapGestures(onPress = {
                    onPress()
                    if (repeat) {
                        val job = scope.launch {
                            delay(400)
                            while (true) { onPress(); delay(60) }
                        }
                        tryAwaitRelease()
                        job.cancel()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else Color(0xFFE5E7EB),
        )
    }
}

/** Bottom sheet from the ★ key: bookmarks to run or insert. */
@Composable
private fun QuickCommandsSheet(onDone: () -> Unit) {
    val dao = AppDb.db.bookmarks()
    val marks by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp))
        if (marks.isEmpty()) {
            Text("No bookmarks yet. Add them in the Bookmarks tab.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(marks, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().clickable {
                    Sessions.type(b.command, enter = false); scope.launch { dao.used(b.id) }; onDone()
                }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.label, fontWeight = FontWeight.Medium)
                        Text(b.command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { Sessions.type(b.command, enter = true); scope.launch { dao.used(b.id) }; onDone() }) {
                        Icon(Icons.Filled.PlayArrow, "Run", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Tap to insert, ▶ to run", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
