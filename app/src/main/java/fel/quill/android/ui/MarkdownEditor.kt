package fel.quill.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fel.quill.android.data.MarkdownFormat

@Composable
fun MarkdownEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var emitted by remember { mutableStateOf(value) }

    if (value != emitted) {
        emitted = value
        field = TextFieldValue(value, TextRange(value.length))
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MarkdownFormat.Action.entries.forEach { action ->
                TextButton(onClick = {
                    val result = MarkdownFormat.apply(field.text, field.selection.start, field.selection.end, action)
                    field = field.copy(text = result.text, selection = TextRange(result.selectionStart, result.selectionEnd))
                    emitted = result.text
                    onValueChange(result.text)
                }) {
                    Text(actionLabel(action), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                emitted = it.text
                onValueChange(it.text)
            },
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (field.text.isEmpty()) {
                    Text("Write in Markdown…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            },
        )
    }
}

private fun actionLabel(action: MarkdownFormat.Action): String = when (action) {
    MarkdownFormat.Action.H1 -> "H1"
    MarkdownFormat.Action.H2 -> "H2"
    MarkdownFormat.Action.H3 -> "H3"
    MarkdownFormat.Action.BOLD -> "B"
    MarkdownFormat.Action.ITALIC -> "I"
    MarkdownFormat.Action.STRIKE -> "S"
    MarkdownFormat.Action.CODE -> "</>"
    MarkdownFormat.Action.CODE_BLOCK -> "```"
    MarkdownFormat.Action.LINK -> "Link"
    MarkdownFormat.Action.WIKILINK -> "[[ ]]"
    MarkdownFormat.Action.BULLET -> "•"
    MarkdownFormat.Action.TODO -> "☐"
    MarkdownFormat.Action.QUOTE -> "❝"
    MarkdownFormat.Action.HR -> "—"
}
