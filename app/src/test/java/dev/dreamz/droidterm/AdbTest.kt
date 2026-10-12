package dev.dreamz.droidterm

import dev.dreamz.droidterm.adb.AdbKey
import dev.dreamz.droidterm.adb.AdbMessage
import dev.dreamz.droidterm.adb.AdbTools
import dev.dreamz.droidterm.adb.Fastboot
import dev.dreamz.droidterm.adb.ShellV2
import dev.dreamz.droidterm.ui.fastbootCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

class AdbTest {
    @Test fun keyCreatedAndReloaded() {
        val dir = Files.createTempDirectory("adbkey").toFile()
        val k1 = AdbKey.loadOrCreate(dir, "test@host")
        val k2 = AdbKey.loadOrCreate(dir, "test@host")
        assertEquals(k1.keyPair.public, k2.keyPair.public)
        assertTrue(dir.resolve("adbkey").readText().startsWith("-----BEGIN PRIVATE KEY-----"))
        assertTrue(dir.resolve("adbkey.pub").readText().endsWith(" test@host\n"))
    }

    @Test fun androidPublicKeyFormat() {
        val k = AdbKey.loadOrCreate(Files.createTempDirectory("adbkey").toFile(), "x")
        val pub = k.keyPair.public as RSAPublicKey
        val b = ByteBuffer.wrap(AdbKey.androidPublicKey(pub)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(524, b.capacity())
        assertEquals(64, b.int)
        val n0inv = b.int.toLong() and 0xffffffffL
        val n0 = pub.modulus.mod(BigInteger.ONE.shiftLeft(32)).toLong()
        assertEquals(0L, (n0 * n0inv + 1) and 0xffffffffL) // n0inv = -1/n[0] mod 2^32
        b.position(4 * (2 + 64 + 64))
        assertEquals(65537, b.int)
    }

    @Test fun signatureVerifiesAsSha1Rsa() {
        val k = AdbKey.loadOrCreate(Files.createTempDirectory("adbkey").toFile(), "x")
        val token = ByteArray(20) { it.toByte() }
        val sig = k.sign(token)
        // adbd verifies RSA_verify(NID_sha1, token, ...): PKCS#1 v1.5 with the token treated as the SHA-1 digest.
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.DECRYPT_MODE, k.keyPair.public)
        val decoded = c.doFinal(sig)
        assertArrayEquals(token, decoded.copyOfRange(decoded.size - 20, decoded.size))
        assertEquals(35, decoded.size)
    }

    @Test fun messageHeader() {
        val m = AdbMessage(AdbMessage.A_CNXN, AdbMessage.VERSION, 4096, "host::\u0000".toByteArray())
        val v = AdbMessage.parseHeader(m.header())
        assertEquals(AdbMessage.A_CNXN, v[0]); assertEquals(7, v[3]); assertEquals("CNXN", AdbMessage.name(v[0]))
    }

    @Test fun shellV2Decoder() {
        val d = ShellV2.Decoder()
        val a = ShellV2.packet(ShellV2.STDOUT, "hello".toByteArray())
        val b = ShellV2.packet(ShellV2.EXIT, byteArrayOf(3))
        val all = a + b
        val first = d.feed(all.copyOfRange(0, 7))
        assertTrue(first.isEmpty())
        val rest = d.feed(all.copyOfRange(7, all.size))
        assertEquals(2, rest.size)
        assertEquals("hello", String(rest[0].second)); assertEquals(ShellV2.EXIT, rest[1].first)
    }

    @Test fun devicesParsing() {
        val out = "* daemon started successfully\nList of devices attached\n127.0.0.1:37011 device product:oulu model:moto_g transport_id:1\nZY22 unauthorized usb:1-1 transport_id:2\n"
        val d = AdbTools.parseDevices(out)
        assertEquals(2, d.size)
        assertEquals("moto g", d[0].model); assertTrue(d[0].isNetwork)
        assertEquals("unauthorized", d[1].state)
    }

    @Test fun fastbootHelpers() {
        assertEquals("getvar:product", fastbootCommand("getvar product"))
        assertEquals("reboot-bootloader", fastbootCommand("reboot bootloader"))
        assertEquals("flashing unlock", fastbootCommand("flashing unlock"))
        assertEquals(0x10000000L, Fastboot.parseNumber("0x10000000"))
    }
}
