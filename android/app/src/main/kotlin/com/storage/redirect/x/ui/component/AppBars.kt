package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.icon.AppIcons
import com.storage.redirect.x.ui.util.LocalTopBarBackdrop
import com.storage.redirect.x.ui.util.LocalTopBarBlur
import com.storage.redirect.x.ui.util.surfaceBlur
import top.yukonga.miuix.kmp.basic.Icon
import com.storage.redirect.x.ui.component.SrxIconButton as IconButton
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面骨架间距：顶栏下留白、内容左右边距、分组间距、列表项间距
val PAGE_CONTENT_TOP_PADDING = 10.dp
val PAGE_HORIZONTAL_PADDING = 12.dp
val PAGE_GROUP_GAP = 12.dp
val PAGE_ITEM_GAP = 8.dp

// 顶栏下拉菜单与屏幕边缘、锚点各留 8dp
val TOP_BAR_POPUP_POSITION_PROVIDER: PopupPositionProvider = object : PopupPositionProvider by ListPopupDefaults.DropdownPositionProvider {
    override fun getMargins(): PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
}

// 参照 only_player AppScaffold：顶栏模糊开启时内容层捕获 backdrop，顶栏经 LocalTopBarBackdrop 采样
// 开关只切换修饰符，组合结构保持不变，切换时页面滚动等状态不被重建
@Composable
fun SrxScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val shouldBlur = LocalTopBarBlur.current && isRuntimeShaderSupported()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    CompositionLocalProvider(LocalTopBarBackdrop provides backdrop.takeIf { shouldBlur }) {
        Scaffold(modifier = modifier, topBar = topBar) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().then(if (shouldBlur) Modifier.layerBackdrop(backdrop) else Modifier)) {
                content(innerPadding)
            }
        }
    }
}

// 顶栏模糊开启时经 backdrop 采样内容，关闭时行为与 miuix TopAppBar 一致；边距沿用 miuix 默认值
@Composable
fun SrxTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    applyBlur: Boolean = true,
) {
    val backdrop = if (applyBlur) LocalTopBarBackdrop.current else null
    TopAppBar(
        title = title,
        modifier = if (backdrop != null) modifier.surfaceBlur(backdrop) else modifier,
        color = if (LocalTopBarBackdrop.current != null || !applyBlur) Color.Transparent else MiuixTheme.colorScheme.surface,
        subtitle = subtitle,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun SrxSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    applyBlur: Boolean = true,
) {
    val backdrop = if (applyBlur) LocalTopBarBackdrop.current else null
    SmallTopAppBar(
        title = title,
        modifier = if (backdrop != null) modifier.surfaceBlur(backdrop) else modifier,
        color = if (LocalTopBarBackdrop.current != null || !applyBlur) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

// 二级页返回键：在 miuix 导航区 16dp 之外再留 12dp
@Composable
fun SrxBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.padding(start = 12.dp)) {
        Icon(AppIcons.Back, contentDescription = stringResource(R.string.common_back))
    }
}
