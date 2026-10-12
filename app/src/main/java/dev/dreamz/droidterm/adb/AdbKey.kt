package dev.dreamz.droidterm.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * The adb host key, shared by the bundled adb binary ($HOME/.android/adbkey, PKCS#8 PEM, the same format adb
 * writes) and DroidTerm's own USB adb client, so a device that trusts one trusts the other.
 */
class AdbKey private constructor(val keyPair: KeyPair) {
    private val pub get() = keyPair.public as RSAPublicKey

    /** AUTH_SIGNATURE payload: the token signed like OpenSSL's RSA_sign(NID_sha1) (token used as the digest). */
    fun sign(token: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return c.doFinal(SHA1_DIGEST_INFO + token)
    }

    /** AUTH_RSAPUBLICKEY payload: base64 of Android's RSAPublicKey struct, a space, a name, NUL. */
    fun publicKeyPayload(name: String): ByteArray = (publicKeyLine(name)).toByteArray() + 0

    fun publicKeyLine(name: String): String = Base64.getEncoder().encodeToString(androidPublicKey(pub)) + " " + name

    companion object {
        private const val KEY_WORDS = 64 // 2048-bit modulus in 32-bit words

        // ASN.1 DigestInfo prefix for SHA-1.
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** Loads $dir/adbkey, creating it (and adbkey.pub) if missing. */
        fun loadOrCreate(dir: File, name: String): AdbKey {
            dir.mkdirs()
            val priv = File(dir, "adbkey")
            if (priv.isFile) return AdbKey(readPem(priv.readText()))
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
            priv.writeText(pem)
            priv.setReadable(false, false); priv.setReadable(true, true)
            val key = AdbKey(kp)
            File(dir, "adbkey.pub").writeText(key.publicKeyLine(name) + "\n")
            return key
        }

        fun readPem(pem: String): KeyPair {
            val body = pem.lines().filterNot { it.startsWith("-----") }.joinToString("")
            val der = Base64.getDecoder().decode(body)
            require(pem.contains("BEGIN PRIVATE KEY")) { "adbkey is not a PKCS#8 key" }
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
            val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
            return KeyPair(pub, priv)
        }

        /** Android's mincrypt RSAPublicKey: len, n0inv, n[64], rr[64], e — all little-endian uint32. */
        fun androidPublicKey(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            require(n.bitLength() <= KEY_WORDS * 32) { "adb keys must be 2048-bit" }
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
            val rr = BigInteger.ONE.shiftLeft(KEY_WORDS * 32 * 2).mod(n)
            val buf = ByteBuffer.allocate(4 * (3 + 2 * KEY_WORDS)).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(KEY_WORDS)
            buf.putInt(n0inv.toInt())
            putWords(buf, n)
            putWords(buf, rr)
            buf.putInt(pub.publicExponent.toInt())
            return buf.array()
        }

        private fun putWords(buf: ByteBuffer, v: BigInteger) {
            val mask = BigInteger.valueOf(0xffffffffL)
            var x = v
            repeat(KEY_WORDS) {
                buf.putInt(x.and(mask).toLong().toInt())
                x = x.shiftRight(32)
            }
        }
    }
}
