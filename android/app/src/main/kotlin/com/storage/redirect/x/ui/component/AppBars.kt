package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar

val SRX_TOP_BAR_TRAILING_ICON_END_PADDING = 8.dp

@Composable
fun SrxTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    horizontalPadding: Dp = 12.dp,
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        titlePadding = horizontalPadding,
        navigationIconPadding = horizontalPadding,
        actionIconPadding = horizontalPadding,
    )
}

@Composable
fun SrxSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    horizontalPadding: Dp = 12.dp,
) {
    SmallTopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        titlePadding = horizontalPadding,
        navigationIconPadding = horizontalPadding,
        actionIconPadding = horizontalPadding,
    )
}
