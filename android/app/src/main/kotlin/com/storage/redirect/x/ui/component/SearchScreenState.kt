// Adapted from Mishka (YuKongA), GPL-3.0.
package com.storage.redirect.x.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.ui.util.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 搜索屏的状态载体，label 随语言变更同步。连接页与分应用代理页的骨架完全一致，差别只在被过滤 的集合。返回**可写** State：搜索框还要回写 offsetY 与展开态，调用方照旧
 * `var status by ...`。
 *
 * 结果状态由 [SearchResultStatusEffect] 单独回填——搜索词决定过滤结果、过滤结果又决定结果状态， 中间隔着调用方的过滤逻辑，硬塞进一个函数就得在组合期回写 State。
 */
@Composable
fun rememberSearchScreenStatus(label: String): MutableState<SearchStatus> {
    val status = remember { mutableStateOf(SearchStatus(label = label)) }
    LaunchedEffect(label) {
        if (status.value.label != label) {
            status.value = status.value.copy(label = label)
        }
    }
    return status
}

/** 按「无搜索词 / 有词无结果 / 有结果」回填 [SearchStatus.resultStatus]。 */
@Composable
fun SearchResultStatusEffect(status: MutableState<SearchStatus>, isResultEmpty: Boolean) {
    val resultStatus =
        when {
            status.value.searchText.isEmpty() -> SearchStatus.ResultStatus.DEFAULT
            isResultEmpty -> SearchStatus.ResultStatus.EMPTY
            else -> SearchStatus.ResultStatus.SHOW
        }
    LaunchedEffect(resultStatus) {
        if (status.value.resultStatus != resultStatus) {
            status.value = status.value.copy(resultStatus = resultStatus)
        }
    }
}

/**
 * 搜索框的顶部间距。宽屏用固定的 SmallTopAppBar（永不折叠），间距恒为 0；只有手机上可折叠的 大标题栏才随折叠收缩。
 *
 * 返回 lambda 而非 Dp：`collapsedFraction` 是帧率级 State，组合期读会让顶栏每帧重组， 必须留给消费方在布局阶段读。
 */
@Composable
fun rememberSearchBarTopPadding(scrollBehavior: ScrollBehavior): () -> Dp {
    val isWideScreen = rememberIsWideScreen()
    return remember(isWideScreen, scrollBehavior) {
        if (isWideScreen) {
            { 0.dp }
        } else {
            { SearchBarTopPadding * (1f - scrollBehavior.state.collapsedFraction) }
        }
    }
}

private val SearchBarTopPadding = 12.dp
