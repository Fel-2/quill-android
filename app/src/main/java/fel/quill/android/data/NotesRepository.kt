package fel.quill.android.data

import android.content.Context
import fel.quill.android.model.BridgeConfig
import fel.quill.android.model.Library
import fel.quill.android.model.NoteDocument
import fel.quill.android.model.Todo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate

private data class UndoEntry(val path: String, val content: String?)

class NotesRepository(context: Context, private val bridge: BridgeClient) {
    private val root = File(context.applicationContext.filesDir, "notes")
    private val outbox = OfflineOutbox(context)
    private val crypto = NoteCrypto(context)
    private val undoStack = ArrayDeque<UndoEntry>()

    val usesBridge: Boolean get() = usesBridgeInternal
    private var usesBridgeInternal = false

    fun setBridgeEnabled(enabled: Boolean) {
        usesBridgeInternal = enabled
        if (!enabled) outbox.clear()
    }

    suspend fun sync(config: BridgeConfig?): Library = withContext(Dispatchers.IO) {
        root.mkdirs()
        if (config == null) return@withContext buildLibrary()
        flushPending(config)
        val manifest = bridge.manifest(config)
        val remotePaths = manifest.files.associateBy { it.path }
        manifest.files.forEach { entry ->
            val local = resolveLocal(entry.path)
            val pending = outbox.writeFor(entry.path)
            val pendingDelete = outbox.deleteFor(entry.path)
            if (pending != null && (pending.etag ?: "") != entry.etag) {
                runCatching { outbox.markConflict(entry.path, pending.content, entry.etag) }
                    .getOrElse { outbox.markConflict(entry.path, pending.content, "") }
            }
            if (pendingDelete != null && pendingDelete.etag != entry.etag) {
                val remote = bridge.readFile(config, entry.path)
                writeLocal(local, remote.content)
                runCatching { outbox.markConflict(entry.path, pendingDelete.content, entry.etag) }
                    .getOrElse { outbox.markConflict(entry.path, pendingDelete.content, "") }
                return@forEach
            }
            if (outbox.isPending(entry.path)) return@forEach
            if (!local.isFile || localEtag(entry.path) != entry.etag) {
                val remote = bridge.readFile(config, entry.path)
                writeLocal(local, remote.content)
            }
        }
        localMarkdownFiles().forEach { file ->
            val path = relativePath(file)
            if (!remotePaths.containsKey(path) && !outbox.isPending(path)) file.delete()
        }
        buildLibrary()
    }

    fun loadLocal(): Library {
        if (!root.exists()) return MarkdownParser.parseLibrary(emptyList()) { 0L }
        return buildLibrary()
    }

    fun readLocal(path: String): String {
        val file = resolveLocal(path)
        if (!file.isFile) return ""
        return crypto.read(file).orEmpty()
    }

    fun localEtag(path: String): String {
        val file = resolveLocal(path)
        if (!file.isFile) return ""
        val plaintext = crypto.read(file) ?: return ""
        return sha256(plaintext.toByteArray(Charsets.UTF_8))
    }

    fun pendingCount(): Int = outbox.pendingCount()

    fun conflictCount(): Int = outbox.conflictCount()

    fun conflictPaths(): List<String> = outbox.conflictPaths()

    fun conflictFor(path: String): PendingConflict? = outbox.conflictFor(path)

    fun cacheEncryptionEnabled(): Boolean = crypto.isEnabled()

    fun setCacheEncryption(enabled: Boolean) {
        if (crypto.isEnabled() == enabled) return
        crypto.setEnabled(enabled, root)
    }

    fun resolveConflict(path: String) {
        outbox.clearConflict(path)
    }

    fun findNote(library: Library, path: String): NoteDocument? = library.notes.firstOrNull { it.path == path }

