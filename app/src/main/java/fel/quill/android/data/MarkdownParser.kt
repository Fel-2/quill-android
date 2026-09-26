package fel.quill.android.data

import fel.quill.android.model.AiTodo
import fel.quill.android.model.Counts
import fel.quill.android.model.Library
import fel.quill.android.model.NoteDocument
import fel.quill.android.model.Todo
import fel.quill.android.model.TagCount
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedNote(
    val document: NoteDocument,
    val todos: List<Todo>,
)

object MarkdownParser {
    private val todoPattern = Regex("^(\\s*)[-*] \\[([ xX])\\] ?(.*)$")
    private val headingPattern = Regex("^\\s*#+\\s*(.+)$")
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val duePattern = Regex("(\\d{4}-\\d{2}-\\d{2})(?:[T ]+(\\d{1,2}:\\d{2}))?")
    private val timePattern = Regex("(\\d{1,2}):(\\d{2})")
    private val tagPattern = Regex("#([\\w\\-/]+)")
    private val recurrencePattern = Regex("@(daily|weekly|monthly)", RegexOption.IGNORE_CASE)
    private val metaPattern = Regex("^([\\w_-]+):\\s*(.*)$")

    fun parse(path: String, content: String, modifiedAt: Long): ParsedNote {
        val split = splitFrontmatter(content)
        val meta = parseMeta(split.first)
        val title = noteTitle(path, meta, split.second)
        val tags = parseTags(meta["tags"])
        val noteTagSeen = tags.toMutableSet()
        val todos = mutableListOf<Todo>()
        var openCount = 0
        var doneCount = 0
        val lines = split.second.lines()
        val bodyStart = if (split.first.isEmpty()) 1 else content.lines().size - lines.size + 1
        lines.forEachIndexed { index, line ->
            val match = todoPattern.matchEntire(line) ?: return@forEachIndexed
            val mark = match.groupValues[2]
            val rest = match.groupValues[3].trim()
            val todoTags = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            tagPattern.findAll(rest).forEach { matchTag ->
                val tag = matchTag.groupValues[1].lowercase(Locale.ROOT)
                if (seen.add(tag)) {
                    todoTags += tag
                }
            }
            tags.addAll(todoTags.filter { noteTagSeen.add(it) })
            val dueMatch = duePattern.find(rest)
            val due = dueMatch?.groupValues?.get(1)
            val time = normalizeTime(dueMatch?.groupValues?.get(2))
            val recurrence = recurrencePattern.find(rest)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            val priority = when {
                rest.contains("!!") || rest.contains("#urgent") -> 2
                rest.contains("!p1") || rest.contains("#important") -> 1
                else -> 0
            }
            val text = cleanTodoText(rest)
            if (text.isNotEmpty()) {
                val lineNumber = bodyStart + index
                val done = mark.equals("x", ignoreCase = true)
                if (done) doneCount++ else openCount++
                todos += Todo(
                    id = "$path:$lineNumber",
                    text = text,
                    done = done,
                    due = due,
                    time = time,
                    recurrence = recurrence,
                    priority = priority,
                    tags = todoTags,
                    path = path,
                    line = lineNumber,
                    title = title,
                    raw = line,
                )
            }
        }
        val document = NoteDocument(
            path = path,
            title = title,
            preview = notePreview(split.second),
            tags = tags.distinct(),
            body = split.second.trimEnd('\n'),
            frontmatter = split.first,
            raw = content,
            modifiedAt = modifiedAt,
            openTodos = openCount,
            doneTodos = doneCount,
        )
        return ParsedNote(document, todos)
    }

