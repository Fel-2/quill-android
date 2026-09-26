package fel.quill.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import fel.quill.android.data.MarkdownParser

@Composable
fun MarkdownPreview(text: String, onWikiLink: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(5.dp))
                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                    Text(
                        text = trimmed.dropWhile { it == '#' }.trim().let(MarkdownParser::stripMarkdown),
                        style = when (level) {
                            1 -> MaterialTheme.typography.headlineMedium
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                trimmed.startsWith("- [ ]") || trimmed.startsWith("* [ ]") -> MarkdownLine("☐", trimmed.drop(5).trim(), false)
                trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]") || trimmed.startsWith("* [x]") || trimmed.startsWith("* [X]") -> MarkdownLine("☑", trimmed.drop(5).trim(), true)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> Row {
                    Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
                    Text(MarkdownParser.stripMarkdown(trimmed.drop(2)), style = MaterialTheme.typography.bodyLarge)
                }
                trimmed.startsWith("---") -> Spacer(Modifier.height(7.dp))
                else -> Text(MarkdownParser.stripMarkdown(trimmed), style = MaterialTheme.typography.bodyLarge)
            }
        }
        val links = Regex("\\[\\[([^|\\]\\n]+)").findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()
        if (links.isNotEmpty()) {
            Text("Links", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(links) { link ->
                    AssistChip(onClick = { onWikiLink(link) }, label = { Text(link) })
                }
            }
        }
    }
}

@Composable
private fun MarkdownLine(mark: String, text: String, done: Boolean) {
    Row {
        Text(mark, color = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
        Text(MarkdownParser.stripMarkdown(text), style = MaterialTheme.typography.bodyLarge, textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None)
    }
}
