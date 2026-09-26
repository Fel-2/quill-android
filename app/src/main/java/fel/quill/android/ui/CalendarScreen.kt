package fel.quill.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fel.quill.android.QuillUiState
import fel.quill.android.QuillViewModel
import fel.quill.android.data.MarkdownParser
import fel.quill.android.model.Todo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarScreen(state: QuillUiState, viewModel: QuillViewModel) {
    var month by rememberSaveable(stateSaver = YearMonthSaver) { mutableStateOf(YearMonth.now()) }
    var selected by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(LocalDate.now()) }

    val byDate = remember(state.library.todos) {
        state.library.todos.filter { it.due != null }.groupBy { it.due!! }
    }
    val selectedTodos = (byDate[selected.toString()] ?: emptyList()).sortedWith(MarkdownParser.todoComparator())

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        MonthHeader(month, onPrevious = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) })
        Spacer(Modifier.height(10.dp))
        WeekdayHeader()
        MonthGrid(
            month = month,
            selected = selected,
            byDate = byDate,
            onSelect = { selected = it },
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ROOT)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!selected.isEqual(LocalDate.now())) {
                IconButton(onClick = { selected = LocalDate.now(); month = YearMonth.now() }) {
                    Icon(Icons.Outlined.Schedule, contentDescription = "Jump to today", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (selectedTodos.isEmpty()) {
            EmptyState(
                title = "Nothing due",
                body = "No todos are scheduled for this day.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
                items(selectedTodos, key = { it.id }) { todo ->
                    CalendarTodoRow(
                        todo = todo,
                        onToggle = { viewModel.toggleTodo(todo) },
                        onOpen = { note -> viewModel.openNote(note) },
                        note = state.library.notes.firstOrNull { it.path == todo.path },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ROOT)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onPrevious) { Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month") }
        IconButton(onClick = onNext) { Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month") }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).forEach { day ->
            Text(
                day.getDisplayName(TextStyle.SHORT, Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    byDate: Map<String, List<Todo>>,
    onSelect: (LocalDate) -> Unit,
) {
    val firstOfMonth = month.atDay(1)
    val leading = (firstOfMonth.dayOfWeek.value + 6) % 7
    val totalCells = leading + month.lengthOfMonth()
    val rows = (totalCells + 6) / 7
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxWidth()) {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val cellIndex = row * 7 + column
                    val dayNumber = cellIndex - leading + 1
                    if (dayNumber in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayNumber)
                        val todos = byDate[date.toString()] ?: emptyList()
                        DayCell(
                            date = date,
                            todos = todos,
                            isSelected = date.isEqual(selected),
                            isToday = date.isEqual(today),
                            onClick = { onSelect(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    todos: List<Todo>,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = todos.count { !it.done }
    val done = todos.count { it.done }
    val overdue = todos.any { !it.done && MarkdownParser.isOverdue(it.due, it.time) }
    val background = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val content = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        overdue -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .aspectRatio(0.95f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = background, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "${date.dayOfMonth}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                if (todos.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    DotRow(open = open, done = done, selected = isSelected, overdue = overdue)
                }
            }
        }
    }
}

@Composable
private fun DotRow(open: Int, done: Int, selected: Boolean, overdue: Boolean) {
    val openColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        overdue -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val doneColor = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(open.coerceAtMost(3)) {
            CalendarDot(openColor)
        }
        repeat(done.coerceAtMost(2)) {
            CalendarDot(doneColor)
        }
        if (open + done > 5) {
            Text(
                "+",
                style = MaterialTheme.typography.labelSmall,
                color = doneColor,
                modifier = Modifier.padding(start = 1.dp),
            )
        }
    }
}

@Composable
private fun CalendarDot(color: Color) {
    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(color))
}

private val YearMonthSaver: Saver<YearMonth, String> = Saver(
    save = { it.toString() },
    restore = { runCatching { YearMonth.parse(it) }.getOrNull() },
)

private val LocalDateSaver: Saver<LocalDate, String> = Saver(
    save = { it.toString() },
    restore = { runCatching { LocalDate.parse(it) }.getOrNull() },
)

@Composable
private fun CalendarTodoRow(
    todo: Todo,
    note: fel.quill.android.model.NoteDocument?,
    onToggle: () -> Unit,
    onOpen: (fel.quill.android.model.NoteDocument) -> Unit,
) {
    val overdue = MarkdownParser.isOverdue(todo.due, todo.time) && !todo.done
    Row(
        modifier = Modifier.fillMaxWidth().clickable { note?.let(onOpen) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = todo.done, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                todo.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (todo.done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (todo.time != null) {
                    Text(todo.time, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
                Text(todo.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (overdue) {
                    Icon(Icons.Outlined.EventBusy, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
