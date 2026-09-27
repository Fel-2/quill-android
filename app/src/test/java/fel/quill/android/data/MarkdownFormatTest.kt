package fel.quill.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import fel.quill.android.data.MarkdownFormat.Action

class MarkdownFormatTest {

    private fun run(text: String, start: Int, end: Int, action: Action) =
        MarkdownFormat.apply(text, start, end, action)

    @Test
    fun `bold wraps the selection and selects the inner text`() {
        val result = run("hello world", 6, 11, Action.BOLD)
        assertEquals("hello **world**", result.text)
        assertEquals("world", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `bold toggles back off`() {
        val once = run("hello world", 6, 11, Action.BOLD)
        val twice = MarkdownFormat.apply(once.text, once.selectionStart, once.selectionEnd, Action.BOLD)
        assertEquals("hello world", twice.text)
    }

    @Test
    fun `bold on an empty selection inserts a placeholder and selects it`() {
        val result = run("", 0, 0, Action.BOLD)
        assertEquals("**bold text**", result.text)
        assertEquals("bold text", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `italic and strike and code wrap correctly`() {
        assertEquals("a *b* c", run("a b c", 2, 3, Action.ITALIC).text)
        assertEquals("a ~~b~~ c", run("a b c", 2, 3, Action.STRIKE).text)
        assertEquals("a `b` c", run("a b c", 2, 3, Action.CODE).text)
    }

    @Test
    fun `wikilink wraps in double brackets`() {
        val result = run("see note", 4, 8, Action.WIKILINK)
        assertEquals("see [[note]]", result.text)
    }

    @Test
    fun `link keeps the label and selects the url placeholder`() {
        val result = run("click here now", 6, 10, Action.LINK)
        assertEquals("click [here](url) now", result.text)
        assertEquals("url", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `heading prefixes the current line only`() {
        val result = run("first\nsecond\nthird", 7, 7, Action.H2)
        assertEquals("first\n## second\nthird", result.text)
    }

    @Test
    fun `heading replaces an existing level rather than stacking`() {
        val result = run("### title", 5, 5, Action.H1)
        assertEquals("# title", result.text)
    }

    @Test
    fun `bullets apply to every selected line and toggle off`() {
        val once = run("one\ntwo", 0, 7, Action.BULLET)
        assertEquals("- one\n- two", once.text)
        val twice = MarkdownFormat.apply(once.text, 0, once.text.length, Action.BULLET)
        assertEquals("one\ntwo", twice.text)
    }

    @Test
    fun `todo prefix applies to a line`() {
        assertEquals("- [ ] buy milk", run("buy milk", 3, 3, Action.TODO).text)
    }

    @Test
    fun `quote prefixes a line`() {
        assertEquals("> quoted", run("quoted", 0, 0, Action.QUOTE).text)
    }

    @Test
    fun `code block wraps across lines`() {
        val result = run("x = 1", 0, 5, Action.CODE_BLOCK)
        assertEquals("```\nx = 1\n```", result.text)
    }

    @Test
    fun `horizontal rule inserts on its own line`() {
        val result = run("before\nafter", 3, 3, Action.HR)
        assertTrue(result.text.contains("---\n"))
        assertTrue(result.text.startsWith("before\n"))
    }

    @Test
    fun `out of range selections are clamped, not crash`() {
        val result = MarkdownFormat.apply("abc", 99, 200, Action.BOLD)
        assertTrue(result.text.isNotEmpty())
        assertTrue(result.selectionStart <= result.text.length)
        assertTrue(result.selectionEnd <= result.text.length)
    }

    @Test
    fun `partial markers are not treated as a wrap`() {
        val result = run("**bold", 0, 6, Action.BOLD)
        assertEquals("****bold**", result.text)
    }

    @Test
    fun `applying every action never throws`() {
        val sample = "# Title\n\n- [ ] a task\nsome *text*\n\n> quote\n"
        Action.entries.forEach { action ->
            val result = MarkdownFormat.apply(sample, 2, 12, action)
            assertTrue("selection bounds for $action", result.selectionStart in 0..result.text.length)
            assertTrue("selection bounds for $action", result.selectionEnd in result.selectionStart..result.text.length)
        }
    }
}
