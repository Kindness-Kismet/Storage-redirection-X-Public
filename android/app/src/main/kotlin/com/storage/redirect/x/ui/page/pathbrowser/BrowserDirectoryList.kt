package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PageMessageState
import com.storage.redirect.x.ui.icon.AppIcons
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private val DIRECTORY_GROUP_CORNER_RADIUS = 16.dp
private val DIRECTORY_ROW_MIN_HEIGHT = 60.dp
private val DIRECTORY_ICON_SIZE = 34.dp

// 列表从顶栏下方滑过；加载与空状态留在顶栏之下
@Composable
internal fun BrowserDirectoryList(state: PathBrowserState, topPadding: Dp, bottomPadding: Dp) {
    val entries = state.visibleDirectories
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentPath, state.query, state.showHidden, state.isDescending) {
        listState.scrollToItem(0)
    }
    when {
        state.isLoading -> Box(Modifier.fillMaxSize().padding(top = topPadding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.hasLoadError || entries.isEmpty() -> BrowserEmptyState(state, topPadding, bottomPadding)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("browser_directories"),
            state = listState,
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        ) {
            item(key = "title") {
                SmallTitle(
                    text = if (state.query.isBlank()) {
                        stringResource(R.string.browser_directory_count, entries.size)
                    } else {
                        pluralStringResource(
                            R.plurals.browser_search_result_count, entries.size,
                            state.currentName.ifEmpty { rootLabel(state) }, entries.size,
                        )
                    },
                )
            }
            itemsIndexed(entries, key = { _, path -> path }) { index, path ->
                BrowserDirectoryRow(state, path, isFirst = index == 0, isLast = index == entries.lastIndex)
            }
        }
    }
}

@Composable
private fun BrowserEmptyState(state: PathBrowserState, topPadding: Dp, bottomPadding: Dp) {
    PageMessageState(
        icon = when {
            state.hasLoadError -> AppIcons.Warning
            state.query.isNotBlank() -> AppIcons.Search
            else -> AppIcons.FolderOpen
        },
        title = stringResource(
            when {
                state.hasLoadError -> R.string.rule_config_browser_load_failed
                state.query.isNotBlank() -> R.string.browser_no_results
                else -> R.string.rule_config_browser_empty
            },
        ),
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        modifier = Modifier.testTag("browser_empty_state"),
        action = {
            when {
                state.hasLoadError -> BrowserButton(stringResource(R.string.common_retry), { state.reloadKey++ })
                state.query.isNotBlank() -> BrowserButton(stringResource(R.string.browser_clear_search), { state.query = "" })
                state.multiSelect && state.canSelect(state.currentPath) &&
                    state.selectionOf(state.currentPath) != DirectorySelection.Covered -> BrowserButton(
                    stringResource(
                        if (state.currentPath in state.selectedPaths) R.string.browser_deselect_current
                        else R.string.rule_config_browser_use_current,
                    ),
                    { state.togglePath(state.currentPath) },
                )
            }
        },
    )
}

// 外层保持整组卡片，行内单独裁切选中和悬停反馈
@Composable
private fun BrowserDirectoryRow(state: PathBrowserState, path: String, isFirst: Boolean, isLast: Boolean) {
    val colors = browserColors()
    val isSelected = state.multiSelect && state.selectionOf(path) == DirectorySelection.Selected
    val isEnabled = state.canSelect(path)
    val top = if (isFirst) DIRECTORY_GROUP_CORNER_RADIUS else 0.dp
    val bottom = if (isLast) DIRECTORY_GROUP_CORNER_RADIUS else 0.dp
    Box(
        Modifier.fillMaxWidth()
            .padding(horizontal = PAGE_HORIZONTAL_PADDING)
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(colors.surface),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .testTag("browser_directory_$path"),
            cornerRadius = 12.dp,
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = if (isSelected) state.selectedRowColor() else colors.surface),
            onClick = if (isEnabled) ({ state.navigateTo(path) }) else null,
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DIRECTORY_ROW_MIN_HEIGHT - 4.dp)
                    .padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    AppIcons.Folder, null,
                    Modifier.size(DIRECTORY_ICON_SIZE).alpha(if (isEnabled) 1f else 0.5f),
                    tint = if (isSelected) state.accentColor() else colors.summary,
                )
                Text(
                    text = highlightQuery(path.substringAfterLast('/'), state.query, state.accentColor()),
                    modifier = Modifier.weight(1f).alpha(if (isEnabled) 1f else 0.5f),
                    color = colors.text,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DirectoryTrailing(state, path, isEnabled)
            }
        }
    }
}

@Composable
private fun DirectoryTrailing(state: PathBrowserState, path: String, isEnabled: Boolean) {
    if (!isEnabled) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Lock, stringResource(R.string.browser_restricted), Modifier.size(20.dp), tint = browserColors().summary)
        }
        return
    }
    if (state.multiSelect) {
        BrowserCheckbox(
            selection = state.selectionOf(path),
            description = stringResource(R.string.browser_select_directory, path.substringAfterLast('/')),
            onClick = { state.togglePath(path) },
            color = state.accentColor(),
            modifier = Modifier.testTag("browser_select_$path"),
        )
        return
    }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = browserColors().summary)
    }
}

// 搜索命中的片段用当前模式的强调色加粗
private fun highlightQuery(name: String, query: String, color: Color): AnnotatedString {
    val keyword = query.trim()
    val start = if (keyword.isEmpty()) -1 else name.indexOf(keyword, ignoreCase = true)
    if (start < 0) return AnnotatedString(name)
    return buildAnnotatedString {
        append(name)
        addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), start, start + keyword.length)
    }
}
