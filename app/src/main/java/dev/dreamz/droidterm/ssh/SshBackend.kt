package dev.dreamz.droidterm.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.termux.terminal.TerminalSession
import dev.dreamz.droidterm.term.TermEnv
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

/** Everything needed to open one SSH connection; secrets are already decrypted. */
data class SshTarget(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val keyName: String? = null,
    val privateKey: ByteArray? = null,
    val publicKey: ByteArray? = null,
    val passphrase: String? = null,
)

/** Terminal tab backend: an interactive SSH shell (JSch). */
class SshBackend(private val t: SshTarget) : TerminalSession.StreamBackend {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val notes = StringBuilder()

    override fun connect(columns: Int, rows: Int) {
        JschSetup.ensure()
        val jsch = JSch()
        jsch.setKnownHosts(TermEnv.knownHosts.absolutePath.also { TermEnv.knownHosts.parentFile?.mkdirs(); TermEnv.knownHosts.createNewFile() })
        if (t.privateKey != null) {
            jsch.addIdentity(t.keyName ?: "key", t.privateKey, t.publicKey, t.passphrase?.toByteArray())
        }
        val s = jsch.getSession(t.username, t.host, t.port)
        s.setConfig("StrictHostKeyChecking", "accept-new")
        s.setConfig("PreferredAuthentications", if (t.privateKey != null) "publickey,keyboard-interactive,password" else "keyboard-interactive,password")
        t.password?.let { s.setPassword(it) }
        s.userInfo = Prompts(t.password)
        s.setServerAliveInterval(30_000)
        s.connect(20_000)
        session = s
        s.hostKey?.let { hk -> notes.append("Host key ${hk.type} ${hk.getFingerPrint(jsch)}\r\n") }

        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color", columns, rows, 0, 0)
        ch.setEnv("LANG", "en_US.UTF-8")
        input = ch.inputStream
        output = ch.outputStream
        ch.connect(15_000)
        channel = ch
    }

    override fun getInputStream(): InputStream =
        SequenceInputStream(ByteArrayInputStream(("\u001b[2mConnected to ${t.username}@${t.host}:${t.port}\r\n$notes\u001b[0m").toByteArray()), input!!)

    override fun getOutputStream(): OutputStream = output!!

    override fun resize(columns: Int, rows: Int) {
        val ch = channel ?: return
        Thread { runCatching { ch.setPtySize(columns, rows, 0, 0) } }.start()
    }

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
    }

    override fun exitStatus(): Int = channel?.exitStatus?.takeIf { it >= 0 } ?: 0

    /** Answers password / keyboard-interactive prompts with the saved password; nothing interactive. */
    private class Prompts(private val password: String?) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?) = password != null
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? = if (password == null || prompt == null) null else Array(prompt.size) { password }
    }
}
