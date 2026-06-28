package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val MD_BLANK_LINE = 4.dp
private val MD_LIST_INDENT = 6.dp

// 更新内容只保留当前语言的列表，避免支持无关 Markdown 形态
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MiuixTheme.textStyles.body2,
) {
    Column(modifier = modifier) {
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("- ") -> MarkdownListRow(
                    text = trimmed.removePrefix("- "),
                    style = style
                )
                trimmed.isBlank() -> Spacer(Modifier.height(MD_BLANK_LINE))
                else -> Text(text = parseBold(trimmed), style = style)
            }
        }
    }
}

@Composable
private fun MarkdownListRow(text: String, style: TextStyle) {
    Row(modifier = Modifier.padding(start = MD_LIST_INDENT)) {
        Text("·  ", style = style)
        Text(text = parseBold(text), style = style, modifier = Modifier.weight(1f))
    }
}

private fun parseBold(input: String) = buildAnnotatedString {
    var cursor = 0
    while (cursor < input.length) {
        val start = input.indexOf("**", cursor)
        if (start == -1) {
            append(input.substring(cursor))
            break
        }
        append(input.substring(cursor, start))
        val end = input.indexOf("**", start + 2)
        if (end == -1) {
            append(input.substring(start))
            break
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(input.substring(start + 2, end))
        }
        cursor = end + 2
    }
}
