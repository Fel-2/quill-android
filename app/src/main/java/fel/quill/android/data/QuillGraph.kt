package fel.quill.android.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object QuillGraph {
    private val mutex = Mutex()

    @Volatile
    private var repositories: NotesRepository? = null

    fun repository(context: Context): NotesRepository {
        repositories?.let { return it }
        synchronized(this) {
            repositories?.let { return it }
            val created = NotesRepository(context.applicationContext, BridgeClient())
            repositories = created
            return created
        }
    }

    suspend fun <T> withSyncLock(block: suspend () -> T): T = mutex.withLock { block() }
}
