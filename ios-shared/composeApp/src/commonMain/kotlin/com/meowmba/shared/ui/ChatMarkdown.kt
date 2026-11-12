package com.meowmba.shared.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The little Markdown cloud models answer in (bold, italics, `code`, headings, bullets), shown as
 * styled text instead of raw asterisks. Anything it doesn't recognise stays as typed.
 */
internal object ChatMarkdown {
    private val inline = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")

    fun render(text: String): AnnotatedString = buildAnnotatedString {
        var inFence = false
        text.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            if (raw.trimStart().startsWith("```")) { inFence = !inFence; return@forEachIndexed }
            if (inFence) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(raw) }; return@forEachIndexed }
            val h = heading.find(raw)
            val b = bullet.find(raw)
            when {
                h != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(h.groupValues[1]) }
                b != null -> { append(b.groupValues[1]); append("• "); inline(b.groupValues[2]) }
                else -> inline(raw)
            }
        }
    }

    private fun AnnotatedString.Builder.inline(line: String) {
        var at = 0
        for (m in inline.findAll(line)) {
            append(line.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[3]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
            }
            at = m.range.last + 1
        }
        append(line.substring(at))
    }
}
