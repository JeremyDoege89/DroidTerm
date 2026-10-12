package dev.dreamz.droidterm

import dev.dreamz.droidterm.tasks.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {
    private fun cmds(s: String, notes: Boolean = false) = CommandParser.parse(s, notes).filter { !it.isNote }.map { it.text }

    @Test fun plainLines() {
        assertEquals(listOf("adb devices", "adb reboot bootloader", "fastboot flash boot boot.img"),
            cmds("adb devices\n\nadb reboot bootloader\r\nfastboot flash boot boot.img\n"))
    }

    @Test fun promptsStrippedAndOutputDropped() {
        val text = """
            $ uname -a
            Linux phone 5.10.0 aarch64
            $ ls -la
            total 0
            user@box:~/src$ make -j8
        """.trimIndent()
        assertEquals(listOf("uname -a", "ls -la", "make -j8"), cmds(text))
    }

    @Test fun rootPromptInPromptBlock() {
        assertEquals(listOf("whoami", "apt update"), cmds("$ whoami\n# apt update"))
    }

    @Test fun commentsBecomeNotes() {
        val items = CommandParser.parse("# Install deps\nsudo apt install git\n# Clone\ngit clone x", true)
        assertEquals(listOf(true, false, true, false), items.map { it.isNote })
        assertEquals("Install deps", CommandParser.suggestName(items))
        assertEquals(listOf("sudo apt install git", "git clone x"), cmds("# Install deps\nsudo apt install git\n# Clone\ngit clone x"))
    }

    @Test fun backslashContinuation() {
        assertEquals(listOf("docker run -it --rm -v /a:/b image"), cmds("docker run -it \\\n  --rm \\\n  -v /a:/b image"))
    }

    @Test fun heredocKeptTogether() {
        val text = "cat > /tmp/x <<'EOF'\nline one\n  indented\nEOF\necho done"
        assertEquals(listOf("cat > /tmp/x <<'EOF'\nline one\n  indented\nEOF", "echo done"), cmds(text))
    }

    @Test fun webTypographyFixed() {
        assertEquals(listOf("echo \"hi\" --flag"), cmds("echo \u201Chi\u201D \u2014flag\u00A0".replace("\u2014", "-\u2014")))
    }

    @Test fun numberedListMarkers() {
        assertEquals(listOf("adb devices", "adb shell"), cmds("1. adb devices\n2) adb shell"))
    }
}
