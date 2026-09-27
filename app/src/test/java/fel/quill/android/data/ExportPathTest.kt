package fel.quill.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPathTest {

    private fun unsafePath(path: String): Boolean {
        val clean = path.replace('\\', '/')
        return clean.startsWith("/") || clean.split('/').any { it == ".." || it.isEmpty() }
    }

    @Test
    fun `traversal and absolute paths are rejected`() {
        val hostile = listOf(
            "../secret.md",
            "notes/../../escape.md",
            "/etc/passwd",
            "a//b.md",
            "..",
            "a/../../b.md",
        )
        hostile.forEach { assertTrue("expected unsafe: $it", unsafePath(it)) }
    }

    @Test
    fun `ordinary nested note paths are allowed`() {
        val legit = listOf(
            "Inbox.md",
            "work/Project.md",
            "Daily/2026-09-27.md",
            "a/b/c/deep.md",
        )
        legit.forEach { assertFalse("expected safe: $it", unsafePath(it)) }
    }

    @Test
    fun `windows separators normalize before the check`() {
        assertTrue(unsafePath("..\\secret.md"))
        assertFalse(unsafePath("work\\Project.md"))
    }

    @Test
    fun `a hostile filename is skipped, not fatal to the batch`() {
        val names = listOf("good1.md", "../../escape.md", "good2.md", "/abs.md", "good3.md")
        val accepted = names.filterNot(::unsafePath)
        assertEquals(listOf("good1.md", "good2.md", "good3.md"), accepted)
    }
}
