package fel.quill.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

class DocumentTree(private val context: Context, private val treeUri: Uri) {

    data class Entry(val name: String, val uri: Uri, val isDirectory: Boolean, val size: Long)

    private val resolver = context.contentResolver
    private var error: String? = null

    fun lastError(): String? = error

    private fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (throwable: Throwable) {
        error = throwable.message ?: throwable.javaClass.simpleName
        null
    }

    fun list(parent: Uri = treeUri): List<Entry> {
        val childrenUri = childrenUri(parent)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val entries = mutableListOf<Entry>()
        attempt {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    entries += Entry(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(parent, id),
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = size,
                    )
                }
            } ?: throw IllegalStateException("Provider returned no cursor")
        }
        return entries
    }

    private fun childrenUri(parent: Uri): Uri {
        val treeId = DocumentsContract.getTreeDocumentId(parent)
        val docId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrDefault(treeId)
        return DocumentsContract.buildChildDocumentsUriUsingTree(parent, docId)
    }

    fun read(uri: Uri): String? = attempt {
        resolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: throw IllegalStateException("Provider returned no input stream")
    }

    fun child(parent: Uri, name: String, mime: String): Uri? = attempt {
        DocumentsContract.createDocument(resolver, childrenUri(parent), mime, name)
    }

    fun mkdirs(parent: Uri, segments: List<String>): Uri? {
        var current = parent
        for (segment in segments) {
            val existing = list(current).firstOrNull { it.isDirectory && it.name == segment }
            current = existing?.uri ?: child(current, segment, DocumentsContract.Document.MIME_TYPE_DIR) ?: return null
        }
        return current
    }

    fun write(parent: Uri, name: String, content: String): Uri? {
        val existing = list(parent).firstOrNull { !it.isDirectory && it.name == name }
        val target = existing?.uri ?: child(parent, name, "text/markdown") ?: return null
        return attempt {
            resolver.openOutputStream(target, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Provider returned no output stream")
            target
        }
    }

    fun canCreateIn(parent: Uri): Boolean {
        val flags = queryFlags(parent)
        return flags == null || (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
    }

    private fun queryFlags(uri: Uri): Int? = attempt {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
        }
    }

    fun diagnose(): String {
        val out = StringBuilder()
        out.append("authority=").append(treeUri.authority)
        out.append(" treeId=").append(runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrElse { "?" })
        val name = displayName()
        out.append(" name=").append(name ?: "?")
        if (error != null) out.append(" error=").append(error)
        val entries = list(treeUri)
        out.append(" children=").append(entries.size)
        if (error != null) out.append(" listError=").append(error)
        val flags = queryFlags(treeUri)
        out.append(" flags=").append(flags ?: -1)
        if (flags != null) {
            out.append(" canCreate=").append((flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0)
        }
        return out.toString()
    }

    fun clear(): Boolean = runCatching {
        for (entry in list()) {
            DocumentsContract.deleteDocument(resolver, entry.uri)
        }
        true
    }.getOrDefault(false)

    fun displayName(): String? = attempt {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val nameUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.query(nameUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
