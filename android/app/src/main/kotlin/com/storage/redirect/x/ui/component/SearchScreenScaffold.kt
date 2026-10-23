package com.storage.redirect.x.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.PullToRefresh

@Composable
fun SearchScreenScaffold(
    title: String,
    label: String,
    query: String,
    onQueryChange: (String) -> Unit,
    isResultEmpty: Boolean,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    emptyResult: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val status = rememberSearchScreenStatus(label)
    val density = LocalDensity.current
    SearchResultStatusEffect(status, isResultEmpty)
    LaunchedEffect(query) {
        if (query != status.value.searchText) status.value = status.value.copy(searchText = query)
    }
    val update: (SearchStatus) -> Unit = {
        status.value = it
        if (query != it.searchText) onQueryChange(it.searchText)
    }
    BackHandler(enabled = !status.value.isCollapsed()) {
        update(status.value.copy(searchText = "", current = SearchStatus.Status.COLLAPSING))
    }
    SrxScaffold(
        modifier = modifier,
        floatingActionButton = floatingActionButton,
        topBar = {
            val behavior = screenScrollBehavior()!!
            val topPadding = rememberSearchBarTopPadding(behavior)
            status.value.TopAppBarAnim(backgroundColor = screenBarColor()) {
                SrxTopAppBar(
                    title = title,
                    actions = actions,
                    navigationIcon = navigationIcon,
                    bottomContent = {
                        Box(
                            Modifier.graphicsLayer {
                                    alpha = if (status.value.isCollapsed()) 1f else 0f
                                }
                                .onGloballyPositioned { coordinates ->
                                    val offset =
                                        with(density) { coordinates.positionInWindow().y.toDp() }
                                    if (status.value.offsetY != offset)
                                        status.value = status.value.copy(offsetY = offset)
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        update(
                                            status.value.copy(
                                                current = SearchStatus.Status.EXPANDING
                                            )
                                        )
                                    }
                                }
                        ) {
                            SearchBarFake(status.value.label, topPadding)
                        }
                    },
                )
            }
        },
        popupHost = {
            val topPadding = rememberSearchBarTopPadding(screenScrollBehavior()!!)
            status.value.SearchPager(
                onSearchStatusChange = update,
                searchBarTopPadding = topPadding,
                emptyResult = emptyResult,
                defaultResult = {},
            ) {
                SrxLazyColumn(
                    modifier = Modifier.imePadding(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding),
                    content = content,
                )
            }
        },
    ) { padding ->
        status.value.SearchBox {
            PullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp),
                topAppBarScrollBehavior = screenScrollBehavior(),
            ) {
                SrxLazyColumn(
                    state = listState,
                    contentPadding =
                        PaddingValues(top = padding.calculateTopPadding(), bottom = bottomPadding),
                    content = content,
                )
            }
        }
    }
}
