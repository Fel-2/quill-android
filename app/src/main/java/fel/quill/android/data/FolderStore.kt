package fel.quill.android.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ExportResult(val written: Int, val failed: Int, val detail: String)

class FolderStore(context: Context, private val crypto: NoteCrypto) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("quill_folders", Context.MODE_PRIVATE)
    private val root = File(appContext.filesDir, "notes")

    fun savedTree(): Uri? = preferences.getString("tree", null)?.let(Uri::parse)

    fun rememberTree(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { appContext.contentResolver.takePersistableUriPermission(uri, flags) }
        preferences.edit().putString("tree", uri.toString()).apply()
    }

    fun forgetTree() {
        savedTree()?.let { uri ->
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { appContext.contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        preferences.edit().remove("tree").apply()
    }

    suspend fun collectForImport(treeUri: Uri): Pair<List<Pair<String, String>>, Int> = withContext(Dispatchers.IO) {
        val tree = DocumentTree(appContext, treeUri)
        val entries = mutableListOf<Pair<String, String>>()
        var skipped = 0
        collect(tree, treeUri).forEach { (relative, entry) ->
            val content = tree.read(entry.uri)
            when {
                content == null -> skipped++
                crypto.isEncrypted(content.toByteArray(Charsets.UTF_8)) -> skipped++
                runCatching { resolveLocal(relative) }.isFailure -> skipped++
                else -> entries += relative to content
            }
        }
        entries to skipped
    }

    suspend fun export(treeUri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val tree = DocumentTree(appContext, treeUri)
        var written = 0
        var failed = 0
        var reason: String? = null
        val notes = localNotes()
        if (notes.isEmpty()) {
            return@withContext ExportResult(0, 0, "No notes to export")
        }
        notes.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val segments = relative.split('/')
            val content = crypto.read(file)
            if (content == null) {
                failed++
                reason = reason ?: "Could not read $relative"
                return@forEach
            }
            val folder = tree.mkdirs(treeUri, segments.dropLast(1))
            if (folder == null) {
                failed++
                reason = reason ?: tree.lastError() ?: "Could not create a folder for $relative — ${tree.diagnose()}"
                return@forEach
            }
            if (!tree.canCreateIn(folder)) {
                failed++
                reason = reason ?: "This folder does not allow new files — ${tree.diagnose()}"
                return@forEach
            }
            val result = tree.write(folder, segments.last(), content)
            if (result == null) {
                failed++
                reason = reason ?: "Provider refused to write $relative — ${tree.diagnose()}"
            } else {
                written++
            }
        }
        ExportResult(written, failed, reason ?: (tree.displayName() ?: treeUri.lastPathSegment.orEmpty()))
    }

    suspend fun diagnose(treeUri: Uri): String = withContext(Dispatchers.IO) {
        val tree = DocumentTree(appContext, treeUri)
        val notes = localNotes().size
        "${tree.diagnose()} localNotes=$notes"
    }

    private fun collect(tree: DocumentTree, parent: Uri, prefix: String = "", depth: Int = 0): List<Pair<String, DocumentTree.Entry>> {
        if (depth > MAX_DEPTH) return emptyList()
        val out = mutableListOf<Pair<String, DocumentTree.Entry>>()
        for (entry in tree.list(parent)) {
            val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            if (entry.isDirectory) {
                out += collect(tree, entry.uri, path, depth + 1)
            } else if (entry.name.endsWith(".md", ignoreCase = true) && !entry.name.startsWith(".")) {
                out += path to entry
            }
        }
        return out
    }

    private companion object {
        const val MAX_DEPTH = 32
    }

    private fun localNotes(): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) && !it.name.startsWith(".") }
            .toList()
    }

    private fun resolveLocal(path: String): File {
        val clean = path.replace('\\', '/')
        require(!clean.startsWith("/") && !clean.split('/').any { it == ".." || it.isEmpty() }) { "Unsafe notes path" }
        val file = File(root, clean)
        val rootPath = root.canonicalFile.toPath()
        require(file.canonicalFile.toPath().startsWith(rootPath)) { "Unsafe notes path" }
        return file
    }
}
