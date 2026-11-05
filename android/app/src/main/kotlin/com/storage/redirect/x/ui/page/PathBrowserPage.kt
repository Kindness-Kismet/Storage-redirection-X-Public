package com.storage.redirect.x.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.BuildConfig
import com.storage.redirect.x.data.repository.PathBrowserRepository
import com.storage.redirect.x.ui.component.PAGE_ITEM_GAP
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.page.pathbrowser.BrowserDirectoryList
import com.storage.redirect.x.ui.page.pathbrowser.BrowserFloatingBar
import com.storage.redirect.x.ui.page.pathbrowser.BrowserPathPanel
import com.storage.redirect.x.ui.page.pathbrowser.BrowserTopBar
import com.storage.redirect.x.ui.page.pathbrowser.PathBrowserState

@Composable
fun PathBrowserPage(
    browserRepository: PathBrowserRepository,
    userId: Int,
    packageName: String? = null,
    multiSelect: Boolean = false,
    allowExclude: Boolean = false,
    onBack: () -> Unit,
    onPathSelect: (String) -> Unit,
    onPathsSelect: (List<String>) -> Unit = {},
) {
    val state = remember(userId, packageName, multiSelect, allowExclude) {
        PathBrowserState(userId, packageName, multiSelect, allowExclude)
    }
    // 页面切换取消旧协程，状态代次同时拒绝尚未返回的旧请求。
    LaunchedEffect(state, state.currentPath, state.reloadKey) {
        state.load(browserRepository)
    }
    val navigateUp = {
        if (state.currentPath.isEmpty()) onBack()
        else state.navigateTo(state.currentPath.substringBeforeLast('/', ""))
    }
    // 后注册的返回处理优先：路径面板 > 搜索 > 逐级返回；根目录交给页面栈播放预测返回
    BackHandler(enabled = state.currentPath.isNotEmpty(), onBack = navigateUp)
    BackHandler(enabled = state.isSearchActive, onBack = state::closeSearch)
    BackHandler(enabled = state.isPathPanelExpanded) { state.isPathPanelExpanded = false }

    var floatingBarSpace by remember { mutableStateOf(0.dp) }
    SrxScaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .semantics { testTagsAsResourceId = BuildConfig.IS_DEBUG_BUILD }
            .testTag("page_path_browser"),
        topBar = { BrowserTopBar(state, onBack = navigateUp) },
    ) { padding ->
        // 列表从顶栏下方滑过并延伸到屏幕底部，底部留白由悬浮栏高度决定
        Box(Modifier.fillMaxSize()) {
            BrowserDirectoryList(
                state,
                topPadding = padding.calculateTopPadding(),
                bottomPadding = floatingBarSpace + PAGE_ITEM_GAP,
            )
            BrowserFloatingBar(state, onSpaceChange = { floatingBarSpace = it }) {
                val paths = state.confirmedPaths()
                if (paths.isNotEmpty()) {
                    if (multiSelect) onPathsSelect(paths) else onPathSelect(paths.first())
                }
            }
            Box(Modifier.padding(top = padding.calculateTopPadding())) {
                BrowserPathPanel(state)
            }
        }
    }
}
