package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.ui.util.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val LocalScrollBehavior = staticCompositionLocalOf<ScrollBehavior?> { null }
private val LocalScreenBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
private val LocalBarColor = staticCompositionLocalOf { Color.Unspecified }

@Composable
fun SrxScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    popupHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = MiuixScrollBehavior(rememberTopAppBarState())
    val backdrop = rememberBlurBackdrop()
    val barColor =
        if (backdrop != null) Color.Transparent
        else top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
    CompositionLocalProvider(
        LocalScrollBehavior provides behavior,
        LocalScreenBackdrop provides backdrop,
        LocalBarColor provides barColor,
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            floatingActionButton = floatingActionButton,
            popupHost = {
                top.yukonga.miuix.kmp.utils.MiuixPopupUtils.MiuixPopupHost()
                popupHost()
            },
            topBar = { BlurredBar(backdrop) { topBar() } },
            bottomBar = { BlurredBar(backdrop, useProgressive = false) { bottomBar() } },
        ) { padding ->
            content(padding)
        }
    }
}

@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    SrxScaffold(
        modifier = modifier,
        topBar = {
            SrxTopAppBar(
                title = title,
                navigationIcon = {
                    if (onBack != null) BackButton(onBack)
                },
                actions = actions,
            )
        },
    ) { padding ->
        SrxLazyColumn(
            state = state,
            contentPadding =
                PaddingValues(top = padding.calculateTopPadding(), bottom = bottomPadding),
            content = content,
        )
    }
}

@Composable
fun SrxLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    val backdrop = LocalScreenBackdrop.current
    val behavior = LocalScrollBehavior.current
    WideContentBox {
        LazyColumn(
            state = state,
            modifier =
                modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .then(
                        if (behavior != null) Modifier.nestedScroll(behavior.nestedScrollConnection)
                        else Modifier
                    ),
            contentPadding =
                PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                    start =
                        it +
                            contentPadding.calculateLeftPadding(
                                androidx.compose.ui.unit.LayoutDirection.Ltr
                            ),
                    end =
                        it +
                            contentPadding.calculateRightPadding(
                                androidx.compose.ui.unit.LayoutDirection.Ltr
                            ),
                ),
            verticalArrangement = verticalArrangement,
        ) {
            content()
            item(key = "screen_safe_end") { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}

@Composable internal fun screenScrollBehavior(): ScrollBehavior? = LocalScrollBehavior.current

@Composable internal fun screenBarColor(): Color = LocalBarColor.current