    fun parseLibrary(files: List<Pair<String, String>>, modifiedAt: (String) -> Long): Library {
        val notes = mutableListOf<NoteDocument>()
        val todos = mutableListOf<Todo>()
        files.forEach { (path, content) ->
            val parsed = parse(path, content, modifiedAt(path))
            if (!path.substringAfterLast('/').equals("Archive.md", ignoreCase = true)) {
                notes += parsed.document
                todos += parsed.todos
            }
        }
        notes.sortByDescending { it.modifiedAt }
        val rolled = todos.map { todo ->
            if (!todo.done && todo.recurrence != null) {
                val next = rollRecurrence(todo.due, todo.recurrence)
                if (next != todo.due) todo.copy(due = next, rolled = true) else todo
            } else {
                todo
            }
        }
        todos.clear()
        todos.addAll(rolled)
        todos.sortWith(todoComparator())
        val today = today()
        var open = 0
        var done = 0
        var dueToday = 0
        var overdue = 0
        val tags = LinkedHashMap<String, Int>()
        todos.forEach { todo ->
            if (todo.done) {
                done++
            } else {
                open++
                val due = todo.due
                if (due != null) {
                    val overdueNow = isOverdue(due, todo.time)
                    if (due == today && !overdueNow) dueToday++
                    if (overdueNow) overdue++
                }
            }
            todo.tags.forEach { tag -> tags[tag] = (tags[tag] ?: 0) + 1 }
        }
        return Library(
            notes = notes,
            todos = todos,
            counts = Counts(open, done, dueToday, overdue, notes.size),
            tags = tags.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { TagCount(it.key, it.value) },
        )
    }

    fun setDone(raw: String, lineNumber: Int, done: Boolean): String {
        val lines = raw.lines().toMutableList()
        val index = lineNumber - 1
        if (index !in lines.indices) return raw
        val match = todoPattern.matchEntire(lines[index]) ?: return raw
        val mark = if (done) "x" else " "
        val bullet = if (lines[index].trimStart().startsWith("*")) "*" else "-"
        val replacement = match.groupValues[1] + bullet + " [" + mark + "] " + match.groupValues[3]
        lines[index] = replacement
        return joinLines(lines)
    }

    fun setDue(raw: String, lineNumber: Int, due: String?, time: String? = null): String {
        val lines = raw.lines().toMutableList()
        val index = lineNumber - 1
        if (index !in lines.indices) return raw
        val match = todoPattern.matchEntire(lines[index]) ?: return raw
        var rest = match.groupValues[3]
        val matchedDate = datePattern.containsMatchIn(match.groupValues[3])
        rest = rest.replace(Regex("📅\\s*\\d{4}-\\d{2}-\\d{2}(?:[T ]+\\d{1,2}:\\d{2})?"), "")
        rest = rest.replace(Regex("[Dd]ue:\\s*\\d{4}-\\d{2}-\\d{2}(?:[T ]+\\d{1,2}:\\d{2})?"), "")
        if (matchedDate) rest = rest.replace(Regex("\\s+\\d{1,2}:\\d{2}\\s*$"), " ")
        rest = collapseSpaces(rest)
        val cleanTime = normalizeTime(time)
        val suffix = if (due.isNullOrBlank() || !datePattern.matches(due)) "" else " 📅 $due" + (cleanTime?.let { " $it" } ?: "")
        val bullet = if (lines[index].trimStart().startsWith("*")) "*" else "-"
        lines[index] = match.groupValues[1] + bullet + " [" + match.groupValues[2] + "] " + rest + suffix
        return joinLines(lines)
    }

    fun deleteTodo(raw: String, lineNumber: Int): String {
        val lines = raw.lines().toMutableList()
        val index = lineNumber - 1
        if (index !in lines.indices || todoPattern.matchEntire(lines[index]) == null) return raw
        lines.removeAt(index)
        return joinLines(lines)
    }

