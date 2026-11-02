package top.bangwu.taildeck.ui

import kotlin.math.abs

// What agent TUIs draw on screen and the chat needs: the choice list of a
// prompt waiting for the user, Claude's working status line, and Claude's
// greyed-out suggestion for the next prompt.

data class PromptOption(val number: String, val label: String, val selected: Boolean, val checked: Boolean?)

data class PromptContext(val title: String, val detail: List<String>, val command: String?, val notes: List<String>)

data class ScreenPrompt(
    val question: String?,
    val options: List<PromptOption>,
    val context: PromptContext?,
    /** Submit/Next row of a multi-select list; [row] counts the options above it. */
    val submit: Submit?,
    /** Row the TUI's cursor is on, when it draws one. */
    val cursor: Int?,
) {
    data class Submit(val label: String, val row: Int)

    fun rowOf(index: Int): Int = if (submit != null && index >= submit.row) index + 1 else index

    /** Arrow keys from the cursor to [row], or null when the cursor isn't drawn. */
    fun moveTo(row: Int): List<String>? {
        val c = cursor ?: return null
        val d = row - c
        return List(abs(d)) { if (d < 0) "up" else "down" }
    }

    fun isFreeText(index: Int): Boolean {
        val o = options[index]
        if (o.label.startsWith("Type something")) return true
        if (submit == null || o.checked == null) return false
        return index == options.indexOfLast { it.checked != null }
    }

    /** Same question and choices, wherever the cursor is. */
    fun sameQuestion(other: ScreenPrompt): Boolean =
        question == other.question && submit == other.submit &&
            options.map { Triple(it.number, it.label, it.checked) } == other.options.map { Triple(it.number, it.label, it.checked) }
}

private val FRAME = Regex("^[│┃║\\s]+|[│┃║\\s]+$")
private val OPTION = Regex("^([❯›>]\\s*)?(\\d{1,2})[.)]\\s+(.+)$")
private val CLOSING = Regex("^([❯›>]\\s*)?(Submit|Next)$")
private val CHECKBOX = Regex("^\\[([ xX✓✔☒])]\\s+")
private const val RULES = "─━═╭╮╰╯"
private const val BARS = "│┃║"

/**
 * Finds the last numbered choice list on screen, with checkboxes and a
 * Submit row for multi-select questions, the question above it and the
 * boxed context (command, notes) above that.
 */
fun parsePrompt(screen: String): ScreenPrompt? {
    var raw = screen.lines().takeLast(40)
    dividerColumn(raw)?.let { col -> raw = raw.map { prefixColumns(it, col) } }
    val lines = raw.map { it.replace(FRAME, "") }

    class Found(val start: Int, val options: List<PromptOption>, val submit: Triple<String, Int, Boolean>?)
    var best: Found? = null
    var current = listOf<PromptOption>()
    var submit: Triple<String, Int, Boolean>? = null
    var start = 0
    lines.forEachIndexed { i, line ->
        val close = CLOSING.find(line)
        if (close != null && current.isNotEmpty() && current.any { it.checked != null }) {
            submit = Triple(close.groupValues[2], current.size, close.groupValues[1].isNotEmpty())
            if (current.size >= 2) best = Found(start, current, submit)
            return@forEachIndexed
        }
        val m = OPTION.find(line) ?: return@forEachIndexed
        val n = m.groupValues[2]
        if (n == "1") {
            current = emptyList()
            submit = null
            start = i
        } else if (n != (current.size + 1).toString()) {
            current = emptyList()
            submit = null
            return@forEachIndexed
        }
        var label = m.groupValues[3]
        var checked: Boolean? = null
        CHECKBOX.find(label)?.let {
            checked = it.groupValues[1] != " "
            label = label.substring(it.value.length)
        }
        current = current + PromptOption(n, label.trim(), m.groupValues[1].isNotEmpty(), checked)
        if (current.size >= 2) best = Found(start, current, submit)
    }
    val found = best ?: return null
    val above = lines.subList(0, found.start)
    val question = above.lastOrNull { l -> l.isNotEmpty() && !l.all { it in RULES } }
    val q = if (question == null) -1 else above.lastIndexOf(question)
    val s = found.submit?.let { ScreenPrompt.Submit(it.first, it.second) }
    var prompt = ScreenPrompt(question, found.options, if (q >= 0) promptContext(raw, q) else null, s, null)
    val cursor = if (found.submit?.third == true) found.submit.second
    else found.options.indexOfFirst { it.selected }.takeIf { it >= 0 }?.let(prompt::rowOf)
    prompt = prompt.copy(cursor = cursor)
    return prompt
}

