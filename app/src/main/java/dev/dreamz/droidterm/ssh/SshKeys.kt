package dev.dreamz.droidterm.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/** SSH key generation and import with JSch (ed25519 via Bouncy Castle). */
object SshKeys {
    data class Material(val type: String, val publicKey: String, val fingerprint: String, val privateKey: ByteArray)

    enum class Type(val label: String, val jschType: Int, val bits: Int) {
        ED25519("Ed25519 (recommended)", KeyPair.ED25519, 256),
        RSA("RSA 4096", KeyPair.RSA, 4096),
        ECDSA("ECDSA P-256", KeyPair.ECDSA, 256),
    }

    init { JschSetup.ensure() }

    fun generate(type: Type, comment: String, passphrase: String?): Material {
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, type.jschType, type.bits)
        try {
            val prv = ByteArrayOutputStream()
            // OpenSSH's own format (what ssh-keygen writes); the only one JSch can write for Ed25519.
            if (passphrase.isNullOrEmpty()) kp.writeOpenSSHv1PrivateKey(prv, null) else kp.writeOpenSSHv1PrivateKey(prv, passphrase.toByteArray(), "aes256-ctr")
            return Material(typeName(kp), publicLine(kp, comment), kp.fingerPrint ?: "", prv.toByteArray())
        } finally {
            kp.dispose()
        }
    }

    /**
     * Imports a private key (OpenSSH, PEM or PuTTY format). Throws if it can't be parsed or the passphrase is wrong.
     */
    fun import(privateKey: ByteArray, passphrase: String?, comment: String): Material {
        val kp = KeyPair.load(JSch(), privateKey, null)
        try {
            if (kp.isEncrypted) {
                require(!passphrase.isNullOrEmpty()) { "This key is protected; enter its passphrase" }
                require(kp.decrypt(passphrase)) { "Wrong passphrase" }
            }
            return Material(typeName(kp), publicLine(kp, comment), kp.fingerPrint ?: "", privateKey)
        } finally {
            kp.dispose()
        }
    }

    private fun publicLine(kp: KeyPair, comment: String): String {
        val out = ByteArrayOutputStream()
        kp.writePublicKey(out, comment)
        return out.toString().trim()
    }

    private fun typeName(kp: KeyPair) = kp.keyTypeString ?: "key"
}

/** JSch configuration shared by key handling and connections. */
object JschSetup {
    @Volatile private var done = false

    fun ensure() {
        if (done) return
        synchronized(this) {
            if (done) return
            // Android's built-in crypto has no EdDSA; use Bouncy Castle's implementations bundled with JSch.
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("chacha20-poly1305@openssh.com", "com.jcraft.jsch.bc.ChaCha20Poly1305")
            JSch.setConfig("StrictHostKeyChecking", "accept-new")
            done = true
        }
    }
}
