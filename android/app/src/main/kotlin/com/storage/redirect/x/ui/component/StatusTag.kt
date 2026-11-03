package com.storage.redirect.x.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

// miuix 配色没有成功色与提醒色，深浅主题共用这两个固定色
val STATUS_SUCCESS_COLOR = Color(0xFF1FAF54)
val STATUS_WARNING_COLOR = Color(0xFFF08A00)
val STATUS_READONLY_COLOR = Color(0xFFE0A800)

private const val STATUS_TAG_BACKGROUND_ALPHA = 0.12f
private val STATUS_TAG_CORNER_RADIUS = 6.dp

// 状态标签：同色文字配 12% 透明度底色
@Composable
fun StatusTag(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier
            .background(color.copy(alpha = STATUS_TAG_BACKGROUND_ALPHA), RoundedCornerShape(STATUS_TAG_CORNER_RADIUS))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight(600),
        color = color,
        maxLines = 1,
    )
}
