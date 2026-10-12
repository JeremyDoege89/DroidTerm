package dev.dreamz.droidterm

import dev.dreamz.droidterm.ssh.SshKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeysTest {
    @Test fun ed25519RoundTrip() {
        val m = SshKeys.generate(SshKeys.Type.ED25519, "me@phone", null)
        assertTrue(m.publicKey, m.publicKey.startsWith("ssh-ed25519 "))
        assertTrue(m.publicKey.endsWith(" me@phone"))
        val again = SshKeys.import(m.privateKey, null, "me@phone")
        assertEquals(m.publicKey, again.publicKey)
    }

    @Test fun rsaWithPassphrase() {
        val m = SshKeys.generate(SshKeys.Type.RSA, "c", "secret")
        assertTrue(m.publicKey.startsWith("ssh-rsa "))
        val again = SshKeys.import(m.privateKey, "secret", "c")
        assertEquals(m.fingerprint, again.fingerprint)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongPassphrase() {
        val m = SshKeys.generate(SshKeys.Type.ECDSA, "c", "secret")
        SshKeys.import(m.privateKey, "nope", "c")
    }
}
