package dev.dreamz.droidterm.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import dev.dreamz.droidterm.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TabKind { LOCAL, ADB, SSH, USB_ADB }

class TermTab(
    val id: Int,
    val session: TerminalSession,
    val baseTitle: String,
    val kind: TabKind,
) {
    /** Text to type once the session has started (it can't take input before the view has sized it). */
    internal val pending = StringBuilder()
    val title: String get() = session.title?.takeIf { it.isNotBlank() } ?: baseTitle
}

/** All open terminal tabs. Lives in the app process; TermService keeps the process alive while tabs exist. */
object Sessions {
    private const val TRANSCRIPT_ROWS = 5000
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1

    private val _tabs = MutableStateFlow<List<TermTab>>(emptyList())
    val tabs: StateFlow<List<TermTab>> = _tabs.asStateFlow()
    private val _current = MutableStateFlow<TermTab?>(null)
    val current: StateFlow<TermTab?> = _current.asStateFlow()
    /** Bumped when titles or running state change so the tab strip recomposes. */
    val revision = MutableStateFlow(0)

    /** The TerminalView currently on screen, if any. */
    var view: TerminalView? = null

    private fun ctx(): Context = App.instance

    fun newLocalShell(title: String = "shell"): TermTab =
        newProcess(title, "/system/bin/sh", arrayOf("-sh"), TabKind.LOCAL)

    /** Runs a bundled tool (adb/fastboot) or any program in a PTY tab. args[0] is argv[0]. */
    fun newProcess(title: String, program: String, args: Array<String>, kind: TabKind): TermTab {
        checkMain()
        val s = TerminalSession(program, TermEnv.home.absolutePath, args, TermEnv.environmentArray(), TRANSCRIPT_ROWS, client)
        return add(s, title, kind)
    }

    fun newAdbShell(serial: String, title: String = serial): TermTab =
        newProcess(title, TermEnv.adbPath, arrayOf("adb", "-s", serial, "shell"), TabKind.ADB)

    fun newStream(title: String, backend: TerminalSession.StreamBackend, kind: TabKind): TermTab {
        checkMain()
        val s = TerminalSession(backend, TRANSCRIPT_ROWS, client)
        return add(s, title, kind)
    }

    private fun add(s: TerminalSession, title: String, kind: TabKind): TermTab {
        val tab = TermTab(nextId++, s, title, kind)
        _tabs.value = _tabs.value + tab
        select(tab)
        TermService.sync(ctx())
        return tab
    }

    fun select(tab: TermTab) {
        _current.value = tab
        view?.let { v ->
            v.attachSession(tab.session)
            v.onScreenUpdated()
        }
    }

    fun close(tab: TermTab) {
        checkMain()
        tab.session.finishIfRunning()
        val list = _tabs.value.filter { it !== tab }
        _tabs.value = list
        if (_current.value === tab) {
            val next = list.lastOrNull()
            _current.value = next
            if (next != null) select(next)
        }
        TermService.sync(ctx())
    }

    fun closeAll() {
        _tabs.value.forEach { it.session.finishIfRunning() }
        _tabs.value = emptyList()
        _current.value = null
        TermService.sync(ctx())
    }

    /** Current tab, opening a local shell first if there is none. */
    fun currentOrNew(): TermTab = _current.value ?: newLocalShell()

    /**
     * Types [text] into the current terminal. With [enter], presses Enter afterwards. Multi-line text is pasted
     * (bracketed paste aware) so heredocs arrive intact.
     */
    fun type(text: String, enter: Boolean) {
        checkMain()
        val tab = currentOrNew()
        tab.pending.append(text)
        if (enter) tab.pending.append('\r')
        flushPending(tab)
    }

    private fun flushPending(tab: TermTab) {
        val emu: TerminalEmulator = tab.session.emulator ?: return
        if (!tab.session.isRunning || tab.pending.isEmpty()) return
        val text = tab.pending.toString()
        tab.pending.setLength(0)
        val endsWithEnter = text.endsWith('\r')
        val body = if (endsWithEnter) text.dropLast(1) else text
        if (body.isNotEmpty()) emu.paste(body)
        if (endsWithEnter) tab.session.write("\r")
    }

    private fun tabOf(s: TerminalSession) = _tabs.value.firstOrNull { it.session === s }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Sessions must be used on the main thread" }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            tabOf(changedSession)?.let { if (it.pending.isNotEmpty()) flushPending(it) }
            if (_current.value?.session === changedSession) view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            revision.value++
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            revision.value++
            TermService.sync(ctx())
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = ctx().getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("DroidTerm", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = ctx().getSystemService(ClipboardManager::class.java)
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx())?.toString() ?: return
            session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {
            if (_current.value?.session === session) view?.invalidate()
        }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }
}
