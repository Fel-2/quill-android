package fel.quill.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineTest {

    private val inline = Regex(
        "\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|~~[^~\\n]+~~|`[^`\\n]+`|\\[\\[[^\\]\\n]+]]|!\\[[^\\]\\n]*]\\([^)\\n]*\\)|\\[[^\\]\\n]+]\\([^)\\n]*\\)|\\*[^*\\n]+\\*|_[^_\\n]+_",
    )

    private fun tokens(value: String): List<String> = inline.findAll(value).map { it.value }.toList()

    @Test
    fun `bold and italic are distinct tokens`() {
        assertEquals(listOf("**bold**"), tokens("**bold**"))
        assertEquals(listOf("*italic*"), tokens("*italic*"))
        assertEquals(listOf("**b**", "*i*"), tokens("**b** and *i*"))
    }

    @Test
    fun `bold is not misread as two italics`() {
        val found = tokens("**bold**")
        assertEquals(1, found.size)
        assertTrue(found.first().startsWith("**"))
    }

    @Test
    fun `code and strike and wikilink and md link`() {
        assertEquals(listOf("`code`"), tokens("a `code` b"))
        assertEquals(listOf("~~gone~~"), tokens("~~gone~~"))
        assertEquals(listOf("[[Other Note]]"), tokens("see [[Other Note]]"))
        assertEquals(listOf("[label](http://x)"), tokens("see [label](http://x) ok"))
    }

    @Test
    fun `image is matched before link`() {
        assertEquals(listOf("![alt](img.png)"), tokens("![alt](img.png)"))
    }

    @Test
    fun `markers spanning a newline do not match`() {
        assertEquals(emptyList<String>(), tokens("**unclosed\nacross lines**"))
    }

    @Test
    fun `plain text yields no tokens`() {
        assertEquals(emptyList<String>(), tokens("just a normal sentence"))
    }

    @Test
    fun `every token strips to its visible text`() {
        assertEquals("bold", "**bold**".removeSurrounding("**"))
        assertEquals("gone", "~~gone~~".removeSurrounding("~~"))
        assertEquals("code", "`code`".removeSurrounding("`"))
        assertEquals("Other Note", "[[Other Note]]".removeSurrounding("[[", "]]"))
    }
}
