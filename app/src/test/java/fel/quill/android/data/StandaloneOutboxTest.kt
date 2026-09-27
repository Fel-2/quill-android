package fel.quill.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class StandaloneOutboxTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    private fun outbox() = OfflineOutbox(File(folder.root, "outbox.json"))

    @Test
    fun `pending write survives reload`() {
        val box = outbox()
        box.putWrite(PendingWrite("Inbox.md", "- [ ] task", null, true))
        assertEquals(1, box.pendingCount())

        val reloaded = outbox()
        assertEquals(1, reloaded.pendingCount())
        assertEquals("- [ ] task", reloaded.writeFor("Inbox.md")?.content)
    }

    @Test
    fun `clear removes writes deletes and conflicts`() {
        val box = outbox()
        box.putWrite(PendingWrite("a.md", "x", null, true))
        box.putDelete(PendingDelete("b.md", "etag", "old"))
        box.markConflict("c.md", "mine", "their-etag")
        assertTrue(box.pendingCount() > 0)
        assertTrue(box.conflictCount() > 0)

        box.clear()

        assertEquals(0, box.pendingCount())
        assertEquals(0, box.conflictCount())
        assertNull(box.writeFor("a.md"))
        assertNull(box.deleteFor("b.md"))
        assertNull(box.conflictFor("c.md"))
    }

    @Test
    fun `cleared outbox does not resurrect on reload`() {
        val box = outbox()
        box.putWrite(PendingWrite("a.md", "x", null, true))
        box.clear()

        val reloaded = outbox()
        assertEquals(0, reloaded.pendingCount())
        assertFalse(reloaded.isPending("a.md"))
    }
}
