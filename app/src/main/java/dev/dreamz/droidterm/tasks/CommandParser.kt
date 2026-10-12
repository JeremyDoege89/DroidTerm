package dev.dreamz.droidterm.tasks

/**
 * Turns text copied from a web page / README into a list of commands.
 *
 * - Shell prompts are stripped ("$ ", "# " root prompts when the block uses prompts, "user@host:~$ ", "PS> ", "> ").
 * - When some lines carry a prompt, lines without one are treated as command output and dropped.
 * - Lines ending in a backslash are joined with the next line; heredocs (<<EOF ... EOF) stay one command.
 * - "#" comment lines become notes (or are dropped when keepNotes = false); blank lines are skipped.
 * - Typographic quotes/dashes and non-breaking spaces that web pages substitute are turned back into ASCII.
 */
object CommandParser {

    data class Item(val text: String, val isNote: Boolean)

    private val userHostPrompt = Regex("""^[\w.\-]+@[\w.\-]+(:[^\s$#]*)?\s*[$#]\s+""")
    private val dollarPrompt = Regex("""^\$\s+""")
    private val psPrompt = Regex("""^PS( [A-Za-z]:[^>]*)?>\s+""")
    private val gtPrompt = Regex("""^>\s+""")
    private val rootPrompt = Regex("""^#\s+""")
    private val heredoc = Regex("""<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1""")
    private val listMarker = Regex("""^(\d+[.)]|[-*•])\s+""")

    fun normalize(text: String): String = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace(' ', ' ').replace("​", "").replace("﻿", "")
        .replace('“', '"').replace('”', '"').replace('„', '"')
        .replace('‘', '\'').replace('’', '\'')
        .replace('–', '-').replace('—', '-').replace('−', '-')

    private fun promptOf(line: String): Regex? =
        listOf(userHostPrompt, dollarPrompt, psPrompt).firstOrNull { it.containsMatchIn(line) }

    fun parse(raw: String, keepNotes: Boolean = true): List<Item> {
        val lines = normalize(raw).lines()
        val trimmedLines = lines.map { it.trim() }
        val usesPrompts = trimmedLines.any { promptOf(it) != null }
        val out = ArrayList<Item>()
        var i = 0
        while (i < lines.size) {
            var line = trimmedLines[i]
            i++
            if (line.isEmpty()) continue

            var hadPrompt = false
            val p = promptOf(line)
            if (p != null) {
                line = line.replaceFirst(p, ""); hadPrompt = true
            } else if (usesPrompts && rootPrompt.containsMatchIn(line) && looksLikeCommand(line.replaceFirst(rootPrompt, ""))) {
                // "# apt update" inside a block that uses prompts is a root prompt, not a comment.
                line = line.replaceFirst(rootPrompt, ""); hadPrompt = true
            }

            if (!hadPrompt && line.startsWith("#")) {
                if (keepNotes) {
                    val note = line.trimStart('#').trim()
                    if (note.isNotEmpty()) out += Item(note, true)
                }
                continue
            }
            if (usesPrompts && !hadPrompt) continue // output shown between commands
            if (!hadPrompt) line = line.replaceFirst(listMarker, "").replaceFirst(gtPrompt, "")
            if (line.isEmpty()) continue

            val sb = StringBuilder(line)
            // Backslash continuations (the continuation lines may carry a "> " PS2 prompt).
            while (sb.endsWith("\\") && i < lines.size) {
                sb.setLength(sb.length - 1)
                while (sb.endsWith(" ")) sb.setLength(sb.length - 1)
                sb.append(" ").append(trimmedLines[i].replaceFirst(gtPrompt, "").trim())
                i++
            }
            // Heredoc: keep raw lines until the terminator.
            heredoc.find(sb)?.let { m ->
                val terminator = m.groupValues[2]
                while (i < lines.size) {
                    val body = lines[i].replaceFirst(Regex("""^\s*>\s"""), "")
                    sb.append("\n").append(body)
                    i++
                    if (body.trim() == terminator) break
                }
            }
            out += Item(sb.toString().trimEnd(), false)
        }
        return out
    }

    private fun looksLikeCommand(s: String): Boolean {
        val first = s.substringBefore(' ')
        return first.isNotEmpty() && first.all { it.isLetterOrDigit() || it in "._-/" } && first.first().isLowerCase()
    }

    /** A name for the task from the first note line, if the text starts with one. */
    fun suggestName(items: List<Item>): String? = items.firstOrNull()?.takeIf { it.isNote }?.text?.take(60)
}
