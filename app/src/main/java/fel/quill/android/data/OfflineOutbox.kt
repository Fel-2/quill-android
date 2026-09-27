package fel.quill.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PendingWrite(
    val path: String,
    val content: String,
    val etag: String?,
    val create: Boolean,
)

data class PendingDelete(
    val path: String,
    val etag: String,
    val content: String,
)

data class PendingConflict(
    val path: String,
    val localContent: String,
    val remoteEtag: String,
    val createdAt: Long,
)

class OfflineOutbox(private val file: File, private val crypto: NoteCrypto? = null) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "quill-sync-outbox.json"), NoteCrypto(context))
    private val writes = linkedMapOf<String, PendingWrite>()
    private val deletes = linkedMapOf<String, PendingDelete>()
    private val conflicts = linkedMapOf<String, PendingConflict>()

    init {
        load()
    }

    @Synchronized
    fun putWrite(value: PendingWrite) {
        writes[value.path] = value
        deletes.remove(value.path)
        persist()
    }

    @Synchronized
    fun putDelete(value: PendingDelete) {
        deletes[value.path] = value
        writes.remove(value.path)
        persist()
    }

    @Synchronized
    fun remove(path: String) {
        writes.remove(path)
        deletes.remove(path)
        persist()
    }

    @Synchronized
    fun writeFor(path: String): PendingWrite? = writes[path]

    @Synchronized
    fun deleteFor(path: String): PendingDelete? = deletes[path]

    @Synchronized
    fun writesSnapshot(): List<PendingWrite> = writes.values.toList()

    @Synchronized
    fun deletesSnapshot(): List<PendingDelete> = deletes.values.toList()

    @Synchronized
    fun isPending(path: String): Boolean = writes.containsKey(path) || deletes.containsKey(path)

    @Synchronized
    fun markConflict(path: String, localContent: String, remoteEtag: String) {
        conflicts[path] = PendingConflict(path, localContent, remoteEtag, System.currentTimeMillis())
        writes.remove(path)
        deletes.remove(path)
        persist()
    }

    @Synchronized
    fun pendingCount(): Int = writes.size + deletes.size

    @Synchronized
    fun conflictCount(): Int = conflicts.size

    @Synchronized
    fun conflictPaths(): List<String> = conflicts.keys.toList()

    @Synchronized
    fun conflictFor(path: String): PendingConflict? = conflicts[path]

    @Synchronized
    fun clearConflict(path: String) {
        conflicts.remove(path)
        persist()
    }

    @Synchronized
    fun clear() {
        writes.clear()
        deletes.clear()
        conflicts.clear()
        persist()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val text = crypto?.read(file) ?: file.readText()
            val json = JSONObject(text)
            val writeArray = json.optJSONArray("writes") ?: JSONArray()
            for (index in 0 until writeArray.length()) {
                val item = writeArray.optJSONObject(index) ?: continue
                val path = item.optString("path")
                if (path.isNotBlank()) {
                    writes[path] = PendingWrite(
                        path = path,
                        content = item.optString("content"),
                        etag = item.optString("etag").ifBlank { null },
                        create = item.optBoolean("create"),
                    )
                }
            }
            val deleteArray = json.optJSONArray("deletes") ?: JSONArray()
            for (index in 0 until deleteArray.length()) {
                val item = deleteArray.optJSONObject(index) ?: continue
                val path = item.optString("path")
                if (path.isNotBlank()) {
                    deletes[path] = PendingDelete(path, item.optString("etag"), item.optString("content"))
                }
            }
            val conflictArray = json.optJSONArray("conflicts") ?: JSONArray()
            for (index in 0 until conflictArray.length()) {
                val item = conflictArray.optJSONObject(index) ?: continue
                val path = item.optString("path")
                if (path.isNotBlank()) {
                    conflicts[path] = PendingConflict(
                        path = path,
                        localContent = item.optString("local_content"),
                        remoteEtag = item.optString("remote_etag"),
                        createdAt = item.optLong("created_at"),
                    )
                }
            }
        }
    }

    private fun persist() {
        val json = JSONObject()
        val writeArray = JSONArray()
        writes.values.forEach { value ->
            writeArray.put(
                JSONObject()
                    .put("path", value.path)
                    .put("content", value.content)
                    .put("etag", value.etag ?: "")
                    .put("create", value.create),
            )
        }
        val deleteArray = JSONArray()
        deletes.values.forEach { value ->
            deleteArray.put(JSONObject().put("path", value.path).put("etag", value.etag).put("content", value.content))
        }
        val conflictArray = JSONArray()
        conflicts.values.forEach { value ->
            conflictArray.put(
                JSONObject()
                    .put("path", value.path)
                    .put("local_content", value.localContent)
                    .put("remote_etag", value.remoteEtag)
                    .put("created_at", value.createdAt),
            )
        }
        json.put("writes", writeArray).put("deletes", deleteArray).put("conflicts", conflictArray)
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: File(".")
        val temporary = File.createTempFile("quill-outbox-", ".tmp", parent)
        if (crypto != null) {
            crypto.write(temporary, json.toString())
        } else {
            temporary.writeText(json.toString())
        }
        if (!temporary.renameTo(file)) {
            if (crypto != null) crypto.write(file, json.toString()) else file.writeText(json.toString())
            temporary.delete()
        }
    }
}
