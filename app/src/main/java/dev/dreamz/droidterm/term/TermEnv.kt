package dev.dreamz.droidterm.term

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Filesystem layout and environment for shells:
 *   $PREFIX/bin   symlinks to the bundled tools (adb, fastboot) in nativeLibraryDir
 *   $PREFIX/etc   DroidTerm's mkshrc (regenerated on every start)
 *   $HOME         the user's home (~/.mkshrc, ~/.android/adbkey, ~/.ssh/known_hosts)
 */
object TermEnv {
    lateinit var home: File; private set
    lateinit var prefix: File; private set
    lateinit var bin: File; private set
    lateinit var tmp: File; private set
    lateinit var nativeDir: String; private set

    val adbPath get() = "$nativeDir/libadb.so"
    val fastbootPath get() = "$nativeDir/libfastboot.so"
    val adbAvailable get() = File(adbPath).canExecute()
    val androidDir get() = File(home, ".android")
    val knownHosts get() = File(home, ".ssh/known_hosts")

    private val tools = mapOf("adb" to "libadb.so", "fastboot" to "libfastboot.so")

    fun bootstrap(context: Context) {
        val files = context.filesDir
        prefix = File(files, "usr")
        bin = File(prefix, "bin")
        home = File(files, "home")
        tmp = File(context.cacheDir, "tmp")
        nativeDir = context.applicationInfo.nativeLibraryDir
        listOf(bin, File(prefix, "etc"), home, tmp, androidDir, knownHosts.parentFile!!).forEach { it.mkdirs() }

        // nativeLibraryDir moves on every app update, so the links are rebuilt each start.
        for ((name, lib) in tools) {
            val link = File(bin, name)
            val target = File(nativeDir, lib)
            try {
                link.delete()
                if (target.exists()) Os.symlink(target.absolutePath, link.absolutePath)
            } catch (e: Exception) {
                Log.w("TermEnv", "symlink $name failed", e)
            }
        }
        File(prefix, "etc/mkshrc").writeText(MKSHRC)
        val userRc = File(home, ".mkshrc")
        if (!userRc.exists()) userRc.writeText("# Your own shell setup. Runs after DroidTerm's defaults.\n")
    }

    /** Environment for every process DroidTerm starts (shells, adb, fastboot). */
    fun environment(): Map<String, String> {
        val env = LinkedHashMap(System.getenv())
        env["HOME"] = home.absolutePath
        env["PREFIX"] = prefix.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        env["PATH"] = "${bin.absolutePath}:/system/bin:/system/xbin:/product/bin:/vendor/bin"
        env["ENV"] = File(prefix, "etc/mkshrc").absolutePath
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"
        env["ANDROID_USER_HOME"] = androidDir.absolutePath
        env["SHELL"] = "/system/bin/sh"
        return env
    }

    fun environmentArray(): Array<String> = environment().map { "${it.key}=${it.value}" }.toTypedArray()

    private val MKSHRC = """
        |# DroidTerm shell defaults (rewritten on every app start). Put your own settings in ~/.mkshrc.
        |[ -r /system/etc/mkshrc ] && . /system/etc/mkshrc
        |HISTFILE=${'$'}HOME/.mksh_history
        |HISTSIZE=5000
        |PS1=${'$'}'\001\r\001\e[1;32m\001droidterm\001\e[0m\001:\001\e[1;34m\001${'$'}{PWD/#${'$'}HOME/\~}\001\e[0m\001 ${'$'} '
        |alias ll='ls -la'
        |alias la='ls -A'
        |alias ..='cd ..'
        |[ -r ${'$'}HOME/.mkshrc ] && . ${'$'}HOME/.mkshrc
        |""".trimMargin()
}
