package fel.quill.android.model

import java.time.LocalDate

enum class AppTab(val label: String) {
    TODOS("Todos"),
    NOTES("Notes"),
    CALENDAR("Calendar"),
    ASK("Ask"),
}

data class BridgeConfig(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val fingerprint: String,
    val token: String,
    val deviceName: String,
)

data class DiscoveredBridge(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val fingerprint: String,
)

data class ManifestFile(
    val path: String,
    val etag: String,
    val size: Long,
    val modifiedAt: Long,
)

data class Manifest(
    val revision: String,
    val files: List<ManifestFile>,
)

data class RemoteFile(
    val entry: ManifestFile,
    val content: String,
)

data class Todo(
    val id: String,
    val text: String,
    val done: Boolean,
    val due: String?,
    val time: String?,
    val recurrence: String?,
    val priority: Int,
    val tags: List<String>,
    val path: String,
    val line: Int,
    val title: String,
    val raw: String,
    val rolled: Boolean = false,
)

data class NoteDocument(
    val path: String,
    val title: String,
    val preview: String,
    val tags: List<String>,
    val body: String,
    val frontmatter: String,
    val raw: String,
    val modifiedAt: Long,
    val openTodos: Int,
    val doneTodos: Int,
)

data class Counts(
    val open: Int = 0,
    val done: Int = 0,
    val dueToday: Int = 0,
    val overdue: Int = 0,
    val notes: Int = 0,
)

data class Library(
    val notes: List<NoteDocument> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val counts: Counts = Counts(),
    val tags: List<TagCount> = emptyList(),
)

data class TagCount(val tag: String, val count: Int)

data class AskMessage(
    val role: String,
    val content: String,
    val sources: List<String> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString(),
)

data class CaptureResult(
    val kind: String,
    val text: String,
    val body: String,
    val due: String?,
    val time: String?,
    val file: String?,
    val tags: List<String>,
    val priority: Int,
)

data class AiTodo(
    val text: String,
    val due: String?,
    val time: String?,
    val priority: Int,
)

data class AiConfig(
    val provider: String = "opencode-go",
    val model: String = "deepseek-v4-flash",
    val baseUrl: String = "",
    val apiKey: String = "",
    val maxTokens: Int = 0,
    val aiCapture: Boolean = true,
)

data class TodoFilter(val label: String, val value: String) {
    companion object {
        val all = TodoFilter("All", "all")
        val today = TodoFilter("Today", "today")
        val overdue = TodoFilter("Overdue", "overdue")
        val important = TodoFilter("Important", "important")
    }
}

fun todayIso(): String = LocalDate.now().toString()
