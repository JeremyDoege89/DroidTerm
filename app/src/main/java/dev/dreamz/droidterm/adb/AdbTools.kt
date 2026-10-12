package dev.dreamz.droidterm.adb

import dev.dreamz.droidterm.term.TermEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the bundled adb / fastboot binaries non-interactively. */
object AdbTools {
    data class Result(val exitCode: Int, val output: String) {
        val ok get() = exitCode == 0
    }

    data class Device(val serial: String, val state: String, val description: String) {
        val model: String? get() = Regex("""model:(\S+)""").find(description)?.groupValues?.get(1)?.replace('_', ' ')
        val isNetwork get() = serial.contains(':') || serial.startsWith("adb-")
    }

    suspend fun adb(vararg args: String, timeoutSec: Long = 30): Result = run(TermEnv.adbPath, args.toList(), timeoutSec)

    suspend fun fastboot(vararg args: String, timeoutSec: Long = 60): Result = run(TermEnv.fastbootPath, args.toList(), timeoutSec)

    private suspend fun run(binary: String, args: List<String>, timeoutSec: Long): Result = withContext(Dispatchers.IO) {
        if (!File(binary).canExecute()) return@withContext Result(127, "Bundled tool not available for this CPU ($binary)")
        // Output goes to a file, not a pipe: "adb start-server" leaves a daemon behind that would hold a pipe open.
        val out = File.createTempFile("adb", ".out", TermEnv.tmp)
        try {
            val pb = ProcessBuilder(listOf(binary) + args)
                .directory(TermEnv.home)
                .redirectErrorStream(true)
                .redirectOutput(out)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            pb.environment().apply { clear(); putAll(TermEnv.environment()) }
            val p = pb.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return@withContext Result(124, out.readText() + "\n(timed out after ${timeoutSec}s)")
            }
            Result(p.exitValue(), out.readText().trim())
        } catch (e: Exception) {
            Result(1, e.message ?: e.toString())
        } finally {
            out.delete()
        }
    }

    suspend fun devices(): List<Device> {
        val r = adb("devices", "-l")
        return parseDevices(r.output)
    }

    fun parseDevices(output: String): List<Device> = output.lines()
        .dropWhile { !it.startsWith("List of devices") }.drop(1)
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 2) null else Device(parts[0], parts[1], parts.getOrElse(2) { "" })
        }

    suspend fun pair(host: String, port: Int, code: String) = adb("pair", "$host:$port", code)
    suspend fun connect(host: String, port: Int) = adb("connect", "$host:$port", timeoutSec = 20)
    suspend fun disconnect(serial: String) = adb("disconnect", serial)
    suspend fun startServer() = adb("start-server")
    suspend fun killServer() = adb("kill-server")

    /** "connected to x" / "already connected to x" / "paired" / "Successfully paired" */
    fun looksSuccessful(r: Result) = r.ok && !r.output.contains("failed", true) && !r.output.contains("unable", true) &&
        !r.output.contains("error", true)
}
