package dev.dreamz.droidterm.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Raw byte pipe to a device (USB bulk endpoints). */
interface AdbPipe {
    fun write(data: ByteArray, offset: Int, length: Int)
    /** Reads exactly [length] bytes. */
    fun readFully(data: ByteArray, length: Int)
    fun close()
}

class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = EMPTY) {
    fun header(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(command); putInt(arg0); putInt(arg1); putInt(payload.size); putInt(checksum(payload)); putInt(command.inv())
    }.array()

    override fun toString() = "${name(command)}($arg0, $arg1, ${payload.size}b)"

    companion object {
        const val HEADER_SIZE = 24
        val EMPTY = ByteArray(0)
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534c5453
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        const val VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024

        fun checksum(p: ByteArray): Int { var s = 0; for (b in p) s += b.toInt() and 0xff; return s }

        fun parseHeader(h: ByteArray): IntArray {
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val v = IntArray(6) { bb.int }
            if (v[5] != v[0].inv()) throw IOException("Bad adb message magic")
            return v
        }

        fun name(c: Int) = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c).array())
    }
}

/**
 * Minimal adb host-side protocol (what `adb` does over USB): CNXN + RSA AUTH, then streams opened with OPEN.
 * One reader thread demultiplexes device messages to streams.
 */
class AdbConnection(private val pipe: AdbPipe, private val key: AdbKey, private val keyName: String) {
    var banner: String = ""; private set
    var maxPayload = AdbMessage.MAX_PAYLOAD; private set
    val features: Set<String> get() = Regex("features=([^;]*)").find(banner)?.groupValues?.get(1)?.split(',')?.toSet() ?: emptySet()

    private val writeLock = Any()
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closed = false

    fun send(m: AdbMessage) = synchronized(writeLock) {
        val h = m.header()
        pipe.write(h, 0, h.size)
        if (m.payload.isNotEmpty()) pipe.write(m.payload, 0, m.payload.size)
    }

    private fun receive(): AdbMessage {
        val h = ByteArray(AdbMessage.HEADER_SIZE)
        pipe.readFully(h, h.size)
        val v = AdbMessage.parseHeader(h)
        val len = v[3]
        if (len < 0 || len > 16 * 1024 * 1024) throw IOException("Bad adb payload length $len")
        val p = if (len > 0) ByteArray(len).also { pipe.readFully(it, len) } else AdbMessage.EMPTY
        return AdbMessage(v[0], v[1], v[2], p)
    }

    /**
     * Handshake. [onWaitingForUser] is called when the device has to show "Allow USB debugging?" for our key.
     */
    fun connect(onWaitingForUser: () -> Unit) {
        send(AdbMessage(AdbMessage.A_CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::droidterm\u0000".toByteArray()))
        var signed = false
        while (true) {
            val m = receive()
            when (m.command) {
                AdbMessage.A_AUTH -> {
                    if (m.arg0 != AdbMessage.AUTH_TOKEN) continue
                    if (!signed) {
                        signed = true
                        send(AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_SIGNATURE, 0, key.sign(m.payload)))
                    } else {
                        send(AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload(keyName)))
                        onWaitingForUser()
                    }
                }
                AdbMessage.A_CNXN -> {
                    maxPayload = minOf(m.arg1, AdbMessage.MAX_PAYLOAD)
                    banner = String(m.payload).trimEnd('\u0000')
                    break
                }
                AdbMessage.A_STLS -> throw IOException("Device asked for TLS; use Wireless debugging pairing instead")
                else -> {}
            }
        }
        Thread({ readLoop() }, "AdbUsbReader").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val m = receive()
                val s = streams[m.arg1]
                when (m.command) {
                    AdbMessage.A_OKAY -> s?.onOkay(m.arg0)
                    AdbMessage.A_WRTE -> {
                        if (s != null) {
                            s.onData(m.payload)
                            send(AdbMessage(AdbMessage.A_OKAY, s.localId, s.remoteId))
                        }
                    }
                    AdbMessage.A_CLSE -> {
                        if (s != null) { streams.remove(s.localId); s.onClosed() }
                    }
                }
            }
        } catch (e: Exception) {
            closed = true
        } finally {
            streams.values.forEach { it.onClosed() }
            streams.clear()
        }
    }

    /** Opens a service like "shell:" or "shell,v2,pty:ls". Blocks until the device accepts or refuses. */
    fun open(destination: String): AdbStream {
        val s = AdbStream(this, nextId.getAndIncrement())
        streams[s.localId] = s
        send(AdbMessage(AdbMessage.A_OPEN, s.localId, 0, (destination + "\u0000").toByteArray()))
        if (!s.awaitOpen(15)) { streams.remove(s.localId); throw IOException("Device refused \"$destination\"") }
        return s
    }

    fun close() {
        closed = true
        runCatching { pipe.close() }
    }
}

