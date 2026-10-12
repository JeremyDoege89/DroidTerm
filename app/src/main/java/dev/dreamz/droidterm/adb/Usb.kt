package dev.dreamz.droidterm.adb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.termux.terminal.TerminalSession
import dev.dreamz.droidterm.term.TermEnv
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

enum class UsbMode { ADB, FASTBOOT, OTHER }

data class UsbTarget(val device: UsbDevice, val mode: UsbMode, val iface: UsbInterface?) {
    val label: String get() = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { device.deviceName }
    val serial: String? get() = runCatching { device.serialNumber }.getOrNull()
}

object Usb {
    private const val CLASS_VENDOR = 0xff
    private const val SUBCLASS_ADB = 0x42
    private const val PROTO_ADB = 0x01
    private const val PROTO_FASTBOOT = 0x03

    fun list(context: Context): List<UsbTarget> {
        val um = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return um.deviceList.values.map { classify(it) }
    }

    fun classify(d: UsbDevice): UsbTarget {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (itf.interfaceClass == CLASS_VENDOR && itf.interfaceSubclass == SUBCLASS_ADB) {
                when (itf.interfaceProtocol) {
                    PROTO_ADB -> return UsbTarget(d, UsbMode.ADB, itf)
                    PROTO_FASTBOOT -> return UsbTarget(d, UsbMode.FASTBOOT, itf)
                }
            }
        }
        return UsbTarget(d, UsbMode.OTHER, null)
    }

    /** Opens the bulk endpoints of [t]'s interface. Needs USB permission for the device. */
    fun open(context: Context, t: UsbTarget): UsbBulkPipe {
        val um = context.getSystemService(UsbManager::class.java)
        val itf = t.iface ?: throw IOException("Not an adb/fastboot device")
        if (!um.hasPermission(t.device)) throw IOException("No USB permission for ${t.label}")
        val conn = um.openDevice(t.device) ?: throw IOException("Couldn't open ${t.label}")
        if (!conn.claimInterface(itf, true)) { conn.close(); throw IOException("Couldn't claim the USB interface") }
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        if (epIn == null || epOut == null) { conn.close(); throw IOException("No bulk endpoints") }
        return UsbBulkPipe(conn, itf, epIn, epOut)
    }
}

class UsbBulkPipe(
    private val conn: UsbDeviceConnection,
    private val itf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : AdbPipe {
    private val chunk = 16 * 1024
    private var readBuf = ByteArray(64 * 1024)
    private var readLen = 0
    private var readPos = 0
    @Volatile private var closed = false

    override fun write(data: ByteArray, offset: Int, length: Int) = write(data, offset, length, 5000)

    fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val n = minOf(chunk, end - pos)
            val w = conn.bulkTransfer(epOut, data, pos, n, timeoutMs)
            if (w < 0) throw IOException("USB write failed")
            pos += w
        }
        // Like adb: a transfer that is an exact multiple of the packet size ends with a zero-length packet.
        if (length > 0 && length % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, ByteArray(0), 0, timeoutMs)
    }

    /** One bulk IN transfer into [buf]; returns bytes read. Used by fastboot, which is packet-oriented. */
    fun readPacket(buf: ByteArray, timeoutMs: Int): Int {
        val n = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs)
        if (n < 0) throw IOException(if (closed) "USB closed" else "USB read failed or timed out")
        return n
    }

    override fun readFully(data: ByteArray, length: Int) {
        var got = 0
        while (got < length) {
            if (readPos >= readLen) {
                // Always ask for a whole buffer so a device packet is never larger than the request.
                var n: Int
                do {
                    if (closed) throw IOException("USB closed")
                    n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 1000)
                } while (n < 0 && !closed && stillAttached())
                if (n < 0) throw IOException("USB device gone")
                readLen = n; readPos = 0
                continue
            }
            val n = minOf(length - got, readLen - readPos)
            System.arraycopy(readBuf, readPos, data, got, n)
            readPos += n; got += n
        }
    }

    // bulkTransfer returns -1 both for timeouts and for an unplugged device; a fresh descriptor read tells them apart.
    private fun stillAttached() = conn.rawDescriptors != null

    override fun close() {
        closed = true
        runCatching { conn.releaseInterface(itf) }
        conn.close()
    }
}

/** Terminal tab backend: an interactive adb shell on a phone plugged in over USB OTG. */
class UsbAdbBackend(private val context: Context, private val target: UsbTarget) : TerminalSession.StreamBackend {
    private var conn: AdbConnection? = null
    private var stream: AdbStream? = null
    private var v2 = false
    @Volatile private var exit = 0
    private var greeting = ByteArray(0)

    override fun connect(columns: Int, rows: Int) {
        val pipe = Usb.open(context, target)
        val key = AdbKey.loadOrCreate(TermEnv.androidDir, keyName())
        val c = AdbConnection(pipe, key, keyName())
        conn = c
        val waiting = "\r\nCheck the other phone: allow USB debugging from this device.\r\n"
        c.connect(onWaitingForUser = { greetingNotice(waiting) })
        v2 = "shell_v2" in c.features
        val dest = if (v2) "shell,v2,TERM=xterm-256color,pty:" else "shell:"
        val s = c.open(dest)
        stream = s
        if (v2) s.write(ShellV2.windowSize(columns, rows))
        val product = Regex("ro.product.model=([^;]*)").find(c.banner)?.groupValues?.get(1) ?: target.label
        greeting = "Connected to $product over USB${if (v2) "" else " (legacy shell: no resize)"}\r\n".toByteArray()
    }

    private fun greetingNotice(s: String) {
        // Shown before the shell starts: written into the same stream the terminal reads.
        pendingNotice += s.toByteArray()
    }

    @Volatile private var pendingNotice = ByteArray(0)

    override fun getInputStream(): InputStream {
        val s = stream ?: throw IOException("Not connected")
        val raw = s.inputStream
        val body: InputStream = if (!v2) raw else object : InputStream() {
            val dec = ShellV2.Decoder()
            var cur = ByteArray(0); var pos = 0
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (pos >= cur.size) {
                    val chunk = s.read() ?: return -1
                    val out = java.io.ByteArrayOutputStream()
                    for ((id, data) in dec.feed(chunk)) when (id) {
                        ShellV2.STDOUT, ShellV2.STDERR -> out.write(data)
                        ShellV2.EXIT -> exit = data.firstOrNull()?.toInt()?.and(0xff) ?: 0
                    }
                    cur = out.toByteArray(); pos = 0
                }
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n); pos += n
                return n
            }
        }
        return SequenceInputStream(ByteArrayInputStream(pendingNotice + greeting), body)
    }

    override fun getOutputStream(): OutputStream {
        val s = stream ?: throw IOException("Not connected")
        if (!v2) return s.outputStream
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) = s.write(ShellV2.packet(ShellV2.STDIN, b.copyOfRange(off, off + len)))
        }
    }

    override fun resize(columns: Int, rows: Int) {
        val s = stream ?: return
        if (v2) Thread { runCatching { s.write(ShellV2.windowSize(columns, rows)) } }.start()
    }

    override fun close() {
        runCatching { stream?.close() }
        runCatching { conn?.close() }
    }

    override fun exitStatus() = exit

    companion object {
        fun keyName() = "droidterm@${Build.MODEL.replace(' ', '_')}"
    }
}
