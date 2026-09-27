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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fel.quill.android.data.MarkdownParser

@Composable
fun MarkdownPreview(text: String, onWikiLink: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val lines = text.lines()
    val blocks = buildBlocks(lines)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block -> MarkdownBlock(block) }
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

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Todo(val done: Boolean, val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Numbered(val marker: String, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Code(val language: String, val body: String) : Block
    data class Rule(val placeholder: Boolean = true) : Block
    data class Paragraph(val text: String) : Block
    data object Blank : Block
}

private fun buildBlocks(lines: List<String>): List<Block> {
    val out = mutableListOf<Block>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim()
                val body = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    body += lines[index]
                    index++
                }
                out += Block.Code(language, body.joinToString("\n"))
            }
            trimmed.isEmpty() -> out += Block.Blank
            isRule(trimmed) -> out += Block.Rule()
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                out += Block.Heading(level, trimmed.dropWhile { it == '#' }.trim())
            }
            todoMark(trimmed) != null -> {
                val (done, rest) = todoMark(trimmed)!!
                out += Block.Todo(done, rest)
            }
            bulletMark(trimmed) != null -> out += Block.Bullet(bulletMark(trimmed)!!)
            numberedMark(trimmed) != null -> {
                val match = numberedMark(trimmed)!!
                out += Block.Numbered(match.first, match.second)
            }
            trimmed.startsWith(">") -> out += Block.Quote(trimmed.removePrefix(">").trim())
            else -> out += Block.Paragraph(trimmed)
        }
        index++
    }
    return out
}

private fun isRule(value: String): Boolean {
    val stripped = value.replace(" ", "")
    return stripped.length >= 3 && (stripped.all { it == '-' } || stripped.all { it == '*' } || stripped.all { it == '_' })
}

private fun todoMark(value: String): Pair<Boolean, String>? {
    val patterns = listOf("- [ ]", "* [ ]", "+ [ ]", "- [x]", "* [x]", "- [X]", "* [X]")
    for (pattern in patterns) {
        if (value.startsWith(pattern)) {
            val done = pattern.endsWith("x]") || pattern.endsWith("X]")
            return done to value.removePrefix(pattern).trim()
        }
    }
    return null
}

private fun bulletMark(value: String): String? =
    if (value.startsWith("- ") || value.startsWith("* ") || value.startsWith("+ ")) value.drop(2).trim() else null

private fun numberedMark(value: String): Pair<String, String>? {
    val match = Regex("^(\\d+)[.)]\\s+(.*)$").find(value) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

@Composable
private fun MarkdownBlock(block: Block) {
    when (block) {
        is Block.Blank -> Spacer(Modifier.height(6.dp))
        is Block.Rule -> Surface(
            modifier = Modifier.fillMaxWidth().height(1.dp).padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        ) {}
        is Block.Heading -> Text(
            text = inline(block.text),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
        is Block.Todo -> Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (block.done) "☑" else "☐",
                color = if (block.done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(22.dp),
            )
            Text(
                inline(block.text),
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (block.done) TextDecoration.LineThrough else TextDecoration.None,
                color = if (block.done) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
        }
        is Block.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
            Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
            Text(inline(block.text), style = MaterialTheme.typography.bodyLarge)
        }
        is Block.Numbered -> Row(modifier = Modifier.fillMaxWidth()) {
            Text("${block.marker}.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(26.dp))
            Text(inline(block.text), style = MaterialTheme.typography.bodyLarge)
        }
        is Block.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
            Surface(modifier = Modifier.width(3.dp).height(20.dp), color = MaterialTheme.colorScheme.primary) {}
            Spacer(Modifier.width(10.dp))
            Text(
                inline(block.text),
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Block.Code -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small,
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (block.language.isNotBlank()) {
                    Text(block.language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    block.body.ifBlank { " " },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        is Block.Paragraph -> Text(
            text = inline(block.text),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun inline(value: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < value.length) {
        val rest = value.substring(index)
        val match = INLINE.find(rest)
        if (match == null) {
            append(value.substring(index))
            return@buildAnnotatedString
        }
        append(value.substring(index, index + match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.removeSurrounding("**")) }
            token.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.removeSurrounding("__")) }
            token.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.removeSurrounding("~~")) }
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(token.removeSurrounding("`")) }
            token.startsWith("[[") -> withStyle(SpanStyle(color = Color(0xFF4C8DFF), textDecoration = TextDecoration.Underline)) { append(token.removeSurrounding("[[", "]]")) }
            token.startsWith("![") -> append(Regex("\\!\\[([^\\]]*)\\]").find(token)?.groupValues?.get(1).orEmpty())
            token.startsWith("[") -> withStyle(SpanStyle(color = Color(0xFF4C8DFF), textDecoration = TextDecoration.Underline)) {
                append(Regex("\\[([^\\]]*)\\]").find(token)?.groupValues?.get(1).orEmpty())
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(Regex("^[*_](.*)[*_]$").find(token)?.groupValues?.get(1) ?: token) }
        }
        index += match.range.first + token.length
    }
}

private val INLINE = Regex(
    "\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|~~[^~\\n]+~~|`[^`\\n]+`|\\[\\[[^\\]\\n]+]]|!\\[[^\\]\\n]*]\\([^)\\n]*\\)|\\[[^\\]\\n]+]\\([^)\\n]*\\)|\\*[^*\\n]+\\*|_[^_\\n]+_",
)
