package fel.quill.android

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fel.quill.android.ai.AiClient
import fel.quill.android.data.BridgeClient
import fel.quill.android.data.BridgeException
import fel.quill.android.data.MarkdownParser
import fel.quill.android.data.NotesRepository
import fel.quill.android.data.QuillGraph
import fel.quill.android.data.SecureStore
import fel.quill.android.model.AiConfig
import fel.quill.android.model.AppTab
import fel.quill.android.model.AskMessage
import fel.quill.android.model.BridgeConfig
import fel.quill.android.model.DiscoveredBridge
import fel.quill.android.model.Library
import fel.quill.android.model.NoteDocument
import fel.quill.android.model.Todo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

enum class ConnectionStatus {
    NEEDS_PAIRING,
    CONNECTING,
    CONNECTED,
    OFFLINE,
    STANDALONE,
    ERROR,
}

data class QuillUiState(
    val connection: ConnectionStatus = ConnectionStatus.STANDALONE,
    val config: BridgeConfig? = null,
    val discovered: List<DiscoveredBridge> = emptyList(),
    val library: Library = Library(),
    val tab: AppTab = AppTab.TODOS,
    val search: String = "",
    val captureText: String = "",
    val selectedNote: NoteDocument? = null,
    val noteTitleDraft: String = "",
    val editorText: String = "",
    val askText: String = "",
    val askMessages: List<AskMessage> = emptyList(),
    val aiConfig: AiConfig = AiConfig(),
    val pairingCode: String? = null,
    val pendingChanges: Int = 0,
    val conflicts: Int = 0,
    val conflictPaths: List<String> = emptyList(),
    val encryptCache: Boolean = false,
    val showPairing: Boolean = false,
    val importFolderName: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class QuillViewModel(application: Application) : AndroidViewModel(application) {
    private val secureStore = SecureStore(application)
    private val bridgeClient = BridgeClient()
    private val repository = QuillGraph.repository(application)
    private var selectedEtag: String? = null
    private val aiClient = AiClient()
    private val _state = MutableStateFlow(
        QuillUiState(
            aiConfig = secureStore.loadAi(),
            pendingChanges = repository.pendingCount(),
            conflicts = repository.conflictCount(),
            conflictPaths = repository.conflictPaths(),
            encryptCache = repository.cacheEncryptionEnabled(),
        ),
    )
    val state: StateFlow<QuillUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val library = repository.loadLocal()
            _state.update { it.copy(library = library, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
        }
        val saved = secureStore.loadBridge()
        if (saved != null) {
            repository.setBridgeEnabled(true)
            _state.update { it.copy(config = saved, connection = ConnectionStatus.CONNECTING) }
            viewModelScope.launch { connectAndSync(saved) }
        }
        viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(10_000)
                val config = _state.value.config
                if ((_state.value.connection == ConnectionStatus.CONNECTED || _state.value.connection == ConnectionStatus.OFFLINE) && !_state.value.busy && config != null) {
                    sync(config, silent = true)
                }
            }
        }
    }

    fun startStandalone() {
        repository.setBridgeEnabled(false)
        _state.update { it.copy(connection = ConnectionStatus.STANDALONE, showPairing = false, message = null) }
    }

    fun openPairing() {
        _state.update { it.copy(showPairing = true) }
    }

    fun closePairing() {
        val hasBridge = _state.value.config != null
        _state.update { it.copy(showPairing = false, connection = if (hasBridge) it.connection else ConnectionStatus.STANDALONE) }
    }

    fun discover() {
        launchTask {
            val found = bridgeClient.discover()
            _state.update { it.copy(discovered = found, message = if (found.isEmpty()) "No Quill bridge found" else null) }
        }
    }

    fun pair(host: String, port: Int, code: String, useTls: Boolean, fingerprint: String) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || port !in 1..65535 || code.trim().length < 6) {
            _state.update { it.copy(message = "Enter a host, port, and pairing code") }
            return
        }
        _state.update { it.copy(connection = ConnectionStatus.CONNECTING, message = null) }
        viewModelScope.launch {
            try {
                val resolvedFingerprint = fingerprint.trim().ifBlank {
                    if (useTls) bridgeClient.pairInfo(cleanHost, port, true).fingerprint else ""
                }
                val config = BridgeConfig(
                    host = cleanHost,
                    port = port,
                    useTls = useTls,
                    fingerprint = resolvedFingerprint,
                    token = "",
                    deviceName = Build.MODEL ?: "Android device",
                )
                val paired = bridgeClient.pair(config, code, config.deviceName)
                secureStore.saveBridge(paired)
                repository.setBridgeEnabled(true)
                _state.update { it.copy(config = paired, showPairing = false) }
                connectAndSync(paired)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(connection = ConnectionStatus.ERROR, message = error.message ?: "Pairing failed") }
            }
        }
    }

    fun refresh() {
        val config = _state.value.config ?: return
        launchTask { sync(config) }
    }

    fun selectTab(tab: AppTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun setSearch(value: String) {
        _state.update { it.copy(search = value) }
    }

    fun setCaptureText(value: String) {
        _state.update { it.copy(captureText = value) }
    }

    fun setNoteTitle(value: String) {
        _state.update { it.copy(noteTitleDraft = value) }
    }

    fun setEditorText(value: String) {
        _state.update { it.copy(editorText = value) }
    }

    fun setAskText(value: String) {
        _state.update { it.copy(askText = value) }
    }

    fun capture() {
        val text = _state.value.captureText.trim()
        if (text.isBlank()) return
        val config = _state.value.config
        _state.update { it.copy(captureText = "", busy = true, message = null) }
        viewModelScope.launch {
            try {
                if (_state.value.aiConfig.aiCapture) {
                    val projects = _state.value.library.notes.joinToString("\n") { note ->
                        "- ${note.path} — ${note.title}"
                    }.let { if (it.isEmpty()) "" else "Existing projects:\n$it" }
                    val result = runCatching { aiClient.capture(_state.value.aiConfig, text, projects) }.getOrNull()
                    if (result != null && result.kind == "note") {
                        val created = QuillGraph.withSyncLock { repository.createNote(result.text, result.body, config) }
                        _state.update { it.copy(library = created.second, selectedNote = created.first, noteTitleDraft = created.first.title, editorText = created.first.body, tab = AppTab.NOTES) }
                    } else {
                        val library = QuillGraph.withSyncLock { repository.addTodo(result?.text ?: text, result?.file, result?.due, result?.tags.orEmpty(), result?.priority ?: 0, result?.time, config) }
                        _state.update { it.copy(library = library, message = localMessage("Added todo")) }
                    }
                } else {
                    val (hintDue, hintTime) = MarkdownParser.parseDueHint(text)
                    val library = QuillGraph.withSyncLock { repository.addTodo(text, null, hintDue, emptyList(), 0, hintTime, config) }
                    _state.update { it.copy(library = library, message = if (hintDue != null || hintTime != null) "Added todo with a due date" else "Added todo") }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val (hintDue, hintTime) = MarkdownParser.parseDueHint(text)
                val fallback = runCatching { QuillGraph.withSyncLock { repository.addTodo(text, null, hintDue, emptyList(), 0, hintTime, config) } }.getOrNull()
                _state.update { it.copy(library = fallback ?: it.library, message = if (fallback != null) "AI unavailable; added as a todo" else (error.message ?: "Capture failed")) }
            } finally {
                _state.update { it.copy(busy = false, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
            }
        }
    }

    fun toggleTodo(todo: Todo) {
        val config = _state.value.config
        launchTask {
            val library = QuillGraph.withSyncLock { repository.toggleTodo(todo, !todo.done, config) }
            _state.update { it.copy(library = library, message = localMessage(if (todo.done) "Todo reopened" else "Todo completed")) }
        }
    }

    fun cycleDue(todo: Todo) {
        val config = _state.value.config
        launchTask { _state.update { it.copy(library = QuillGraph.withSyncLock { repository.cycleDue(todo, config) }) } }
    }

    fun setTodoDue(todo: Todo, due: String?, time: String?) {
        val config = _state.value.config
        launchTask { _state.update { it.copy(library = QuillGraph.withSyncLock { repository.setTodoDue(todo, due, time, config) }) } }
    }

    fun deleteTodo(todo: Todo) {
        val config = _state.value.config
        launchTask { _state.update { it.copy(library = QuillGraph.withSyncLock { repository.deleteTodo(todo, config) }, message = localMessage("Todo deleted")) } }
    }

    fun archiveCompleted() {
        val config = _state.value.config
        launchTask { _state.update { it.copy(library = QuillGraph.withSyncLock { repository.archiveCompleted(config) }, message = localMessage("Completed todos archived")) } }
    }

    fun openNote(note: NoteDocument) {
        selectedEtag = repository.localEtag(note.path)
        _state.update { it.copy(selectedNote = note, noteTitleDraft = note.title, editorText = note.body, tab = AppTab.NOTES) }
    }

    fun openWikiLink(target: String) {
        val clean = target.substringBefore('|').trim()
        val note = _state.value.library.notes.firstOrNull {
            it.title.equals(clean, ignoreCase = true) || it.path.removeSuffix(".md").equals(clean, ignoreCase = true) || it.path.substringAfterLast('/').removeSuffix(".md").equals(clean, ignoreCase = true)
        }
        if (note == null) {
            _state.update { it.copy(message = "Note not found: $clean") }
        } else {
            openNote(note)
        }
    }

    fun closeNote() {
        selectedEtag = null
        _state.update { it.copy(selectedNote = null, noteTitleDraft = "", editorText = "") }
    }

    fun newNote() {
        val config = _state.value.config
        launchTask {
            val created = QuillGraph.withSyncLock { repository.createNote("Untitled", config = config) }
            _state.update { it.copy(library = created.second, selectedNote = created.first, noteTitleDraft = created.first.title, editorText = created.first.body, tab = AppTab.NOTES) }
        }
    }

    fun saveNote() {
        val config = _state.value.config
        val note = _state.value.selectedNote ?: return
        val body = _state.value.editorText
        val title = _state.value.noteTitleDraft.trim().ifBlank { note.title }
        val expectedEtag = selectedEtag
        launchTask {
            val library = QuillGraph.withSyncLock { repository.saveNote(note, body, title, expectedEtag, config) }
            val refreshed = library.notes.firstOrNull { it.path == note.path }
            selectedEtag = repository.localEtag(note.path)
            val conflicted = repository.conflictFor(note.path)
            val message = when {
                conflicted != null -> "Changed on the PC — your version is kept here; resolve with Keep mine / Keep PC"
                else -> localMessage("Saved")
            }
            _state.update { it.copy(library = library, selectedNote = refreshed ?: note, noteTitleDraft = refreshed?.title ?: title, editorText = refreshed?.body ?: body, message = message) }
        }
    }

    fun keepMine() {
        val note = _state.value.selectedNote ?: return
        val conflict = repository.conflictFor(note.path) ?: return
        val config = _state.value.config
        repository.resolveConflict(note.path)
        if (config == null) {
            _state.update { it.copy(conflicts = repository.conflictCount(), message = "Conflict cleared") }
            return
        }
        launchTask {
            QuillGraph.withSyncLock {
                repository.write(note.path, conflict.localContent, create = conflict.remoteEtag.isBlank(), expectedEtag = conflict.remoteEtag, config = config)
            }
            val library = repository.loadLocal()
            selectedEtag = repository.localEtag(note.path)
            _state.update { it.copy(library = library, selectedNote = library.notes.firstOrNull { it.path == note.path }, message = "Kept your version") }
        }
    }

    fun keepPc() {
        val note = _state.value.selectedNote ?: return
        val conflict = repository.conflictFor(note.path) ?: return
        val config = _state.value.config
        repository.resolveConflict(note.path)
        if (config == null) {
            _state.update { it.copy(conflicts = repository.conflictCount(), message = "Conflict cleared") }
            return
        }
        launchTask {
            val library = QuillGraph.withSyncLock { repository.sync(config) }
            val refreshed = library.notes.firstOrNull { it.path == note.path }
            selectedEtag = refreshed?.let { repository.localEtag(it.path) } ?: ""
            _state.update { it.copy(library = library, selectedNote = refreshed, editorText = refreshed?.body ?: it.editorText, message = "Kept the version from your PC") }
        }
    }

    fun copySelectedNote() {
        val content = _state.value.editorText
        if (content.isBlank()) {
            _state.update { it.copy(message = "Nothing to copy") }
            return
        }
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Quill", content))
        _state.update { it.copy(message = "Copied note") }
    }

    fun deleteNote() {
        val config = _state.value.config
        val note = _state.value.selectedNote ?: return
        launchTask {
            val library = QuillGraph.withSyncLock { repository.deleteNote(note, config) }
            _state.update { it.copy(library = library, selectedNote = null, noteTitleDraft = "", editorText = "", message = localMessage("Note deleted")) }
        }
    }

    fun ask() {
        val question = _state.value.askText.trim()
        if (question.isBlank()) return
        if (question.startsWith("/")) {
            runCommand(question)
            return
        }
        val ai = _state.value.aiConfig
        _state.update { it.copy(askText = "", askMessages = it.askMessages + AskMessage("user", question), busy = true, message = null) }
        viewModelScope.launch {
            try {
                val context = buildAskContext(question)
                val answer = aiClient.ask(ai, question, context, _state.value.askMessages.dropLast(1))
                val sources = Regex("\\[[^\\]\\n]+]").findAll(answer).map { it.value.removePrefix("[").removeSuffix("]") }.distinct().toList()
                _state.update { it.copy(askMessages = it.askMessages + AskMessage("assistant", answer, sources), message = null) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(askMessages = it.askMessages + AskMessage("error", error.message ?: "AI request failed")) }
            } finally {
                _state.update { it.copy(busy = false, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
            }
        }
    }

    fun runPlan() {
        val todos = _state.value.library.todos.filter { !it.done }.take(40).map { it.text }
        if (todos.isEmpty()) {
            _state.update { it.copy(message = "No open todos to plan") }
            return
        }
        _state.update { it.copy(askMessages = it.askMessages + AskMessage("user", "Plan my day"), busy = true) }
        viewModelScope.launch {
            try {
                val answer = aiClient.planDay(_state.value.aiConfig, todos)
                _state.update { it.copy(askMessages = it.askMessages + AskMessage("assistant", answer), tab = AppTab.ASK) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(askMessages = it.askMessages + AskMessage("error", error.message ?: "AI request failed")) }
            } finally {
                _state.update { it.copy(busy = false, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
            }
        }
    }

    fun runReview() {
        val library = _state.value.library
        val done = library.todos.filter { it.done }.joinToString("\n") { "- ${it.text}" }.ifBlank { "(none)" }
        val open = library.todos.filter { !it.done }.joinToString("\n") { "- ${it.text}" }.ifBlank { "(none)" }
        val recent = library.notes.take(15).joinToString("\n") { "- ${it.title}" }.ifBlank { "(none)" }
        val context = "Today is ${LocalDate.now()}.\n\nCompleted todos:\n$done\n\nStill open:\n$open\n\nRecently touched notes:\n$recent"
        _state.update { it.copy(askMessages = it.askMessages + AskMessage("user", "Weekly review"), busy = true) }
        viewModelScope.launch {
            try {
                val answer = aiClient.weeklyReview(_state.value.aiConfig, context)
                _state.update { it.copy(askMessages = it.askMessages + AskMessage("assistant", answer), tab = AppTab.ASK) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(askMessages = it.askMessages + AskMessage("error", error.message ?: "AI request failed")) }
            } finally {
                _state.update { it.copy(busy = false, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
            }
        }
    }

    fun summarizeSelected() {
        val note = _state.value.selectedNote ?: return
        val body = _state.value.editorText
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val summary = aiClient.summarize(_state.value.aiConfig, note.title, body)
                val updatedBody = body.trimEnd('\n') + "\n\n---\n\n## Summary\n\n" + summary.trim() + "\n"
                _state.update { it.copy(editorText = updatedBody, message = "Summary added; review and save") }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(message = error.message ?: "Summary failed") }
            } finally {
                _state.update { it.copy(busy = false, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
            }
        }
    }

    fun extractSelectedTodos() {
        val config = _state.value.config
        val note = _state.value.selectedNote ?: return
        val body = _state.value.editorText
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val todos = aiClient.extractTodos(_state.value.aiConfig, note.title, body)
                var library = _state.value.library
                QuillGraph.withSyncLock {
                    todos.forEach { todo ->
                        library = repository.addTodo(todo.text, null, todo.due, emptyList(), todo.priority, todo.time, config)
                    }
                }
                _state.update { it.copy(library = library, message = localMessage("Added ${todos.size} todo(s) to Inbox.md")) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(message = error.message ?: "Could not extract todos") }
            } finally {
                _state.update { it.copy(busy = false, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
            }
        }
    }

    fun rewriteSelected(instruction: String) {
        val note = _state.value.selectedNote ?: return
        val body = _state.value.editorText
        if (instruction.isBlank()) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val rewritten = aiClient.rewrite(_state.value.aiConfig, instruction, note.title, body)
                _state.update { it.copy(editorText = rewritten, message = "Rewrite ready; review and save") }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(message = error.message ?: "Rewrite failed") }
            } finally {
                _state.update { it.copy(busy = false, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
            }
        }
    }

    fun undo() {
        val config = _state.value.config
        if (!repository.canUndo()) {
            _state.update { it.copy(message = "Nothing to undo") }
            return
        }
        launchTask { _state.update { it.copy(library = QuillGraph.withSyncLock { repository.undoLast(config) }, message = localMessage("Undid the last change")) } }
    }

    fun copyLastAnswer() {
        val answer = _state.value.askMessages.lastOrNull { it.role == "assistant" }?.content
        if (answer.isNullOrBlank()) {
            _state.update { it.copy(message = "Nothing to copy yet") }
            return
        }
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Quill", answer))
        _state.update { it.copy(message = "Copied the last answer") }
    }

    fun openDaily() {
        val config = _state.value.config
        launchTask {
            val opened = QuillGraph.withSyncLock { repository.openDaily(config) }
            _state.update { it.copy(library = opened.second, selectedNote = opened.first, noteTitleDraft = opened.first.title, editorText = opened.first.body, tab = AppTab.NOTES) }
        }
    }

    fun forgetBridge() {
        secureStore.clearBridge()
        repository.setBridgeEnabled(false)
        _state.value = QuillUiState(
            aiConfig = _state.value.aiConfig,
            pendingChanges = repository.pendingCount(),
            conflicts = repository.conflictCount(),
            conflictPaths = repository.conflictPaths(),
        )
    }

    fun createPairingCode() {
        val config = _state.value.config ?: return
        launchTask {
            val code = bridgeClient.rotatePairing(config)
            _state.update { it.copy(pairingCode = code, message = "Pairing code created; it expires in 15 minutes") }
        }
    }

    fun saveAiConfig(config: AiConfig) {
        secureStore.saveAi(config)
        _state.update { it.copy(aiConfig = config) }
    }

    fun importFolder(uri: android.net.Uri) {
        val store = QuillGraph.folderStore(getApplication())
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = runCatching { QuillGraph.withSyncLock { store.import(uri) } }.getOrNull()
            val library = QuillGraph.withSyncLock { repository.loadLocal() }
            _state.update {
                it.copy(
                    library = library,
                    busy = false,
                    message = when {
                        result == null -> "Could not read that folder"
                        result.imported == 0 && result.skipped > 0 -> "Nothing imported: those notes are encrypted"
                        result.imported == 0 && result.overwritten == 0 && result.failed > 0 -> "Import failed for ${result.failed} file(s)"
                        result.overwritten > 0 -> "Imported ${result.imported}, replaced ${result.overwritten}"
                        result.failed > 0 -> "Imported ${result.imported}, ${result.failed} failed"
                        else -> "Imported ${result.imported} note(s)"
                    },
                )
            }
        }
    }

    fun exportFolder(uri: android.net.Uri) {
        val store = QuillGraph.folderStore(getApplication())
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = runCatching { QuillGraph.withSyncLock { store.export(uri) } }.getOrNull()
            _state.update {
                it.copy(
                    busy = false,
                    message = when {
                        result == null -> "Could not write to that folder"
                        result.written == 0 && result.failed == 0 -> result.detail
                        result.failed > 0 -> "Exported ${result.written}, ${result.failed} failed: ${result.detail}"
                        else -> "Exported ${result.written} note(s) to ${result.detail}"
                    },
                )
            }
        }
    }

    fun rememberExportFolder(uri: android.net.Uri) {
        QuillGraph.folderStore(getApplication()).rememberTree(uri)
        _state.update { it.copy(message = "Folder remembered for export") }
    }

    fun checkFolder(uri: android.net.Uri) {
        val store = QuillGraph.folderStore(getApplication())
        viewModelScope.launch {
            val report = runCatching { QuillGraph.withSyncLock { store.diagnose(uri) } }.getOrElse { it.message ?: "unknown error" }
            _state.update { it.copy(message = report) }
        }
    }

    fun setCacheEncryption(enabled: Boolean) {
        viewModelScope.launch {
            val ok = runCatching {
                QuillGraph.withSyncLock { repository.setCacheEncryption(enabled) }
            }.isSuccess
            val library = QuillGraph.withSyncLock { repository.loadLocal() }
            _state.update {
                it.copy(
                    library = library,
                    encryptCache = if (ok) enabled else it.encryptCache,
                    message = if (ok) (if (enabled) "Local cache encrypted" else "Local cache is plain again") else "Could not change cache encryption",
                )
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun acceptSharedText(text: String) {
        _state.update { it.copy(captureText = text, tab = AppTab.TODOS) }
    }

    private suspend fun connectAndSync(config: BridgeConfig) = QuillGraph.withSyncLock {
        try {
            bridgeClient.health(config)
            val library = repository.sync(config)
            _state.update { it.copy(connection = ConnectionStatus.CONNECTED, config = config, library = library, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths(), message = null) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _state.update { it.copy(connection = ConnectionStatus.OFFLINE, config = config, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths(), message = "PC offline; changes will sync when it returns") }
        }
    }

    private suspend fun sync(config: BridgeConfig, silent: Boolean = false) = QuillGraph.withSyncLock {
        try {
            val library = repository.sync(config)
            _state.update { it.copy(connection = ConnectionStatus.CONNECTED, library = library, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths(), message = if (silent) it.message else null) }
            ReminderScheduler.reschedule(getApplication())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _state.update { it.copy(connection = ConnectionStatus.OFFLINE, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths(), message = if (silent) it.message else (error.message ?: "PC offline")) }
        }
    }

    private fun runCommand(command: String) {
        val parts = command.trim().split(Regex("\\s+"), limit = 2)
        val name = parts.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
        val rest = parts.getOrNull(1).orEmpty()
        when (name) {
            "/clear" -> _state.update { it.copy(askMessages = emptyList(), message = null) }
            "/plan" -> runPlan()
            "/review" -> runReview()
            "/todo" -> if (rest.isBlank()) _state.update { it.copy(message = "Usage: /todo <text>") } else {
                _state.update { it.copy(captureText = rest, tab = AppTab.TODOS) }
                capture()
            }
            "/note" -> if (rest.isBlank()) _state.update { it.copy(message = "Usage: /note <title>") } else {
                val config = _state.value.config
                launchTask {
                    val created = QuillGraph.withSyncLock { repository.createNote(rest, config = config) }
                    _state.update { it.copy(library = created.second, selectedNote = created.first, noteTitleDraft = created.first.title, editorText = created.first.body, tab = AppTab.NOTES) }
                }
            }
            "/daily" -> openDaily()
            "/undo" -> undo()
            "/copy" -> copyLastAnswer()
            "/save" -> {
                val answer = _state.value.askMessages.lastOrNull { it.role == "assistant" }?.content
                if (answer.isNullOrBlank()) {
                    _state.update { it.copy(message = "Nothing to save yet") }
                } else {
                    val config = _state.value.config
                    launchTask {
                        val title = rest.ifBlank { "AI answer" }
                        val created = QuillGraph.withSyncLock { repository.createNote(title, answer, config) }
                        _state.update { it.copy(library = created.second, message = "Saved as ${created.first.title}") }
                    }
                }
            }
            "/help" -> _state.update { it.copy(askMessages = it.askMessages + AskMessage("assistant", "Commands: /clear, /plan, /review, /todo <text>, /note <title>, /daily, /save [title], /copy, /undo")) }
            else -> _state.update { it.copy(askMessages = it.askMessages + AskMessage("error", "Unknown command. Try /help")) }
        }
    }

    private fun localMessage(message: String): String {
        return if (repository.pendingCount() > 0) "$message locally; sync pending" else message
    }

    private fun buildAskContext(question: String): String {
        val library = MarkdownParser.search(_state.value.library, question)
        val selectedNotes = if (library.notes.isEmpty()) _state.value.library.notes.take(4) else library.notes.take(4)
        val parts = mutableListOf<String>()
        val openTodos = _state.value.library.todos.filter { !it.done }.take(30)
        if (openTodos.isNotEmpty()) {
            parts += "## Open todos\n" + openTodos.joinToString("\n") { "- ${it.text}" }
        }
        selectedNotes.forEach { note ->
            parts += "## ${note.title} (${note.path})\n${note.body.take(6000)}"
        }
        return parts.joinToString("\n\n").ifBlank { "(no notes or todos found)" }
    }

    private fun launchTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val text = when (error) {
                    is BridgeException -> if (error.statusCode == 409) "Changed on the PC — use Keep mine or Keep PC" else error.message
                    else -> error.message ?: "Something went wrong"
                }
                _state.update { it.copy(message = text) }
                if (error is BridgeException && error.statusCode == 409) {
                    _state.value.config?.let { config ->
                        runCatching { QuillGraph.withSyncLock { repository.sync(config) } }
                            .onSuccess { library -> _state.update { it.copy(library = library) } }
                    }
                }
            } finally {
                _state.update { it.copy(busy = false, pendingChanges = repository.pendingCount(), conflicts = repository.conflictCount(), conflictPaths = repository.conflictPaths()) }
            }
        }
    }
}
