package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground

@Composable
fun StatusBadge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier =
            Modifier.squircleBackground(color.copy(alpha = 0.12f), 3.dp)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        maxLines = 1,
    )
}