    suspend fun write(path: String, content: String, create: Boolean = false, expectedEtag: String? = null, config: BridgeConfig? = null): Library = withContext(Dispatchers.IO) {
        val currentEtag = if (create) null else localEtag(path)
        if (!create && expectedEtag != null && currentEtag != expectedEtag) {
            outbox.markConflict(path, content, currentEtag.orEmpty())
            val conflict = PendingConflict(path, content, currentEtag.orEmpty(), System.currentTimeMillis())
            return@withContext buildLibrary(conflict)
        }
        val oldContent = if (resolveLocal(path).isFile) readLocal(path) else null
        pushUndo(UndoEntry(path, oldContent))
        writeLocal(resolveLocal(path), content)
        if (config == null || !usesBridgeInternal) return@withContext buildLibrary()
        queueWrite(path, content, currentEtag, create)
        val pending = outbox.writeFor(path)
        try {
            bridge.writeFile(config, path, content, pending?.etag, pending?.create ?: create)
            outbox.remove(path)
        } catch (error: BridgeException) {
            when {
                error.statusCode == 409 -> {
                    runCatching { outbox.markConflict(path, content, bridge.remoteEtag(config, path)) }
                        .getOrElse { outbox.markConflict(path, content, "") }
                    throw error
                }
                error.statusCode in 400..403 -> {
                    outbox.remove(path)
                    restoreLocal(path, oldContent)
                    throw error
                }
            }
        } catch (_: IOException) {
        }
        buildLibrary()
    }

    suspend fun undoLast(config: BridgeConfig?): Library = withContext(Dispatchers.IO) {
        val entry = undoStack.removeLastOrNull() ?: return@withContext buildLibrary()
        if (entry.content == null) {
            val pendingWrite = outbox.writeFor(entry.path)
            if (pendingWrite != null) {
                outbox.remove(entry.path)
                resolveLocal(entry.path).delete()
            } else {
                val etag = localEtag(entry.path)
                resolveLocal(entry.path).delete()
                if (etag.isNotBlank()) queueDelete(entry.path, entry.content.orEmpty(), etag)
                flushOneDelete(config, entry.path)
            }
        } else {
            val currentEtag = localEtag(entry.path)
            writeLocal(resolveLocal(entry.path), entry.content)
            queueWrite(entry.path, entry.content, currentEtag, false)
            flushOneWrite(config, entry.path)
        }
        buildLibrary()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    suspend fun createNote(title: String, body: String = "", config: BridgeConfig? = null): Pair<NoteDocument, Library> = withContext(Dispatchers.IO) {
        val (basePath, initialContent) = MarkdownParser.createNote(title, body)
        val existing = buildLibrary().notes.map { it.path }.toMutableSet()
        var path = basePath
        var suffix = 2
        while (path in existing || resolveLocal(path).exists()) {
            path = basePath.removeSuffix(".md") + "-$suffix.md"
            suffix++
        }
        val library = write(path, initialContent, create = true, config = config)
        val document = library.notes.first { it.path == path }
        document to library
    }

    suspend fun openDaily(config: BridgeConfig?): Pair<NoteDocument, Library> = withContext(Dispatchers.IO) {
        val date = LocalDate.now().toString()
        val path = "Daily/$date.md"
        if (!resolveLocal(path).isFile) {
            val content = "---\ntitle: $date\ndate: $date\ntags: [daily]\n---\n\n# $date\n\n## Tasks\n\n## Notes\n\n"
            write(path, content, create = true, config = config)
        }
        val library = buildLibrary()
        val document = library.notes.first { it.path == path }
        document to library
    }

    suspend fun saveNote(document: NoteDocument, body: String, title: String = document.title, expectedEtag: String? = null, config: BridgeConfig? = null): Library {
        return write(document.path, MarkdownParser.serializeBody(document, body, title), expectedEtag = expectedEtag, config = config)
    }

    suspend fun deleteNote(document: NoteDocument, config: BridgeConfig? = null): Library = withContext(Dispatchers.IO) {
        val oldContent = readLocal(document.path)
        val etag = localEtag(document.path)
        pushUndo(UndoEntry(document.path, oldContent))
        resolveLocal(document.path).delete()
        if (etag.isNotBlank() && config != null && usesBridgeInternal) queueDelete(document.path, oldContent, etag)
        flushOneDelete(config, document.path)
        buildLibrary()
    }

    suspend fun toggleTodo(todo: Todo, done: Boolean, config: BridgeConfig? = null): Library = withContext(Dispatchers.IO) {
        val raw = readLocal(todo.path)
        val updated = if (done && todo.recurrence != null) {
            val next = nextRecurringDue(todo.due, todo.recurrence)
            val completed = MarkdownParser.setDone(raw, todo.line, true)
            MarkdownParser.appendTodo(completed, todo.text, next, todo.time, todo.tags, todo.priority, todo.recurrence)
        } else {
            MarkdownParser.setDone(raw, todo.line, done)
        }
        write(todo.path, updated, config = config)
    }

    suspend fun deleteTodo(todo: Todo, config: BridgeConfig? = null): Library = withContext(Dispatchers.IO) {
        write(todo.path, MarkdownParser.deleteTodo(readLocal(todo.path), todo.line), config = config)
    }

    suspend fun cycleDue(todo: Todo, config: BridgeConfig? = null): Library = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val next = when (todo.due) {
            null, "" -> today
            today.toString() -> today.plusDays(1)
            today.plusDays(1).toString() -> today.plusDays(7)
            else -> null
        }
        write(todo.path, MarkdownParser.setDue(readLocal(todo.path), todo.line, next?.toString(), todo.time), config = config)
    }

