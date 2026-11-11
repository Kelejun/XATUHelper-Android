package com.xatune.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

internal data class MarkdownBlock(val text: String, val kind: String = "paragraph", val level: Int = 0)
internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var code: MutableList<String>? = null
    var fence = ""
    fun flush() { if (paragraph.isNotEmpty()) { result += MarkdownBlock(paragraph.joinToString("\n")); paragraph.clear() } }
    for (line in text.lines()) {
        val trimmed = line.trim()
        val current = code
        if (current != null) {
            if (trimmed.startsWith(fence)) { result += MarkdownBlock(current.joinToString("\n"), "code"); code = null }
            else current += line
            continue
        }
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> { flush(); fence = trimmed.take(3); code = mutableListOf() }
            trimmed.isEmpty() -> flush()
            Regex("^(#{1,6}) +(.+)$").matches(trimmed) -> { flush(); val match = Regex("^(#{1,6}) +(.+)$").find(trimmed)!!; result += MarkdownBlock(match.groupValues[2], "heading", match.groupValues[1].length) }
            Regex("^([-*_])(?: *\\1){2,}$").matches(trimmed) -> { flush(); result += MarkdownBlock("", "rule") }
            trimmed.startsWith(">") -> { flush(); result += MarkdownBlock(trimmed.removePrefix(">").trimStart(), "quote") }
            Regex("^[-*+] +.+").matches(trimmed) -> { flush(); result += MarkdownBlock("• " + trimmed.drop(2).trimStart(), "list") }
            Regex("^[0-9]+[.)] +.+").matches(trimmed) -> { flush(); result += MarkdownBlock(trimmed, "list") }
            else -> paragraph += line
        }
    }
    flush()
    code?.let { result += MarkdownBlock(it.joinToString("\n"), "code") }
    return result
}

internal fun markdownInline(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\[([^]\\n]+)]\\(([^ )]+)\\)|\\*\\*(.+?)\\*\\*|__(.+?)__|\\*([^*]+)\\*|_([^_]+)_|`([^`]+)`")
    var cursor = 0
    for (match in pattern.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        when {
            match.groupValues[1].isNotEmpty() -> {
                val url = match.groupValues[2]
                if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)))) { append(markdownInline(match.groupValues[1])) }
                } else append(match.groupValues[1])
            }
            match.groupValues[3].isNotEmpty() || match.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(markdownInline(match.groupValues[3].ifEmpty { match.groupValues[4] })) }
            match.groupValues[5].isNotEmpty() || match.groupValues[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[5].ifEmpty { match.groupValues[6] }) }
            else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[7]) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable internal fun AnnouncementMarkdown(text: String) {
    val blocks = remember(text) { markdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block -> when (block.kind) {
            "rule" -> HorizontalDivider()
            "code" -> Text(block.text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp), fontFamily = FontFamily.Monospace)
            "quote" -> Row { Box(Modifier.width(3.dp).height(28.dp).background(MaterialTheme.colorScheme.outline)); Text(markdownInline(block.text), Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            "heading" -> Text(markdownInline(block.text), style = when (block.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium }, fontWeight = FontWeight.Bold)
            else -> Text(markdownInline(block.text), style = MaterialTheme.typography.bodyLarge)
        } }
    }
}
