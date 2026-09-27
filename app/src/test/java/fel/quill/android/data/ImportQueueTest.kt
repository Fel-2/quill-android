package fel.quill.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportQueueTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    private fun outbox() = OfflineOutbox(File(folder.root, "outbox.json"))

    @Test
    fun `a queued import is treated as pending so a sync will not cull it`() {
        val box = outbox()
        box.putWrite(PendingWrite("Vault/Note.md", "# Note", null, false))
        assertTrue("imported file must be pending", box.isPending("Vault/Note.md"))
    }

    @Test
    fun `an unqueued file is not pending and would be culled`() {
        val box = outbox()
        assertFalse(box.isPending("Vault/Note.md"))
    }

    @Test
    fun `import upserts without a create precondition`() {
        val box = outbox()
        box.putWrite(PendingWrite("Vault/Note.md", "# Note", null, false))
        val pending = box.writeFor("Vault/Note.md")!!
        assertFalse("create=true would 409 against an existing PC file", pending.create)
        assertTrue("no etag means an unconditional upsert", pending.etag == null)
    }

    @Test
    fun `many imported files are all pending after a reload`() {
        val box = outbox()
        (1..50).forEach { box.putWrite(PendingWrite("Vault/Note$it.md", "# $it", null, false)) }
        val reloaded = outbox()
        (1..50).forEach { assertTrue("Note$it.md", reloaded.isPending("Vault/Note$it.md")) }
        assertTrue(reloaded.pendingCount() >= 50)
    }
}