    suspend fun setTodoDue(todo: Todo, due: String?, time: String?, config: BridgeConfig? = null): Library = withContext(Dispatchers.IO) {
        write(todo.path, MarkdownParser.setDue(readLocal(todo.path), todo.line, due, time), config = config)
    }

    fun resolveProjectTarget(file: String?): String {
        val clean = MarkdownParser.safeProjectPath(file) ?: return "Inbox.md"
        val notes = buildLibrary().notes
        notes.firstOrNull { it.path.equals(clean, ignoreCase = true) }?.let { return it.path }
        val stem = clean.removeSuffix(".md")
        notes.firstOrNull { it.title.equals(stem, ignoreCase = true) }?.let { return it.path }
        return clean
    }

    suspend fun addTodo(
        text: String,
        target: String? = null,
        due: String? = null,
        tags: List<String> = emptyList(),
        priority: Int = 0,
        time: String? = null,
        config: BridgeConfig? = null,
    ): Library = withContext(Dispatchers.IO) {
        val path = resolveProjectTarget(target)
        if (resolveLocal(path).isFile) {
            return@withContext write(path, MarkdownParser.appendTodo(readLocal(path), text, due, time, tags, priority), config = config)
        }
        val title = path.removeSuffix(".md").replace('-', ' ').replace('_', ' ')
        val created = createNote(title, config = config)
        val body = MarkdownParser.appendTodo(created.first.body, text, due, time, tags, priority)
        write(created.first.path, MarkdownParser.serializeBody(created.first, body, created.first.title), config = config)
    }

    suspend fun archiveCompleted(config: BridgeConfig? = null): Library = withContext(Dispatchers.IO) {
        val library = buildLibrary()
        val grouped = library.todos.filter { it.done }.groupBy { it.path }
        for ((path, todos) in grouped) {
            val raw = readLocal(path)
            val doneLines = todos.map { it.raw }
            val updated = raw.lines().filterNot { line -> doneLines.contains(line) }.joinToString("\n")
            write(path, updated, config = config)
        }
        val archivePath = "Archive.md"
        val archive = readLocal(archivePath)
        val sections = grouped.entries.joinToString("\n\n") { (path, todos) ->
            "## $path\n\n" + todos.joinToString("\n") { it.raw }
        }
        val updatedArchive = (if (archive.isBlank()) "" else archive.trimEnd('\n') + "\n\n") +
            "# Archived ${LocalDate.now()}\n\n$sections\n"
        if (resolveLocal(archivePath).isFile) write(archivePath, updatedArchive, config = config) else write(archivePath, updatedArchive, create = true, config = config)
        buildLibrary()
    }

    fun importNotes(entries: List<Pair<String, String>>, config: BridgeConfig?): Int {
        var imported = 0
        entries.forEach { (path, content) ->
            val target = runCatching { resolveLocal(path) }.getOrNull() ?: return@forEach
            val existed = target.isFile
            val oldContent = if (existed) readLocal(path) else null
            val ok = runCatching {
                pushUndo(UndoEntry(path, oldContent))
                writeLocal(target, content)
                if (config != null && usesBridgeInternal) {
                    outbox.putWrite(PendingWrite(path, content, null, false))
                }
            }.isSuccess
            if (ok) imported++
        }
        return imported
    }

    private fun queueWrite(path: String, content: String, etag: String?, create: Boolean) {
        val existingWrite = outbox.writeFor(path)
        val existingDelete = outbox.deleteFor(path)
        val baseEtag = existingWrite?.etag ?: existingDelete?.etag ?: etag
        val shouldCreate = existingWrite?.create ?: (existingDelete == null && create)
        outbox.putWrite(PendingWrite(path, content, baseEtag, shouldCreate))
    }

    private fun queueDelete(path: String, content: String, etag: String) {
        val existingWrite = outbox.writeFor(path)
        if (existingWrite?.create == true) {
            outbox.remove(path)
            return
        }
        val existingDelete = outbox.deleteFor(path)
        val baseEtag = existingWrite?.etag ?: existingDelete?.etag ?: etag
        val deletedContent = existingWrite?.content ?: content
        outbox.putDelete(PendingDelete(path, baseEtag, deletedContent))
    }

