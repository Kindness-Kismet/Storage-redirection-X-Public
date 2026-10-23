// Adapted from Mishka (YuKongA), GPL-3.0.
package com.storage.redirect.x.ui.util

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.ui.theme.LocalPlatformDensity
import top.yukonga.miuix.kmp.anim.folmeSpring

/** 宽屏阈值：窗口宽度达到此值时启用侧边导航栏与内容居中（平板、横屏、展开态折叠屏、桌面）。 */
private val WideScreenMinWidth = 600.dp

/** 宽屏下内容的最大宽度，超出后两侧留白居中，避免超宽窗口下卡片被过度拉伸。 */
val MaxContentWidth: Dp = 800.dp

/** 当前窗口是否达到宽屏尺寸。宽屏切换到 NavigationRail + 固定小标题栏 + 内容居中。 */
@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return with(density) { containerSize.width.toDp() >= WideScreenMinWidth }
}

/**
 * 宽屏内容居中容器：按当前可用宽度算出单侧留白量并经 [content] 回传，交给内部滚动列表加进自己的
 * `contentPadding`——列表本身保持全宽（滚动手势覆盖整屏、两侧无死区），仅其中的内容被居中限制到 [MaxContentWidth]。窄屏（可用宽度不足上限）留白为
 * 0，等同无操作。
 *
 * 是否居中必须复用 [rememberIsWideScreen] 的判定：外壳（NavigationRail/底栏）用缩放前密度量 600dp，而本容器的 BoxWithConstraints 在
 * densityScale 覆盖后的 LocalDensity 下量宽——若各自 独立比较 600dp，densityScale != 1 时会出现「手机外壳 + 内容莫名内缩」或「rail
 * 外壳 + 内容不居中」 的组合。以外壳判定为唯一权威，留白量本身仍在缩放后空间计算（contentPadding 消费缩放后 dp）。
 */
@Composable
fun WideContentBox(
    modifier: Modifier = Modifier,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    val isWideScreen = rememberIsWideScreen()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePadding =
            if (isWideScreen) {
                ((maxWidth - MaxContentWidth) / 2).coerceAtLeast(0.dp)
            } else {
                0.dp
            }
        content(sidePadding)
    }
}

/**
 * 为内容补上水平方向（起始 + 末尾）的屏幕缺口 / 侧边导航栏 inset padding。二级页 Scaffold 内容默认 只吃了顶部 inset，横屏遇到侧边刘海 / 挖孔 /
 * 手势条时内容会压到缺口下——在内容根容器加此 modifier 让内容避开。竖屏或无侧边缺口时 inset 为 0、等同无操作。顶栏由 `TopAppBar` 自己的
 * `defaultWindowInsetsPadding` 处理，与此互不重叠。
 */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier =
    windowInsetsPadding(
        WindowInsets.displayCutout
            .union(WindowInsets.navigationBars)
            .only(WindowInsetsSides.Horizontal)
    )

/**
 * BottomSheet 内容的底部安全区。miuix 的 sheet 只装了 `imePadding()`，`captionBar`（小窗 / 桌面窗口模式的系统 标题栏）与
 * `displayCutout` 仅取 top 分量算最大高度上限、不产生 padding，底部因此要内容自补。取 [WindowInsets.systemBars]（含 statusBars /
 * navigationBars / captionBar）∪ [WindowInsets.displayCutout] 的底部。
 *
 * 两处刻意的排除，勿「顺手补全」：
 * - **不含 ime**：sheet 已装 `imePadding()`，并进来会叠成双倍底距。
 * - **不含水平方向**：sheet `BottomCenter` 对齐且 `widthIn(max = 640.dp)`，水平 inset 只在有缺口的单侧生效，
 *   会把居中的内容推偏；窗口更宽时两侧本就留白、缺口也压不到。
 */
@Composable
fun Modifier.sheetContentSafePadding(): Modifier =
    windowInsetsPadding(
        WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom)
    )

/**
 * BottomSheet 内容的高度过渡。sheet 是 `wrapContentHeight`、高度完全跟随内容且自身无动画， loading 切列表、条数增减都会整体硬跳。
 *
 * spec 与 sheet 的入场动画同参（[folmeSpring] 是 miuix public API，别自己凑 spring 参数）。
 */
fun Modifier.sheetHeightTransition(): Modifier =
    animateContentSize(
        folmeSpring(damping = 0.9f, response = 0.38f, visibilityThreshold = IntSize(1, 1))
    )
