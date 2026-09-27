package fel.quill.android.data

object MarkdownFormat {

    enum class Action {
        H1, H2, H3, BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK, LINK, WIKILINK, BULLET, TODO, QUOTE, HR,
    }

    data class Result(val text: String, val selectionStart: Int, val selectionEnd: Int)

    fun apply(text: String, selectionStart: Int, selectionEnd: Int, action: Action): Result {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        return when (action) {
            Action.BOLD -> wrap(text, start, end, "**", "**", "bold text")
            Action.ITALIC -> wrap(text, start, end, "*", "*", "italic text")
            Action.STRIKE -> wrap(text, start, end, "~~", "~~", "struck text")
            Action.CODE -> wrap(text, start, end, "`", "`", "code")
            Action.WIKILINK -> wrap(text, start, end, "[[", "]]", "Note name")
            Action.LINK -> link(text, start, end)
            Action.CODE_BLOCK -> block(text, start, end, "```\n", "\n```")
            Action.QUOTE -> linePrefix(text, start, end, "> ")
            Action.BULLET -> linePrefix(text, start, end, "- ")
            Action.TODO -> linePrefix(text, start, end, "- [ ] ")
            Action.HR -> insertLine(text, start, end, "---")
            Action.H1 -> heading(text, start, end, 1)
            Action.H2 -> heading(text, start, end, 2)
            Action.H3 -> heading(text, start, end, 3)
        }
    }

    private fun wrap(text: String, start: Int, end: Int, open: String, close: String, placeholder: String): Result {
        val selected = text.substring(start, end)
        val outerStart = start - open.length
        val outerEnd = end + close.length
        if (selected.isNotEmpty() && outerStart >= 0 && outerEnd <= text.length && isWrapped(text, outerStart, outerEnd, open, close)) {
            val stripped = text.removeRange(end, outerEnd).removeRange(outerStart, start)
            return Result(stripped, outerStart, outerStart + selected.length)
        }
        if (selected.isNotEmpty() && isWrapped(text, start, end, open, close)) {
            val inner = text.substring(start + open.length, end - close.length)
            val stripped = text.removeRange(end - close.length, end).removeRange(start, start + open.length)
            return Result(stripped, start, start + inner.length)
        }
        val body = selected.ifEmpty { placeholder }
        val openEnd = start + open.length
        return Result(
            text.substring(0, start) + open + body + close + text.substring(end),
            openEnd,
            openEnd + body.length,
        )
    }

    private fun isWrapped(text: String, start: Int, end: Int, open: String, close: String): Boolean {
        if (end - start < open.length + close.length) return false
        return text.regionMatches(start, open, 0, open.length) && text.regionMatches(end - close.length, close, 0, close.length)
    }

    private fun link(text: String, start: Int, end: Int): Result {
        val label = text.substring(start, end).ifEmpty { "link text" }
        val replacement = "[$label](url)"
        val urlStart = start + 1 + label.length + 2
        return Result(text.substring(0, start) + replacement + text.substring(end), urlStart, urlStart + 3)
    }

    private fun heading(text: String, start: Int, end: Int, level: Int): Result {
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0 || start == 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val existing = line.takeWhile { it == '#' }.length.coerceAtMost(3)
        val bare = line.dropWhile { it == '#' }.trimStart()
        val prefix = if (existing == level) "" else "#".repeat(level) + " "
        val newLine = prefix + bare
        val updated = text.substring(0, lineStart) + newLine + text.substring(lineEnd)
        val cursor = lineStart + newLine.length
        return Result(updated, cursor, cursor)
    }

    private fun linePrefix(text: String, start: Int, end: Int, prefix: String): Result {
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0 || start == 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val block = text.substring(lineStart, lineEnd)
        val lines = block.split('\n')
        val allPrefixed = lines.all { it.startsWith(prefix) }
        val rewritten = lines.joinToString("\n") { line ->
            when {
                allPrefixed -> line.removePrefix(prefix)
                line.isBlank() -> prefix.trimEnd()
                else -> prefix + line
            }
        }
        val updated = text.substring(0, lineStart) + rewritten + text.substring(lineEnd)
        val delta = rewritten.length - block.length
        val cursor = (end + delta).coerceAtLeast(lineStart)
        return Result(updated, cursor, cursor)
    }

    private fun block(text: String, start: Int, end: Int, open: String, close: String): Result {
        val selected = text.substring(start, end)
        val openEnd = start + open.length
        return Result(
            text.substring(0, start) + open + selected + close + text.substring(end),
            openEnd,
            openEnd + selected.length,
        )
    }

    private fun insertLine(text: String, start: Int, end: Int, line: String): Result {
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val atLineStart = start == 0 || text.getOrNull(start - 1) == '\n'
        val insertion = if (atLineStart) start else lineEnd
        val prefix = if (atLineStart) "" else "\n"
        val suffix = if (atLineStart && insertion < text.length) "" else "\n"
        val replacement = prefix + line + suffix
        val updated = text.substring(0, insertion) + replacement + text.substring(insertion)
        val cursor = insertion + replacement.length
        return Result(updated, cursor, cursor)
    }
}
