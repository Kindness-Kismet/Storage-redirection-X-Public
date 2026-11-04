package com.storage.redirect.x.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// 全页状态在可用内容区居中，小窗口和大字体下仍可滚动。
@Composable
fun PageMessageState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    message: String? = null,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .overScrollVertical()
            .verticalScroll(rememberScrollState(), overscrollEffect = null)
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MessageContent(icon, title, message)
        if (action != null) {
            Spacer(Modifier.size(16.dp))
            action()
        }
    }
}

@Composable
private fun MessageContent(icon: ImageVector, title: String, message: String?) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onSurfaceVariantSummary,
            modifier = Modifier.size(40.dp),
        )
    }
    Spacer(Modifier.size(16.dp))
    Text(
        text = title,
        style = MiuixTheme.textStyles.title2,
        color = colors.onSurface,
        textAlign = TextAlign.Center,
    )
    if (message != null) {
        Spacer(Modifier.size(8.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}