/** One adb stream. Writes wait for the device's OKAY before the next WRTE, as the protocol requires. */
class AdbStream internal constructor(private val conn: AdbConnection, val localId: Int) {
    @Volatile var remoteId = 0; private set
    private val opened = Semaphore(0)
    private val canWrite = Semaphore(0)
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile var isClosed = false; private set

    internal fun onOkay(remote: Int) {
        if (remoteId == 0) { remoteId = remote; opened.release(); canWrite.release() } else canWrite.release()
    }
    internal fun onData(b: ByteArray) { queue.put(b) }
    internal fun onClosed() {
        if (isClosed) return
        isClosed = true
        opened.release(); canWrite.release(); queue.put(ByteArray(0))
    }
    internal fun awaitOpen(sec: Long) = opened.tryAcquire(sec, TimeUnit.SECONDS) && !isClosed

    fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var pos = off
        while (pos < off + len) {
            val n = minOf(conn.maxPayload, off + len - pos)
            canWrite.acquire()
            if (isClosed) throw IOException("Stream closed")
            conn.send(AdbMessage(AdbMessage.A_WRTE, localId, remoteId, b.copyOfRange(pos, pos + n)))
            pos += n
        }
    }

    /** Next chunk from the device, or null at end of stream. */
    fun read(): ByteArray? {
        if (isClosed && queue.isEmpty()) return null
        val b = queue.take()
        return if (b.isEmpty()) null else b
    }

    fun close() {
        if (!isClosed) runCatching { conn.send(AdbMessage(AdbMessage.A_CLSE, localId, remoteId)) }
        onClosed()
    }

    val inputStream: InputStream = object : InputStream() {
        private var cur: ByteArray? = null
        private var pos = 0
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (cur == null || pos >= cur!!.size) {
                cur = this@AdbStream.read() ?: return -1
                pos = 0
            }
            val n = minOf(len, cur!!.size - pos)
            System.arraycopy(cur!!, pos, b, off, n)
            pos += n
            return n
        }
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = this@AdbStream.write(b, off, len)
    }
}

/**
 * adb "shell,v2" framing: [id:1][len:4 LE][data]. id 0 stdin, 1 stdout, 2 stderr, 3 exit, 4 close-stdin,
 * 5 window-size-change ("ROWSxCOLS,XPIXxYPIX").
 */
object ShellV2 {
    const val STDIN = 0
    const val STDOUT = 1
    const val STDERR = 2
    const val EXIT = 3
    const val WINDOW = 5

    fun packet(id: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(data.size).put(data).array()

    fun windowSize(cols: Int, rows: Int) = packet(WINDOW, "${rows}x$cols,0x0\u0000".toByteArray())

    /** Incremental decoder: feed raw stream bytes, get (id, data) packets out. */
    class Decoder {
        private var buf = ByteArray(0)
        fun feed(chunk: ByteArray): List<Pair<Int, ByteArray>> {
            buf += chunk
            val out = ArrayList<Pair<Int, ByteArray>>()
            var p = 0
            while (buf.size - p >= 5) {
                val len = ByteBuffer.wrap(buf, p + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (buf.size - p - 5 < len) break
                out += buf[p].toInt() to buf.copyOfRange(p + 5, p + 5 + len)
                p += 5 + len
            }
            buf = buf.copyOfRange(p, buf.size)
            return out
        }
    }
}
