package fel.quill.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fel.quill.android.QuillUiState
import fel.quill.android.QuillViewModel
import fel.quill.android.data.MarkdownParser
import fel.quill.android.model.NoteDocument
import fel.quill.android.model.Todo
import fel.quill.android.model.TodoFilter

@Composable
fun TodosScreen(state: QuillUiState, viewModel: QuillViewModel) {
    var filter by remember { mutableStateOf(TodoFilter.all) }
    var showDone by remember { mutableStateOf(false) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var editingDue by remember { mutableStateOf<Todo?>(null) }
    val visibleTodos = state.library.todos.filter { todo ->
        val includeDone = showDone || !todo.done
        val matchesTag = tagFilter == null || todo.tags.any { it.equals(tagFilter, ignoreCase = true) }
        val matchesFilter = when (filter) {
            TodoFilter.all -> true
            TodoFilter.today -> todo.due == java.time.LocalDate.now().toString() && !todo.done
            TodoFilter.overdue -> MarkdownParser.isOverdue(todo.due, todo.time) && !todo.done
            TodoFilter.important -> todo.priority > 0 && !todo.done
            else -> true
        }
        includeDone && matchesTag && matchesFilter
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.captureText,
                onValueChange = viewModel::setCaptureText,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Capture a thought…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.capture() }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = viewModel::capture, enabled = !state.busy && state.captureText.isNotBlank()) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = "Capture", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CountCard("${state.library.counts.open}", "open", Modifier.weight(1f))
            CountCard("${state.library.counts.dueToday}", "today", Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
            CountCard("${state.library.counts.overdue}", "overdue", Modifier.weight(1f), MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(TodoFilter.all, TodoFilter.today, TodoFilter.overdue, TodoFilter.important).forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                )
            }
            AssistChip(onClick = { showDone = !showDone }, label = { Text(if (showDone) "Hide done" else "Show done") })
        }
        if (state.library.tags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(onClick = { tagFilter = null }, label = { Text("All tags") })
                state.library.tags.take(16).forEach { entry ->
                    FilterChip(
                        selected = tagFilter == entry.tag,
                        onClick = { tagFilter = if (tagFilter == entry.tag) null else entry.tag },
                        label = { Text("#${entry.tag} ${entry.count}") },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${visibleTodos.size} shown", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::undo, enabled = !state.busy) {
                Text("Undo")
            }
            TextButton(onClick = viewModel::archiveCompleted, enabled = state.library.counts.done > 0 && !state.busy) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear done")
            }
        }
        if (visibleTodos.isEmpty()) {
            EmptyState(
                title = if (state.library.todos.isEmpty()) "Nothing to do" else "No matching todos",
                body = if (state.library.todos.isEmpty()) "Capture something above and it will land in Inbox.md." else "Try another filter or capture a new task.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
                items(visibleTodos, key = { it.id }) { todo ->
                    TodoRow(
                        todo = todo,
                        note = state.library.notes.firstOrNull { it.path == todo.path },
                        onToggle = { viewModel.toggleTodo(todo) },
                        onDue = { editingDue = todo },
                        onDelete = { viewModel.deleteTodo(todo) },
                        onOpen = { note -> viewModel.openNote(note) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                }
            }
        }
    }

    editingDue?.let { todo ->
        DueEditorDialog(
            todo = todo,
            onDismiss = { editingDue = null },
            onSave = { due, time ->
                viewModel.setTodoDue(todo, due, time)
                editingDue = null
            },
        )
    }
}

@Composable
private fun CountCard(value: String, label: String, modifier: Modifier, color: Color? = null) {
    val actualColor = color ?: MaterialTheme.colorScheme.primary
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = actualColor.copy(alpha = 0.11f)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = actualColor, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TodoRow(
    todo: Todo,
    note: NoteDocument?,
    onToggle: () -> Unit,
    onDue: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (NoteDocument) -> Unit,
) {
    var deleteArmed by remember(todo.id) { mutableStateOf(false) }
    val overdue = MarkdownParser.isOverdue(todo.due, todo.time) && !todo.done
    Row(
        modifier = Modifier.fillMaxWidth().clickable { note?.let(onOpen) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = todo.done, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                todo.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (todo.done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(todo.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (todo.due != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (overdue) Icons.Outlined.EventBusy else Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(13.dp), tint = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                        Text(MarkdownParser.dueLabel(todo.due, todo.time), style = MaterialTheme.typography.labelSmall, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                    }
                }
                if (todo.recurrence != null) Text("@${todo.recurrence}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (todo.priority > 0) Icon(Icons.Outlined.Flag, contentDescription = "Priority", tint = if (todo.priority == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(17.dp))
        IconButton(onClick = onDue) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = "Change due date", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = {
            if (deleteArmed) onDelete() else deleteArmed = true
        }) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = if (deleteArmed) "Confirm delete" else "Delete todo", tint = if (deleteArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueEditorDialog(
    todo: Todo,
    onDismiss: () -> Unit,
    onSave: (due: String?, time: String?) -> Unit,
) {
    var date by remember(todo.id) { mutableStateOf(todo.due) }
    var time by remember(todo.id) { mutableStateOf(todo.time) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Due date and time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DateSelector(
                    date = date,
                    onDateChange = { date = it },
                    onPickDate = { showDatePicker = true },
                )
                TimeSelector(
                    time = time,
                    enabled = date != null,
                    onTimeChange = { time = it },
                    onPickTime = { showTimePicker = true },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(date, time) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.let { value ->
                runCatching {
                    java.time.LocalDate.parse(value)
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }.getOrNull()
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .toString()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = time?.substringBefore(":")?.toIntOrNull() ?: 9,
            initialMinute = time?.substringAfter(":")?.toIntOrNull() ?: 0,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    time = String.format(java.util.Locale.ROOT, "%02d:%02d", state.hour, state.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = {
                TimePicker(state = state)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelector(
    date: String?,
    onDateChange: (String?) -> Unit,
    onPickDate: () -> Unit,
) {
    val today = java.time.LocalDate.now()
    val options = buildList {
        add(null to "No date")
        for (offset in 0..14) {
            val value = today.plusDays(offset.toLong()).toString()
            val label = when (offset) {
                0 -> "Today · $value"
                1 -> "Tomorrow · $value"
                else -> "In $offset days · $value"
            }
            add(value to label)
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == date }?.second ?: (date?.let { "Custom · $it" } ?: "No date")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Date") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onDateChange(value)
                        expanded = false
                    })
                }
            }
        }
        IconButton(onClick = onPickDate) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = "Pick date")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSelector(
    time: String?,
    enabled: Boolean,
    onTimeChange: (String?) -> Unit,
    onPickTime: () -> Unit,
) {
    val options = buildList {
        add(null to "No time")
        for (slot in 0 until 96) {
            val minutes = slot * 15
            val value = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
            add(value to value)
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == time }?.second ?: (time?.let { "Custom · $it" } ?: "No time")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text("Time") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onTimeChange(value)
                        expanded = false
                    })
                }
            }
        }
        IconButton(onClick = onPickTime, enabled = enabled) {
            Icon(Icons.Outlined.Schedule, contentDescription = "Pick time")
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            Icon(Icons.Outlined.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