    fun appendTodo(
        raw: String,
        text: String,
        due: String? = null,
        time: String? = null,
        tags: List<String> = emptyList(),
        priority: Int = 0,
        recurrence: String? = null,
    ): String {
        val clean = collapseSpaces(text)
        val builder = StringBuilder("- [ ] ").append(clean)
        if (!due.isNullOrBlank() && datePattern.matches(due)) {
            builder.append(" 📅 ").append(due)
            normalizeTime(time)?.let { builder.append(" ").append(it) }
        }
        if (priority == 2) builder.append(" #urgent")
        if (priority == 1) builder.append(" #important")
        if (recurrence in listOf("daily", "weekly", "monthly")) builder.append(" @").append(recurrence)
        tags.map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() && it.matches(Regex("[\\w\\-/]+")) }
            .distinct()
            .forEach { builder.append(" #").append(it) }
        val prefix = if (raw.isEmpty() || raw.endsWith("\n")) "" else "\n"
        return raw + prefix + builder + "\n"
    }

    fun createNote(title: String, body: String = ""): Pair<String, String> {
        val cleanTitle = collapseSpaces(title).ifEmpty { "Untitled" }
        val slug = slugify(cleanTitle).ifEmpty { "note-${System.currentTimeMillis() / 1000}" }
        val front = "---\ntitle: ${yamlString(cleanTitle)}\ncreated: ${LocalDate.now()}T${java.time.LocalTime.now().withNano(0)}\n---\n"
        val content = if (body.startsWith("#")) {
            front + "\n" + body.trimEnd('\n') + "\n"
        } else {
            front + "\n# $cleanTitle\n" + if (body.isBlank()) "" else "\n" + body.trimEnd('\n') + "\n"
        }
        return slug + ".md" to content
    }

    fun serializeBody(document: NoteDocument, body: String, title: String = document.title): String {
        val normalizedBody = body.trimEnd('\n')
        val frontmatter = updatedFrontmatter(document, title)
        val value = if (frontmatter.isEmpty()) normalizedBody else frontmatter.trimEnd('\n') + "\n" + normalizedBody
        return if (value.endsWith("\n")) value else value + "\n"
    }

    fun search(library: Library, query: String): Library {
        val value = query.trim().lowercase(Locale.ROOT)
        if (value.isEmpty()) return library
        val terms = value.split(Regex("\\s+")).filter { it.length > 1 }
        val notes = library.notes.filter { note ->
            terms.any { term -> note.title.lowercase(Locale.ROOT).contains(term) || note.body.lowercase(Locale.ROOT).contains(term) || note.tags.any { it.contains(term) } }
        }
        val todos = library.todos.filter { todo ->
            terms.any { term -> todo.text.lowercase(Locale.ROOT).contains(term) || todo.tags.any { it.contains(term) } }
        }
        return library.copy(notes = notes, todos = todos)
    }

    fun nextRecurrence(due: String?, rule: String, from: LocalDate = LocalDate.now()): String? {
        val date = due?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        var next = date
        var guard = 0
        while (next < from && guard < 400) {
            next = when (rule) {
                "daily" -> next.plusDays(1)
                "weekly" -> next.plusDays(7)
                "monthly" -> next.plusMonths(1)
                else -> return null
            }
            guard++
        }
        return next.toString()
    }

    fun rollRecurrence(due: String?, rule: String?, from: LocalDate = LocalDate.now()): String? {
        if (due.isNullOrBlank() || rule.isNullOrBlank()) return due
        val parsed = runCatching { LocalDate.parse(due) }.getOrNull() ?: return due
        if (!parsed.isBefore(from)) return due
        if (rule == "monthly") return nextRecurrence(due, rule, from) ?: due
        if (rule != "daily" && rule != "weekly") return due
        val step = if (rule == "weekly") 7L else 1L
        val advance = from.toEpochDay() - parsed.toEpochDay()
        if (advance <= 0) return due
        var candidate = parsed.plusDays((advance / step) * step)
        while (candidate < from) {
            candidate = candidate.plusDays(step)
        }
        return candidate.toString()
    }

    fun parseDueHint(text: String, today: LocalDate = LocalDate.now()): Pair<String?, String?> {
        val lower = text.lowercase(Locale.ROOT)
        val year = today.year
        var date: String? = null

        Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(lower)?.let {
            date = runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()).toString() }.getOrNull()
        }
        if (date == null) {
            Regex("(\\d{1,2})[./](\\d{1,2})").find(lower)?.let {
                val day = it.groupValues[1].toIntOrNull()
                val month = it.groupValues[2].toIntOrNull()
                if (day != null && month != null && day in 1..31 && month in 1..12) {
                    date = runCatching { LocalDate.of(year, month, day).toString() }.getOrNull()
                }
            }
        }
        if (date == null && word(lower, "today")) date = today.toString()
        if (date == null && word(lower, "tomorrow")) date = today.plusDays(1).toString()
        if (date == null && word(lower, "yesterday")) date = today.minusDays(1).toString()
        val relative = Regex("\\bin\\s+(\\d+)\\s*(day|week|month)").find(lower)
        if (date == null && relative != null) {
            val count = relative.groupValues[1].toIntOrNull() ?: 0
            date = when (relative.groupValues[2]) {
                "day" -> today.plusDays(count.toLong())
                "week" -> today.plusDays(count * 7L)
                else -> today.plusMonths(count.toLong())
            }.toString()
        }
        if (date == null && word(lower, "next") && word(lower, "week")) date = today.plusDays(7).toString()
        if (date == null && word(lower, "next") && word(lower, "month")) date = today.plusMonths(1).toString()
        if (date == null) {
            Regex("\\bnext\\s+(\\w{3})").find(lower)?.let { date = weekday(it.groupValues[1], nextWeek = true) }
        }
        if (date == null) {
            Regex("\\b(mon|tue|wed|thu|fri|sat|sun)").find(lower)?.let { date = weekday(it.groupValues[1], nextWeek = false) }
        }

        return date to clockTime(lower)
    }

    private fun weekday(token: String, nextWeek: Boolean): String? {
        val target = mapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
        )[token.take(3).lowercase(Locale.ROOT)] ?: return null
        val today = LocalDate.now()
        var delta = (target.value - today.dayOfWeek.value + 7) % 7
        if (delta == 0) delta = 7
        if (nextWeek) delta += 7
        return today.plusDays(delta.toLong()).toString()
    }

    private fun clockTime(text: String): String? {
        Regex("(\\d{1,2}):(\\d{2})").find(text)?.let {
            val hour = it.groupValues[1].toIntOrNull() ?: return@let
            val minute = it.groupValues[2].toIntOrNull() ?: return@let
            if (hour in 0..23 && minute in 0..59) return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
            return null
        }
        val hourToken: Pair<Int?, String>? = Regex("(\\d{1,2})\\s*(am|pm)").find(text)?.let { it.groupValues[1].toIntOrNull() to it.groupValues[2] }
            ?: Regex("(am|pm)\\s*(\\d{1,2})").find(text)?.let { it.groupValues[2].toIntOrNull() to it.groupValues[1] }
        if (hourToken == null) return null
        val hour = hourToken.first ?: return null
        val meridiem = hourToken.second
        if (hour !in 1..12) return null
        val adjusted = when {
            meridiem == "pm" && hour < 12 -> hour + 12
            meridiem == "am" && hour == 12 -> 0
            else -> hour
        }
        return String.format(Locale.ROOT, "%02d:00", adjusted)
    }

    private fun word(text: String, token: String): Boolean = Regex("\\b$token\\b").containsMatchIn(text)

    fun todoComparator(): Comparator<Todo> = Comparator { first, second ->
        if (first.done != second.done) return@Comparator if (first.done) 1 else -1
        if (first.due != null && second.due != null && first.due != second.due) return@Comparator first.due.compareTo(second.due)
        if (first.due != null && second.due != null) {
            val firstTime = first.time ?: "99:99"
            val secondTime = second.time ?: "99:99"
            if (firstTime != secondTime) return@Comparator firstTime.compareTo(secondTime)
        }
        if (first.due != null && second.due == null) return@Comparator -1
        if (first.due == null && second.due != null) return@Comparator 1
        if (first.priority != second.priority) return@Comparator second.priority.compareTo(first.priority)
        val path = first.path.compareTo(second.path)
        if (path != 0) path else first.line.compareTo(second.line)
    }

    fun safeProjectPath(value: String?): String? {
        val raw = value?.trim()?.replace('\\', '/') ?: return null
        if (raw.isEmpty()) return null
        val cleaned = raw.trimEnd('/')
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains('\u0000')) return null
        val segments = cleaned.split('/')
        if (segments.any { it.isEmpty() || it == ".." || it == "~" }) return null
        return if (cleaned.endsWith(".md", ignoreCase = true)) cleaned else "$cleaned.md"
    }

    fun normalizeTime(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = timePattern.matchEntire(raw) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    }

    fun dueLabel(due: String?, time: String? = null): String {
        if (due.isNullOrBlank()) return ""
        val date = runCatching { LocalDate.parse(due) }.getOrNull() ?: return due
        val todayDate = LocalDate.now()
        val label = when {
            date.isBefore(todayDate) -> "${todayDate.toEpochDay() - date.toEpochDay()}d overdue"
            date == todayDate -> "today"
            date == todayDate.plusDays(1) -> "tomorrow"
            date.isAfter(todayDate.plusDays(7)) -> date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ROOT))
            else -> "in ${date.toEpochDay() - todayDate.toEpochDay()}d"
        }
        val cleanTime = normalizeTime(time)
        return if (cleanTime == null) label else "$label $cleanTime"
    }

    fun isOverdue(due: String?, time: String?): Boolean {
        if (due.isNullOrBlank()) return false
        val date = runCatching { LocalDate.parse(due) }.getOrNull() ?: return false
        val today = LocalDate.now()
        if (date.isBefore(today)) return true
        val cleanTime = normalizeTime(time)
        if (date.isAfter(today) || cleanTime == null) return false
        return cleanTime < java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
    }

    fun relativeTime(epochMillis: Long): String {
        val seconds = ((System.currentTimeMillis() - epochMillis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 45 -> "just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }

    fun stripMarkdown(value: String): String {
        var result = value
        result = result.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        result = result.replace(Regex("\\[\\[([^]]+)]]"), "$1")
        result = result.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        result = result.replace(Regex("`([^`]+)`"), "$1")
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        result = result.replace(Regex("__([^_]+)__"), "$1")
        result = result.replace(Regex("^\\s*#+\\s*"), "")
        result = result.replace(Regex("^\\s*[-*+]\\s+"), "")
        return collapseSpaces(result)
    }

    fun slugify(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
            .trim('-')
    }

    private fun updatedFrontmatter(document: NoteDocument, title: String): String {
        val cleanTitle = collapseSpaces(title).ifBlank { document.title }
        if (document.frontmatter.isEmpty()) {
            return if (cleanTitle == document.title) "" else "---\ntitle: ${yamlString(cleanTitle)}\n---\n"
        }
        val lines = document.frontmatter.trimEnd('\n').lines().toMutableList()
        var replaced = false
        for (index in lines.indices) {
            if (lines[index].startsWith("title:")) {
                lines[index] = "title: ${yamlString(cleanTitle)}"
                replaced = true
                break
            }
        }
        if (!replaced && lines.isNotEmpty()) lines.add(1, "title: ${yamlString(cleanTitle)}")
        return lines.joinToString("\n") + "\n"
    }

    private fun splitFrontmatter(content: String): Pair<String, String> {
        val lines = content.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") return "" to content
        val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return "" to content
        val front = lines.take(close + 1).joinToString("\n") + "\n"
        val body = lines.drop(close + 1).joinToString("\n")
        return front to body
    }

    private fun parseMeta(frontmatter: String): Map<String, String> {
        if (frontmatter.isEmpty()) return emptyMap()
        return frontmatter.lines().mapNotNull { line ->
            val match = metaPattern.matchEntire(line.trim()) ?: return@mapNotNull null
            match.groupValues[1].lowercase(Locale.ROOT) to match.groupValues[2].trim()
        }.toMap()
    }

    private fun parseTags(value: String?): MutableList<String> {
        val result = mutableListOf<String>()
        val raw = value?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        raw.split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.forEach { result += it }
        return result
    }

    private fun noteTitle(path: String, meta: Map<String, String>, body: String): String {
        meta["title"]?.let { return it.trim('"', '\'') }
        headingPattern.find(body)?.groupValues?.get(1)?.let { return stripMarkdown(it) }
        return path.substringAfterLast('/').removeSuffix(".md")
    }

    private fun notePreview(body: String): String {
        var seen = 0
        body.lines().forEach { line ->
            seen++
            if (seen > 40) return@forEach
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && todoPattern.matchEntire(trimmed) == null) {
                return stripMarkdown(trimmed).take(140)
            }
        }
        return ""
    }

    private fun cleanTodoText(value: String): String {
        var text = value
        text = text.replace(tagPattern, "")
        text = text.replace(Regex("📅\\s*\\d{4}-\\d{2}-\\d{2}(?:[T ]+\\d{1,2}:\\d{2})?"), "")
        text = text.replace(Regex("[Dd]ue:\\s*\\d{4}-\\d{2}-\\d{2}(?:[T ]+\\d{1,2}:\\d{2})?"), "")
        if (datePattern.containsMatchIn(value)) text = text.replace(Regex("\\s+\\d{1,2}:\\d{2}\\s*$"), "")
        text = text.replace(Regex("@[A-Za-z]+"), "")
        text = text.replace("!!", "")
        text = text.replace("!p1", "")
        text = text.replace("**", "")
        return collapseSpaces(text)
    }

    private fun collapseSpaces(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun yamlString(value: String): String {
        if (value.matches(Regex("^[\\w\\s\\-_.(),:/']+$"))) return value
        return "\"${value.replace("\"", "\\\"")}\""
    }

    private fun joinLines(lines: List<String>): String = lines.joinToString("\n")

    private fun today(): String = LocalDate.now().toString()
}
