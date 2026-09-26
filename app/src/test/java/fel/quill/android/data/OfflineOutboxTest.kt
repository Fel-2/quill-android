package fel.quill.android.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineOutboxTest {
    @Test
    fun persistsWritesDeletesAndConflicts() {
        val directory = File(System.getProperty("java.io.tmpdir"), "quill-outbox-test-${System.nanoTime()}")
        val file = File(directory, "outbox.json")
        val outbox = OfflineOutbox(file)
        outbox.putWrite(PendingWrite("Inbox.md", "local", "base", false))
        outbox.putDelete(PendingDelete("Old.md", "etag", "old content"))
        outbox.putWrite(PendingWrite("Conflict.md", "local", "base", false))
        outbox.markConflict("Conflict.md", "local", "remote")
        val loaded = OfflineOutbox(file)
        assertEquals("local", loaded.writeFor("Inbox.md")?.content)
        assertEquals("old content", loaded.deleteFor("Old.md")?.content)
        assertEquals(null, loaded.writeFor("Conflict.md"))
        assertEquals(null, loaded.deleteFor("Conflict.md"))
        assertEquals(2, loaded.pendingCount())
        assertTrue(loaded.conflictPaths().contains("Conflict.md"))
        directory.deleteRecursively()
    }

    @Test
    fun compareAndRemoveKeepsANewerQueuedWrite() {
        val directory = File(System.getProperty("java.io.tmpdir"), "quill-outbox-test-${System.nanoTime()}")
        val file = File(directory, "outbox.json")
        val outbox = OfflineOutbox(file)
        val submitted = PendingWrite("Inbox.md", "first", "base", false)
        outbox.putWrite(submitted)
        // The user edits again while the first push is in flight.
        outbox.putWrite(PendingWrite("Inbox.md", "second", "base", false))
        // A stale flush must not discard the newer entry.
        val current = outbox.writeFor("Inbox.md")
        if (current?.content == submitted.content) outbox.remove("Inbox.md")
        assertEquals("second", outbox.writeFor("Inbox.md")?.content)
        assertEquals("second", OfflineOutbox(file).writeFor("Inbox.md")?.content)
        directory.deleteRecursively()
    }

    @Test
    fun conflictResolutionClearsPendingAndConflict() {
        val directory = File(System.getProperty("java.io.tmpdir"), "quill-outbox-test-${System.nanoTime()}")
        val file = File(directory, "outbox.json")
        val outbox = OfflineOutbox(file)
        outbox.putWrite(PendingWrite("Inbox.md", "local", "base", false))
        outbox.markConflict("Inbox.md", "local", "remote")
        assertEquals(null, outbox.writeFor("Inbox.md"))
        assertTrue(outbox.conflictPaths().contains("Inbox.md"))
        outbox.clearConflict("Inbox.md")
        assertEquals(0, outbox.conflictCount())
        assertEquals(0, outbox.pendingCount())
        directory.deleteRecursively()
    }
}
