package fel.quill.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fel.quill.android.QuillUiState
import fel.quill.android.QuillViewModel
import fel.quill.android.data.MarkdownParser
import fel.quill.android.model.NoteDocument

@Composable
fun NotesScreen(state: QuillUiState, viewModel: QuillViewModel) {
    var sort by rememberSaveable { mutableStateOf("modified") }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val searched = MarkdownParser.search(state.library, state.search)
    val filtered = searched.copy(
        notes = searched.notes
            .filter { tagFilter == null || it.tags.any { tag -> tag.equals(tagFilter, ignoreCase = true) } }
            .let { noteList ->
                when (sort) {
                    "title" -> noteList.sortedBy { it.title.lowercase() }
                    "open" -> noteList.sortedByDescending { it.openTodos }
                    else -> noteList
                }
            },
    )
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::setSearch,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search notes and wikilinks") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = viewModel::openDaily, enabled = !state.busy) {
                Text("Today")
            }
            TextButton(onClick = viewModel::newNote, enabled = !state.busy) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("modified" to "Recent", "title" to "Title", "open" to "Open todos").forEach { (value, label) ->
                FilterChip(selected = sort == value, onClick = { sort = value }, label = { Text(label) })
            }
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
            Text("${filtered.notes.size} notes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (state.search.isNotBlank() || tagFilter != null) {
                FilterChip(selected = true, onClick = { viewModel.setSearch(""); tagFilter = null }, label = { Text("Clear filters") })
            }
        }
        if (filtered.notes.isEmpty()) {
            EmptyState(
                title = if (state.library.notes.isEmpty()) "No notes yet" else "No matching notes",
                body = if (state.library.notes.isEmpty()) "Create a note or capture a reference from the Todos tab." else "Try a different title, tag, or phrase.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
                items(filtered.notes, key = { it.path }) { note ->
                    NoteCard(note, onClick = { viewModel.openNote(note) })
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteDocument, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
    ) {
        Row(modifier = Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note.preview.isNotBlank()) {
                    Text(note.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(MarkdownParser.relativeTime(note.modifiedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (note.openTodos > 0) Text("${note.openTodos} open", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    if (note.tags.isNotEmpty()) Text(note.tags.take(2).joinToString(" #", prefix = "#"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
