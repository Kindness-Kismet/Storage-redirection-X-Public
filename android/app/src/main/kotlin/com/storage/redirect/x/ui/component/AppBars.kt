package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.util.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

val SRX_TOP_BAR_TRAILING_ICON_END_PADDING = 8.dp

@Composable
fun SrxTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = screenScrollBehavior(),
    horizontalPadding: Dp = 12.dp,
    color: Color = screenBarColor().takeOrElse { MiuixTheme.colorScheme.surface },
    bottomContent: @Composable () -> Unit = {},
) {
    if (rememberIsWideScreen()) {
        SmallTopAppBar(
            title = title,
            modifier = modifier,
            color = color,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    } else {
        TopAppBar(
            title = title,
            modifier = modifier,
            color = color,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
            titlePadding = horizontalPadding,
            navigationIconPadding = horizontalPadding,
            actionIconPadding = horizontalPadding,
            bottomContent = bottomContent,
        )
    }
}

@Composable
fun SrxSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = screenScrollBehavior(),
    horizontalPadding: Dp = 12.dp,
) {
    SmallTopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        color = screenBarColor().takeOrElse { MiuixTheme.colorScheme.surface },
    )
}

@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 4.dp),
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
        holdDownState = holdDownState,
        enabled = enabled,
    ) {
        Icon(icon, label, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(MiuixIcons.Back, stringResource(R.string.common_close))
    }
}

@Composable
fun AlignedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = MiuixTheme.colorScheme.secondaryContainer,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 4.dp),
        enabled = enabled,
        holdDownState = holdDownState,
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = backgroundColor,
        content = content,
    )
}
