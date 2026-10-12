package dev.dreamz.droidterm.adb

import java.io.IOException
import java.io.InputStream

/**
 * fastboot protocol over USB bulk endpoints: send an ASCII command, read replies until OKAY/FAIL.
 * INFO/TEXT replies are progress messages; DATA means "send that many bytes now".
 */
class Fastboot(private val pipe: UsbBulkPipe) {
    data class Reply(val ok: Boolean, val message: String, val info: List<String>)

    fun command(cmd: String, timeoutMs: Int = 30_000, onInfo: (String) -> Unit = {}): Reply {
        val b = cmd.toByteArray()
        if (b.size > 4096) throw IOException("Command too long")
        pipe.write(b, 0, b.size, 5000)
        return readReply(timeoutMs, onInfo)
    }

    private fun readReply(timeoutMs: Int, onInfo: (String) -> Unit): Reply {
        val info = ArrayList<String>()
        val buf = ByteArray(512)
        while (true) {
            val n = pipe.readPacket(buf, timeoutMs)
            val s = String(buf, 0, n)
            val type = s.take(4)
            val msg = s.drop(4)
            when (type) {
                "INFO", "TEXT" -> { info += msg; onInfo(msg) }
                "OKAY" -> return Reply(true, msg, info)
                "FAIL" -> return Reply(false, msg, info)
                "DATA" -> return Reply(true, "DATA$msg", info)
                else -> throw IOException("Unexpected fastboot reply: $s")
            }
        }
    }

    fun getvar(name: String): String? = command("getvar:$name").takeIf { it.ok }?.message

    /** getvar:all, returning the "key: value" INFO lines. */
    fun getvarAll(): List<String> = command("getvar:all", 60_000).info

    fun maxDownloadSize(): Long? = getvar("max-download-size")?.let { parseNumber(it) }

    /** Sends [size] bytes from [input] to the device's download buffer. */
    fun download(input: InputStream, size: Long, progress: (Long) -> Unit = {}): Reply {
        val r = command("download:%08x".format(size))
        if (!r.ok) return r
        if (!r.message.startsWith("DATA")) throw IOException("Device didn't accept the download: ${r.message}")
        val buf = ByteArray(512 * 1024)
        var sent = 0L
        while (sent < size) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
            if (n < 0) throw IOException("File ended early")
            pipe.write(buf, 0, n, 30_000)
            sent += n
            progress(sent)
        }
        return readReply(60_000) {}
    }

    /** download + flash:<partition>. Images larger than max-download-size need sparse splitting (not supported). */
    fun flash(partition: String, input: InputStream, size: Long, onInfo: (String) -> Unit, progress: (Long) -> Unit): Reply {
        val max = maxDownloadSize()
        if (max != null && size > max) {
            return Reply(false, "Image is ${size / 1048576} MB but the device accepts at most ${max / 1048576} MB in one download. " +
                "Split/sparse images aren't supported yet; use fastboot from a computer for this one.", emptyList())
        }
        val d = download(input, size, progress)
        if (!d.ok) return d
        return command("flash:$partition", 10 * 60_000, onInfo)
    }

    fun close() = pipe.close()

    companion object {
        fun parseNumber(s: String): Long? {
            val t = s.trim()
            return if (t.startsWith("0x", true)) t.drop(2).toLongOrNull(16) else t.toLongOrNull()
        }
    }
}
