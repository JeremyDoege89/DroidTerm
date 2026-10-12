package dev.dreamz.droidterm

import dev.dreamz.droidterm.scripts.ScriptWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptWriterTest {
    @Test fun parsesReplyFormat() {
        val d = ScriptWriter.parse("NAME: Mount SD card\nROOT: yes\n```sh\nmount /dev/block/x /mnt\necho ok\n```\nNOTES: Needs root.\nReboot after.")
        assertEquals("Mount SD card", d.name)
        assertTrue(d.needsRoot)
        assertEquals("mount /dev/block/x /mnt\necho ok", d.script)
        assertEquals("Needs root.\nReboot after.", d.notes)
    }

    @Test fun fallsBackToWholeText() {
        val d = ScriptWriter.parse("echo hi")
        assertEquals("echo hi", d.script)
        assertEquals(false, d.needsRoot)
    }
}
