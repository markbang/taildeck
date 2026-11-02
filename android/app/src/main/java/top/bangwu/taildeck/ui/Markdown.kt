package top.bangwu.taildeck.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.bangwu.taildeck.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Code(val lang: String, val text: String) : Block
    data class Item(val marker: String, val depth: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Table(val rows: List<List<String>>) : Block
    data object Rule : Block
}

private val tableRule = Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?$""")

private fun tableCells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private val listItem = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")

private fun parseBlocks(src: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
        para.clear()
    }
    val lines = src.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                out += Block.Code(lang, code.toString().trimEnd('\n'))
            }
            trimmed.startsWith("#") && trimmed.takeWhile { it == '#' }.length in 1..6 && trimmed.getOrNull(trimmed.takeWhile { it == '#' }.length) == ' ' -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length
                out += Block.Heading(level, trimmed.drop(level).trim())
            }
            trimmed.startsWith("|") && lines.getOrNull(i + 1)?.trim()?.matches(tableRule) == true -> {
                flush()
                val rows = arrayListOf(tableCells(trimmed))
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += tableCells(lines[i]); i++
                }
                i--
                out += Block.Table(rows)
            }
            trimmed.matches(Regex("""^([-*_])\1{2,}$""")) -> { flush(); out += Block.Rule }
            trimmed.startsWith(">") -> { flush(); out += Block.Quote(trimmed.removePrefix(">").trim()) }
            listItem.matches(line) -> {
                flush()
                val m = listItem.find(line)!!
                val marker = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                out += Block.Item(marker, m.groupValues[1].length / 2, m.groupValues[3])
            }
            trimmed.isEmpty() -> flush()
            else -> {
                // Continuation lines of a list item stay with the item.
                val last = out.lastOrNull()
                if (para.isEmpty() && last is Block.Item && line.startsWith("  ")) {
                    out[out.lastIndex] = last.copy(text = last.text + " " + trimmed)
                } else {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                }
            }
        }
        i++
    }
    flush()
    return out
}

private val inline = Regex("""`([^`]+)`|\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*([^*\n]+)\*(?!\w)|\[([^\]]+)]\((\S+?)\)|(https?://[^\s)]+)""")

@Composable
private fun inlineText(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val link = MaterialTheme.colorScheme.primary
    return remember(text, codeBg, link) {
        buildAnnotatedString {
            var pos = 0
            for (m in inline.findAll(text)) {
                append(text.substring(pos, m.range.first))
                val g = m.groupValues
                when {
                    g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(" ${g[1]} ") }
                    g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[2]) }
                    g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[3]) }
                    g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
                    g[5].isNotEmpty() -> withLink(LinkAnnotation.Url(g[6], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(g[5]) }
                    g[7].isNotEmpty() -> withLink(LinkAnnotation.Url(g[7], TextLinkStyles(SpanStyle(color = link)))) { append(g[7]) }
                }
                pos = m.range.last + 1
            }
            append(text.substring(pos))
        }
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is Block.Heading -> Text(
                    inlineText(b.text),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Paragraph -> Text(inlineText(b.text), style = MaterialTheme.typography.bodyLarge, color = color)
                is Block.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                    Text(inlineText(b.text), style = MaterialTheme.typography.bodyLarge, color = color)
                }
                is Block.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(6.dp)) {
                    Text(inlineText(b.text), Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is Block.Code -> CodeBlock(b.text, b.lang, copyable = true)
                is Block.Table -> TableBlock(b.rows, color)
                Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

// Columns are laid out side by side so every column takes its widest cell.
@Composable
private fun TableBlock(rows: List<List<String>>, color: Color) {
    val columns = rows.maxOf { it.size }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            for (c in 0 until columns) {
                Column(Modifier.padding(horizontal = 10.dp)) {
                    rows.forEachIndexed { r, row ->
                        Text(
                            inlineText(row.getOrElse(c) { "" }),
                            Modifier.padding(vertical = 4.dp),
                            style = if (r == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            color = color,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, label: String = "", maxLines: Int = Int.MAX_VALUE, copyable: Boolean = false) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (label.isNotEmpty() || copyable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (copyable) {
                        IconButton(onClick = { context.copy(code) }, Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                maxLines = maxLines,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}