/** The boxed block above a question: a title, its detail, the command (quoted) and notes. */
private fun promptContext(raw: List<String>, q: Int): PromptContext? {
    var i = q - 1
    while (i >= 0 && q - i <= 30) {
        val t = raw[i].trim()
        if (t.isNotEmpty() && t.all { it in "─━═╭╮" }) break
        i--
    }
    if (i < 0 || q - i > 30) return null
    val boxed = raw[i].trim().startsWith("╭")
    class Block(val quoted: Boolean, val indented: Boolean, val lines: MutableList<String>)
    val blocks = mutableListOf<Block>()
    var open = false
    for (l in raw.subList(i + 1, q)) {
        var line = l
        if (boxed) {
            line = line.trim().removePrefix("│").removeSuffix("│")
        }
        val t = line.trim()
        if (t.isEmpty() || t.all { it in "╌┄┈╍" }) {
            open = false
            continue
        }
        val quoted = t.startsWith("│")
        val text = if (quoted) t.drop(1).trim() else t
        val last = blocks.lastOrNull()
        if (open && last != null && last.quoted == quoted) last.lines += text
        else {
            blocks += Block(quoted, line.startsWith("   "), mutableListOf(text))
            open = true
        }
    }
    val head = blocks.firstOrNull() ?: return null
    if (head.quoted || head.lines.isEmpty()) return null
    val title = head.lines[0]
    val join = { ls: List<String> -> ls.joinToString(if (title.contains("command", ignoreCase = true)) " " else "\n") }
    val detail = head.lines.drop(1).toMutableList()
    val notes = mutableListOf<String>()
    val rest = blocks.drop(1)
    val c = rest.indexOfFirst { it.quoted }
    val command: String
    when {
        c >= 0 -> {
            command = join(rest[c].lines)
            rest.take(c).forEach { detail += it.lines.joinToString(" ") }
            rest.drop(c + 1).forEach { notes += it.lines.joinToString(" ") }
        }
        // Claude Code indents the command under the title, boxed or not.
        rest.firstOrNull()?.indented == true -> {
            command = rest[0].lines[0]
            detail += rest[0].lines.drop(1)
            rest.drop(1).forEach { notes += it.lines.joinToString(" ") }
        }
        else -> return null
    }
    return PromptContext(title, detail, command, notes)
}

private fun cellWidth(cp: Int): Int = when {
    cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
        cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1FAFF || cp in 0x20000..0x3FFFD -> 2
    else -> 1
}

/** Column of a vertical divider most lines share, e.g. a question with a preview pane beside it. */
private fun dividerColumn(lines: List<String>): Int? {
    val counts = HashMap<Int, Int>()
    var rows = 0
    for (line in lines) {
        if (line.isBlank()) continue
        rows++
        var col = 0
        var text = false
        line.codePoints().forEach { cp ->
            val ch = String(Character.toChars(cp))
            if (ch in BARS) {
                if (text) counts[col] = (counts[col] ?: 0) + 1
            } else if (!Character.isWhitespace(cp)) text = true
            col += cellWidth(cp)
        }
    }
    val top = counts.maxByOrNull { it.value } ?: return null
    if (rows < 4 || top.value * 2 < rows) return null
    return top.key
}

private fun prefixColumns(line: String, columns: Int): String {
    val out = StringBuilder()
    var col = 0
    for (cp in line.codePoints().toArray()) {
        col += cellWidth(cp)
        if (col > columns) break
        out.appendCodePoint(cp)
    }
    return out.toString()
}

// ---- Claude's status line while it works: "✻ Thinking… (12s · ↑ 1.2k tokens · esc to interrupt)" ----

data class StatusLine(val text: String, val elapsedMs: Long?)

private const val SPINNERS = "·✢✳✶✻✽*•"
private val COUNTERS = Regex("\\s*\\(([^()]*)\\)?\\s*$")
private val DURATION = Regex("^\\s*(?:(\\d+)h\\s*)?(?:(\\d+)m\\s*)?(?:(\\d+)s)?")

fun parseStatusLine(screen: String): StatusLine? {
    val lines = screen.lines().map { it.trim(' ', '│', '\u00a0').substringBefore('│').trim(' ', '\u00a0') }
    val input = lines.indexOfLast { it.startsWith("❯") || it.startsWith("›") }
    if (input < 0) return null
    for (line in lines.subList(maxOf(0, input - 8), input).asReversed()) {
        if (line.isEmpty() || line.startsWith("⎿") || line.all { it in RULES }) continue
        val glyph = line.first()
        if (glyph !in SPINNERS) return null
        var text = line.drop(1).trim()
        if (glyph == '•' && "interrupt" !in text) return null
        var elapsed: Long? = null
        val m = COUNTERS.find(text)
        if (m != null && Regex("\\d+s|interrupt|tokens").containsMatchIn(m.groupValues[1])) {
            text = text.substring(0, m.range.first)
            DURATION.find(m.groupValues[1])?.takeIf { it.value.isNotBlank() }?.let { d ->
                val (h, mi, s) = d.destructured
                elapsed = ((h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
            }
        }
        return text.takeIf { it.isNotEmpty() }?.let { StatusLine(it, elapsed) }
    }
    return null
}

/** The dimmed text Claude Code shows in its empty input as the suggested next prompt. */
fun parseClaudeSuggestion(ansi: String): String? {
    val line = ansi.split(Regex("\r?\n|\r")).lastOrNull { it.startsWith("❯ ") || it.startsWith("❯\u00a0") } ?: return null
    val m = Regex("^(?:\u001b\\[0m)?\u001b\\[2m([^\u001b]+)\u001b\\[0m\\s*$").find(line.drop(2)) ?: return null
    return m.groupValues[1].trim().ifEmpty { null }
}

// ---- images an agent mentions by path ----

private val IMAGE_PATH = Regex("(?:^|[^A-Za-z0-9_./~\\\\-])(~?/(?!/)[^\\s`)\\]\"'<>，。；、！？）」》]+\\.(?:png|jpe?g|gif|webp))(?![A-Za-z0-9_-]|\\.[A-Za-z0-9])", RegexOption.IGNORE_CASE)

fun imagePathsIn(text: String): List<String> = IMAGE_PATH.findAll(text).map { it.groupValues[1] }.distinct().take(6).toList()
