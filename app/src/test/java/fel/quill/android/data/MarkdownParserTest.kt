package fel.quill.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarkdownParserTest {
    @Test
    fun parsesFrontmatterAndTodos() {
        val content = """
            ---
            title: Project plan
            tags: [work, active]
            ---
            # Project plan
            - [ ] Call the dentist 📅 2026-09-24 #health !p1
            - [x] Buy milk @weekly
        """.trimIndent()
        val parsed = MarkdownParser.parse("Project.md", content, 10L)
        assertEquals("Project plan", parsed.document.title)
        assertEquals(2, parsed.todos.size)
        assertEquals("Call the dentist", parsed.todos[0].text)
        assertEquals("2026-09-24", parsed.todos[0].due)
        assertEquals(1, parsed.todos[0].priority)
        assertTrue(parsed.document.tags.contains("health"))
        assertEquals(2, parsed.document.openTodos + parsed.document.doneTodos)
    }

    @Test
    fun updatesTodoMetadataInPlace() {
        val content = "# Inbox\n- [ ] Pay rent 📅 2026-09-24 #home\n"
        val done = MarkdownParser.setDone(content, 2, true)
        assertTrue(done.contains("- [x] Pay rent"))
        val due = MarkdownParser.setDue(done, 2, "2026-10-01")
        assertTrue(due.contains("📅 2026-10-01"))
        assertTrue(!due.contains("2026-09-24"))
    }

    @Test
    fun todoLinesPointAtTheRightLineWithFrontmatter() {
        val content = "---\ntitle: Project plan\n---\n# Project plan\n- [ ] Call the dentist\n- [ ] Buy milk\n"
        val parsed = MarkdownParser.parse("Project.md", content, 0L)
        assertEquals(listOf(5, 6), parsed.todos.map { it.line })
        val done = MarkdownParser.setDone(content, parsed.todos[0].line, true)
        assertTrue(done.lines()[4].contains("[x] Call the dentist"))
        assertTrue(done.lines()[5].contains("[ ] Buy milk"))
        val deleted = MarkdownParser.deleteTodo(content, parsed.todos[0].line)
        assertTrue(!deleted.contains("Call the dentist"))
        assertTrue(deleted.contains("Buy milk"))
    }

    @Test
    fun stripsTrailingTimeWithoutADate() {
        val parsed = MarkdownParser.parse("Inbox.md", "# Inbox\n- [ ] Call mom 📅 2026-10-01 14:30 9:15\n", 0L)
        assertEquals("14:30", parsed.todos[0].time)
        assertEquals("Call mom", parsed.todos[0].text)
        val updated = MarkdownParser.setDue("- [ ] Call mom 📅 2026-10-01 14:30 9:15\n", 1, "2026-10-02", "18:30")
        assertEquals("- [ ] Call mom 📅 2026-10-02 18:30\n", updated)
    }

    @Test
    fun setDueKeepsOnlyTheIntendedTime() {
        val content = "# Inbox\n- [ ] Submit report 📅 2026-09-24 14:30 #work\n"
        val parsed = MarkdownParser.parse("Inbox.md", content, 0L)
        assertEquals(2, parsed.todos[0].line)
        assertEquals("14:30", parsed.todos[0].time)
        val updated = MarkdownParser.setDue(content, parsed.todos[0].line, "2026-09-25", "09:00")
        val line = updated.lines().first { it.contains("Submit report") }
        assertEquals("- [ ] Submit report #work 📅 2026-09-25 09:00", line)
    }

    @Test
    fun parsesTimedTodos() {
        val content = "# Inbox\n- [ ] Submit report 📅 2026-09-24 14:30 #work\n- [ ] Date only 📅 2026-09-25\n"
        val parsed = MarkdownParser.parse("Inbox.md", content, 0L)
        assertEquals("14:30", parsed.todos[0].time)
        assertEquals("Submit report", parsed.todos[0].text)
        assertEquals(null, parsed.todos[1].time)
    }

    @Test
    fun normalizesTimeValues() {
        assertEquals("09:05", MarkdownParser.normalizeTime("9:05"))
        assertEquals("23:59", MarkdownParser.normalizeTime("23:59"))
        assertEquals(null, MarkdownParser.normalizeTime("24:00"))
        assertEquals(null, MarkdownParser.normalizeTime("12:60"))
        assertEquals(null, MarkdownParser.normalizeTime("noon"))
    }

    @Test
    fun appendsAndUpdatesTimedTodos() {
        val appended = MarkdownParser.appendTodo("# Inbox\n", "Task", "2026-10-01", "9:00")
        assertTrue(appended.contains("📅 2026-10-01 09:00"))
        val updated = MarkdownParser.setDue(appended, 2, "2026-10-02", "18:30")
        assertTrue(updated.contains("📅 2026-10-02 18:30"))
        assertTrue(!updated.contains("2026-10-01"))
        val cleared = MarkdownParser.setDue(updated, 2, null, null)
        assertTrue(!cleared.contains("📅"))
    }

    @Test
    fun sortsTimedTodosByTime() {
        val library = MarkdownParser.parseLibrary(
            listOf(
                "Inbox.md" to "# Inbox\n- [ ] Morning 📅 2026-09-24 09:00\n- [ ] Evening 📅 2026-09-24 18:00\n- [ ] Timeless 📅 2026-09-24\n",
            ),
        ) { 0L }
        assertEquals("Morning", library.todos[0].text)
        assertEquals("Evening", library.todos[1].text)
        assertEquals("Timeless", library.todos[2].text)
    }

    @Test
    fun normalizesProjectPaths() {
        assertEquals("Work.md", MarkdownParser.safeProjectPath("Work"))
        assertEquals("work.md", MarkdownParser.safeProjectPath("work.md"))
        assertEquals("Home Renovation.md", MarkdownParser.safeProjectPath("Home Renovation"))
        assertEquals("Work/Backend.md", MarkdownParser.safeProjectPath("Work/Backend"))
        assertEquals(null, MarkdownParser.safeProjectPath(null))
        assertEquals(null, MarkdownParser.safeProjectPath(""))
        assertEquals(null, MarkdownParser.safeProjectPath(".."))
        assertEquals(null, MarkdownParser.safeProjectPath("../Work"))
        assertEquals(null, MarkdownParser.safeProjectPath("/etc/passwd"))
        assertEquals(null, MarkdownParser.safeProjectPath("Work/../../etc"))
    }

    @Test
    fun updatesNoteTitleInFrontmatter() {
        val parsed = MarkdownParser.parse("note.md", "---\ntitle: Old\n---\n\nBody\n", 0L)
        val updated = MarkdownParser.serializeBody(parsed.document, "New body", "New title")
        assertEquals("New title", MarkdownParser.parse("note.md", updated, 0L).document.title)
        assertTrue(updated.contains("New body"))
    }

    @Test
    fun groupsTodosByDueDateForCalendar() {
        val library = MarkdownParser.parseLibrary(
            listOf(
                "Inbox.md" to "# Inbox\n- [ ] Morning 📅 2026-09-24 09:00\n- [ ] Evening 📅 2026-09-24 18:00\n- [ ] Next day 📅 2026-09-25\n- [ ] No date\n",
            ),
        ) { 0L }
        val byDate = library.todos.filter { it.due != null }.groupBy { it.due!! }
        assertEquals(2, byDate["2026-09-24"]?.size)
        assertEquals(1, byDate["2026-09-25"]?.size)
        assertEquals(null, byDate[""])
        assertEquals(listOf("Morning", "Evening"), byDate["2026-09-24"]!!.sortedWith(MarkdownParser.todoComparator()).map { it.text })
        assertEquals(listOf("09:00", "18:00"), byDate["2026-09-24"]!!.sortedWith(MarkdownParser.todoComparator()).map { it.time })
    }

    @Test
    fun rollsPastRecurringDueDatesForDisplay() {
        val today = LocalDate.of(2026, 9, 26)
        // Roll forward to the first occurrence that is not in the past.
        assertEquals("2026-09-26", MarkdownParser.rollRecurrence("2026-09-20", "daily", today))
        assertEquals("2026-09-26", MarkdownParser.rollRecurrence("2026-09-12", "weekly", today))
        assertEquals("2026-10-15", MarkdownParser.rollRecurrence("2026-08-15", "monthly", today))
        // A due date that is today is not "past", so it is left alone.
        assertEquals("2026-09-26", MarkdownParser.rollRecurrence("2026-09-26", "daily", today))
        assertEquals(null, MarkdownParser.rollRecurrence(null, "daily", today))
        assertEquals("2026-09-20", MarkdownParser.rollRecurrence("2026-09-20", null, today))
    }

    @Test
    fun monthlyRecurrenceClampsLikeStepwiseMonths() {
        // java.time clamps each step: Jan 31 -> Feb 28 -> ... -> Sep 28.
        assertEquals("2026-09-28", MarkdownParser.rollRecurrence("2025-01-31", "monthly", LocalDate.of(2026, 9, 26)))
        assertEquals("2026-09-28", MarkdownParser.rollRecurrence("2024-02-29", "monthly", LocalDate.of(2026, 9, 26)))
        assertEquals("2026-10-15", MarkdownParser.rollRecurrence("2025-08-15", "monthly", LocalDate.of(2026, 9, 26)))
        // A jump of whole months must not skip the clamped month.
        assertEquals("2026-03-28", MarkdownParser.rollRecurrence("2025-01-31", "monthly", LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun longStaleRecurrenceJumpsWithoutStepping() {
        val today = LocalDate.of(2026, 9, 26)
        assertEquals("2026-09-26", MarkdownParser.rollRecurrence("2024-01-15", "daily", today))
        // 2026-09-12 + 7d -> 09-19 -> 09-26, which is today, so it lands today.
        assertEquals("2026-09-26", MarkdownParser.rollRecurrence("2026-09-12", "weekly", today))
        // An unknown rule leaves the todo alone instead of rolling it.
        assertEquals("2025-09-20", MarkdownParser.rollRecurrence("2025-09-20", "fortnightly", today))
    }

    @Test
    fun parsesNaturalLanguageDueDates() {
        val today = LocalDate.of(2026, 9, 26)
        assertEquals("2026-09-27" to null, MarkdownParser.parseDueHint("buy milk tomorrow", today))
        assertEquals("2026-09-27" to "09:30", MarkdownParser.parseDueHint("standup tomorrow at 9:30", today))
        assertEquals("2026-12-01" to null, MarkdownParser.parseDueHint("renew passport 2026-12-01", today))
        assertEquals("2026-09-29" to null, MarkdownParser.parseDueHint("in 3 days", today))
        assertEquals("2026-10-10" to null, MarkdownParser.parseDueHint("in 2 weeks", today))
        assertEquals("2026-10-03" to null, MarkdownParser.parseDueHint("dentist next week", today))
        assertEquals(null to "14:00", MarkdownParser.parseDueHint("call 2pm", today))
        assertEquals(null to "23:45", MarkdownParser.parseDueHint("late thing 23:45", today))
        assertEquals(null to null, MarkdownParser.parseDueHint("just a plain note", today))
    }

    @Test
    fun countsTagsForFiltering() {
        val library = MarkdownParser.parseLibrary(
            listOf("Inbox.md" to "# Inbox\n- [ ] A #work\n- [ ] B #work #home\n- [x] C #work\n"),
        ) { 0L }
        assertEquals(3, library.tags.first { it.tag == "work" }.count)
        assertEquals(1, library.tags.first { it.tag == "home" }.count)
        assertEquals("work", library.tags.first().tag)
    }

    @Test
    fun archiveIsNotIndexed() {
        val library = MarkdownParser.parseLibrary(
            listOf(
                "Archive.md" to "# Archive\n- [x] old task\n",
                "Inbox.md" to "# Inbox\n- [ ] current task\n",
            ),
        ) { 0L }
        assertEquals(1, library.notes.size)
        assertEquals(1, library.todos.size)
        assertEquals("current task", library.todos.single().text)
    }
}
