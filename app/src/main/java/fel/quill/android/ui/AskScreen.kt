package fel.quill.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fel.quill.android.QuillUiState
import fel.quill.android.QuillViewModel
import fel.quill.android.model.AskMessage

@Composable
fun AskScreen(state: QuillUiState, viewModel: QuillViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.askMessages.size) {
        if (state.askMessages.isNotEmpty()) listState.animateScrollToItem(state.askMessages.lastIndex)
    }
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Ask over your notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Answers stay grounded in the files on your PC.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = viewModel::runPlan, enabled = !state.busy, label = { Text("Plan my day") })
                AssistChip(onClick = viewModel::runReview, enabled = !state.busy, label = { Text("Weekly review") })
                AssistChip(onClick = { viewModel.setAskText("/help") }, label = { Text("Commands") })
            }
        }
        if (state.askMessages.isEmpty()) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                Spacer(Modifier.size(12.dp))
                Text("What do you want to remember?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(5.dp))
                Text("Try “What is due this week?” or “Summarize my project notes.”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.askMessages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }
        if (state.busy) {
            Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Thinking…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = state.askText,
                onValueChange = viewModel::setAskText,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask a question…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.ask() }),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = viewModel::ask, enabled = !state.busy && state.askText.isNotBlank()) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Ask", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AskMessage) {
    val user = message.role == "user"
    val error = message.role == "error"
    val color = when {
        error -> MaterialTheme.colorScheme.errorContainer
        user -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(18.dp),
            color = color,
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (!user) Text(if (error) "Error" else "Quill", style = MaterialTheme.typography.labelSmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                if (message.sources.isNotEmpty()) Text("Sources: ${message.sources.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
