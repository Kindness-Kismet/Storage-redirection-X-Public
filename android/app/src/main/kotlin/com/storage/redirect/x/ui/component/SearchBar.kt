// Adapted from Mishka (YuKongA), GPL-3.0.
package com.storage.redirect.x.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.storage.redirect.x.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 搜索折叠时显示主内容 */
@Composable
fun SearchStatus.SearchBox(content: @Composable () -> Unit) {
    if (shouldCollapsed()) content()
}

/** 在内容上方留出 [inset]，值在布局阶段读——变化只触发重新布局，不重组调用方 */
private fun Modifier.topInset(inset: () -> Dp): Modifier = layout { measurable, constraints ->
    val insetPx = inset().roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = -insetPx))
    layout(placeable.width, placeable.height + insetPx) {
        placeable.placeRelative(0, insetPx)
    }
}

/** 搜索覆盖层（展开时显示搜索框 + 结果） */
@Composable
fun SearchStatus.SearchPager(
    onSearchStatusChange: (SearchStatus) -> Unit,
    defaultResult: @Composable () -> Unit = {},
    emptyResult: @Composable () -> Unit = {},
    expandBar: @Composable (SearchStatus, (SearchStatus) -> Unit, () -> Dp) -> Unit =
        { searchStatus, onStatusChange, padding ->
            SearchBar(searchStatus, onStatusChange, padding)
        },
    searchBarTopPadding: () -> Dp = { 12.dp },
    result: @Composable () -> Unit,
) {
    val searchStatus = this
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    // 展开/收起 300ms 内逐帧变化，只在下方 topInset 的布局阶段读
    val topPadding =
        animateDpAsState(
            targetValue =
                if (searchStatus.shouldExpand()) {
                    systemBarsPadding + 5.dp
                } else {
                    max(searchStatus.offsetY, 0.dp)
                },
            animationSpec = tween(300, easing = LinearOutSlowInEasing),
            label = "SearchPagerTopPadding",
        ) {
            onSearchStatusChange(searchStatus.onAnimationComplete())
        }
    val surfaceAlpha by
        animateFloatAsState(
            if (searchStatus.shouldExpand()) 1f else 0f,
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            label = "SearchPagerSurfaceAlpha",
        )
    val surfaceColor = colorScheme.surface

    Column(
        modifier =
            Modifier.fillMaxSize()
                .zIndex(5f)
                .drawBehind { drawRect(surfaceColor.copy(alpha = surfaceAlpha)) }
                .semantics { onClick { false } }
                .then(if (!searchStatus.isCollapsed()) Modifier.pointerInput(Unit) {} else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .topInset { topPadding.value }
                .then(
                    if (!searchStatus.isCollapsed()) Modifier.background(colorScheme.surface)
                    else Modifier
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!searchStatus.isCollapsed()) {
                Box(modifier = Modifier.weight(1f).background(colorScheme.surface)) {
                    expandBar(searchStatus, onSearchStatusChange, searchBarTopPadding)
                }
            }
            AnimatedVisibility(
                visible = searchStatus.isExpand() || searchStatus.isAnimatingExpand(),
                enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it }),
            ) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                    modifier =
                        Modifier.padding(start = 4.dp, end = 16.dp, bottom = 6.dp)
                            .topInset(searchBarTopPadding)
                            .clickable(
                                interactionSource = null,
                                enabled = searchStatus.isExpand(),
                                indication = null,
                            ) {
                                onSearchStatusChange(
                                    searchStatus.copy(
                                        searchText = "",
                                        current = SearchStatus.Status.COLLAPSING,
                                    )
                                )
                            },
                )
            }
        }
        AnimatedVisibility(
            visible = searchStatus.isExpand(),
            modifier = Modifier.fillMaxSize().zIndex(1f),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            when (searchStatus.resultStatus) {
                SearchStatus.ResultStatus.DEFAULT -> defaultResult()
                SearchStatus.ResultStatus.EMPTY -> emptyResult()
                SearchStatus.ResultStatus.SHOW -> result()
            }
        }
    }
}

/** 真实搜索框（展开状态使用） */
@Composable
fun SearchBar(
    searchStatus: SearchStatus,
    onSearchStatusChange: (SearchStatus) -> Unit,
    searchBarTopPadding: () -> Dp = { 12.dp },
) {
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(searchStatus.searchText)) }

    LaunchedEffect(searchStatus.searchText) {
        if (textFieldValue.text != searchStatus.searchText) {
            textFieldValue = TextFieldValue(searchStatus.searchText)
        }
    }

    BasicTextField(
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
            onSearchStatusChange(searchStatus.copy(searchText = it.text))
        },
        singleLine = true,
        textStyle =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                color = colorScheme.onSurface,
            ),
        cursorBrush = SolidColor(colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp)
                .topInset(searchBarTopPadding)
                .heightIn(min = 45.dp)
                .squircleSurface(colorScheme.surfaceContainerHigh, 24.dp)
                .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MiuixIcons.Basic.Search,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).padding(start = 16.dp, end = 8.dp),
                    tint = colorScheme.onSurfaceContainerHigh,
                )
                Box(modifier = Modifier.weight(1f)) {
                    innerTextField()
                }
                AnimatedVisibility(
                    searchStatus.searchText.isNotEmpty(),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Basic.SearchCleanup,
                        tint = colorScheme.onSurface,
                        contentDescription = null,
                        modifier =
                            Modifier.size(44.dp).padding(start = 8.dp, end = 16.dp).clickable(
                                interactionSource = null,
                                indication = null,
                            ) {
                                textFieldValue = TextFieldValue("")
                                onSearchStatusChange(searchStatus.copy(searchText = ""))
                            },
                    )
                }
            }
        },
    )

    LaunchedEffect(Unit) {
        if (searchStatus.isAnimatingExpand()) {
            focusRequester.requestFocus()
        }
    }
}

/** 占位搜索框（折叠状态使用，点击触发展开） */
@Composable
fun SearchBarFake(
    label: String,
    searchBarTopPadding: () -> Dp = { 12.dp },
) {
    InputField(
        query = "",
        onQueryChange = {},
        label = label,
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = null,
                modifier = Modifier.size(44.dp).padding(start = 16.dp, end = 8.dp),
                tint = colorScheme.onSurfaceContainerHigh,
            )
        },
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp)
                .topInset(searchBarTopPadding),
        onSearch = {},
        enabled = false,
        expanded = false,
        onExpandedChange = {},
    )
}