    private suspend fun flushPending(config: BridgeConfig) {
        outbox.writesSnapshot().forEach { pending -> flushOneWrite(config, pending.path) }
        outbox.deletesSnapshot().forEach { pending -> flushOneDelete(config, pending.path) }
    }

    private fun removeIfUnchanged(path: String, submitted: PendingWrite) {
        val current = outbox.writeFor(path) ?: return
        if (current.content == submitted.content && current.etag == submitted.etag && current.create == submitted.create) {
            outbox.remove(path)
        }
    }

    private fun removeDeleteIfUnchanged(path: String, submitted: PendingDelete) {
        val current = outbox.deleteFor(path) ?: return
        if (current.etag == submitted.etag && current.content == submitted.content) {
            outbox.remove(path)
        }
    }

    private suspend fun flushOneWrite(config: BridgeConfig?, path: String) {
        if (config == null || !usesBridgeInternal) return
        val pending = outbox.writeFor(path) ?: return
        try {
            bridge.writeFile(config, pending.path, pending.content, pending.etag, pending.create)
            removeIfUnchanged(path, pending)
        } catch (error: BridgeException) {
            when (error.statusCode) {
                404 -> {
                    val recreated = pending.copy(etag = null, create = true)
                    outbox.putWrite(recreated)
                    try {
                        bridge.writeFile(config, recreated.path, recreated.content, null, true)
                        removeIfUnchanged(path, recreated)
                    } catch (retry: BridgeException) {
                        if (retry.statusCode in 400..403 && retry.statusCode != 404) throw retry
                    } catch (_: IOException) {
                    }
                }
                409 -> {
                    runCatching { outbox.markConflict(path, pending.content, bridge.remoteEtag(config, path)) }
                        .getOrElse { outbox.markConflict(path, pending.content, "") }
                    throw error
                }
                in 400..403 -> throw error
            }
        } catch (_: IOException) {
        }
    }

    private suspend fun flushOneDelete(config: BridgeConfig?, path: String) {
        if (config == null || !usesBridgeInternal) return
        val pending = outbox.deleteFor(path) ?: return
        try {
            bridge.deleteFile(config, pending.path, pending.etag)
            removeDeleteIfUnchanged(path, pending)
        } catch (error: BridgeException) {
            when (error.statusCode) {
                404 -> removeDeleteIfUnchanged(path, pending)
                in 400..403 -> throw error
            }
        } catch (_: IOException) {
        }
    }

    private fun pushUndo(entry: UndoEntry) {
        undoStack.addLast(entry)
        while (undoStack.size > 25) undoStack.removeFirst()
    }

    private fun restoreLocal(path: String, content: String?) {
        if (content == null) resolveLocal(path).delete() else writeLocal(resolveLocal(path), content)
    }

    private fun buildLibrary(syntheticConflict: PendingConflict? = null): Library {
        val files = localMarkdownFiles().mapNotNull { file ->
            crypto.read(file)?.let { relativePath(file) to it }
        }
        val modified = localMarkdownFiles().associate { relativePath(it) to it.lastModified() }
        val library = MarkdownParser.parseLibrary(files) { modified[it] ?: 0L }
        if (syntheticConflict == null) return library
        val existing = outbox.conflictFor(syntheticConflict.path)
        val merged = if (existing != null && existing.createdAt >= syntheticConflict.createdAt) existing else syntheticConflict
        val patched = library.copy(
            notes = library.notes.map { note ->
                if (note.path == merged.path) note.copy(body = merged.localContent) else note
            },
            counts = library.counts.copy(overdue = library.counts.overdue, notes = library.counts.notes),
        )
        return patched
    }

    private fun localMarkdownFiles(): List<File> {
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
        val filePath = file.canonicalFile.toPath()
        require(filePath.startsWith(rootPath)) { "Unsafe notes path" }
        return file
    }

    private fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    private fun writeLocal(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile("quill-", ".tmp", file.parentFile ?: root)
        crypto.write(temporary, content)
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun nextRecurringDue(current: String?, recurrence: String): String {
        val date = current?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        return when (recurrence) {
            "daily" -> date.plusDays(1)
            "weekly" -> date.plusDays(7)
            "monthly" -> date.plusMonths(1)
            else -> date.plusDays(1)
        }.toString()
    }
}
