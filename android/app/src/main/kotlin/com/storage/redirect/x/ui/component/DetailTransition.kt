package com.storage.redirect.x.ui.component

import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.nav.core.*

private data object NestedMain : NavKey

private data object NestedDetail : NavKey

@Composable
fun <T : Any> DetailTransition(
    detail: T?,
    onBack: () -> Unit,
    detailContent: @Composable (T) -> Unit,
    content: @Composable () -> Unit,
) {
    val stack = remember { navBackStackOf(NestedMain) }
    var retained by remember { mutableStateOf(detail) }
    SideEffect { if (detail != null) retained = detail }
    LaunchedEffect(detail != null) {
        if (detail != null && stack.size == 1) stack.add(NestedDetail)
        if (detail == null && stack.size > 1) stack.removeLastOrNull()
    }
    NavDisplay(
        backStack = stack,
        onBack = onBack,
        effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
    ) {
        entry<NestedMain> { content() }
        entry<NestedDetail> { retained?.let { detailContent(it) } }
    }
}
