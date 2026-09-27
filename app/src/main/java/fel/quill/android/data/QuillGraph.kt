package fel.quill.android.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object QuillGraph {
    private val mutex = Mutex()

    @Volatile
    private var repositories: NotesRepository? = null

    @Volatile
    private var folderStores: FolderStore? = null

    fun repository(context: Context): NotesRepository {
        repositories?.let { return it }
        synchronized(this) {
            repositories?.let { return it }
            val created = NotesRepository(context.applicationContext, BridgeClient())
            repositories = created
            return created
        }
    }

    fun folderStore(context: Context): FolderStore {
        folderStores?.let { return it }
        synchronized(this) {
            folderStores?.let { return it }
            val created = FolderStore(context.applicationContext, NoteCrypto(context.applicationContext))
            folderStores = created
            return created
        }
    }

    suspend fun <T> withSyncLock(block: suspend () -> T): T = mutex.withLock { block() }
}
