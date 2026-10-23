// Adapted from Mishka (YuKongA), GPL-3.0.
package com.storage.redirect.x.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一段独立 lazy item，靠分角 squircle 背景与相邻段拼成一张视觉连续的 miuix 风格卡片：首段圆顶角、 末段圆底角、中间段无圆角、段间严丝合缝。把整卡拆成多段后
 * LazyColumn 只组合/绘制可见段。
 *
 * 语义对齐 miuix [top.yukonga.miuix.kmp.basic.Card]（surfaceContainer 底 / onSurfaceContainer 内容色 / 16.dp
 * 圆角）： 有圆角的段用 [squircleSurface]（fill+clip，把 clickable 涟漪裁进圆角）；无圆角的中间段用纯 [background]（省一个 offscreen
 * layer）。
 *
 * @param topCornerRadius 顶角半径，默认按 [isFirst] 推导；可覆写。
 * @param bottomCornerRadius 底角半径，默认按 [isLast] 推导；传动画值可让展开/收起时圆角平滑过渡而非突变。
 * @param insidePadding 段内留白，对齐 miuix Card 的 `insideMargin`。
 */
@Composable
fun CardSegment(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surfaceContainer,
    contentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    cornerRadius: Dp = 16.dp,
    topCornerRadius: Dp = if (isFirst) cornerRadius else 0.dp,
    bottomCornerRadius: Dp = if (isLast) cornerRadius else 0.dp,
    outerHorizontalPadding: Dp = 12.dp,
    outerTopPadding: Dp = 0.dp,
    outerBottomPadding: Dp = 0.dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val background =
        if (topCornerRadius == 0.dp && bottomCornerRadius == 0.dp) {
            Modifier.background(color)
        } else {
            Modifier.squircleSurface(
                color,
                topCornerRadius,
                topCornerRadius,
                bottomCornerRadius,
                bottomCornerRadius,
            )
        }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(
                        start = outerHorizontalPadding,
                        end = outerHorizontalPadding,
                        top = outerTopPadding,
                        bottom = outerBottomPadding,
                    )
                    .then(background)
                    .padding(insidePadding),
            content = content,
        )
    }
}

/** [groupedCardItems] 的单段定义：稳定 [key] + 段内容。 */
class CardItem(
    val key: String,
    val content: @Composable ColumnScope.() -> Unit,
)

/**
 * 把一张卡片的多行拆成独立 lazy item（视觉上仍是一张连续卡片），替换 `item { Card { row1(); row2(); … } }` 这种"单 item 塞多组件"反模式，让
 * LazyColumn 只组合可见行、按行增量重组。
 *
 * 不加 item 动画，保持静态 `Card` 观感；需要展开/收起动画的（如代理组节点行）自行在 item 内 `Modifier.animateItem(...)`。
 *
 * @param keyPrefix 与各段 key 拼成全局唯一 key。
 * @param items 卡片内各行；空列表不产出任何 item。
 * @param insidePadding 段内留白，对齐所替换 Card 的 `insideMargin`（miuix 默认 0）。
 */
fun LazyListScope.groupedCardItems(
    keyPrefix: String,
    items: List<CardItem>,
    outerTopPadding: Dp = 0.dp,
    outerBottomPadding: Dp = 12.dp,
    outerHorizontalPadding: Dp = 12.dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
) {
    if (items.isEmpty()) return
    val lastIndex = items.lastIndex
    items.forEachIndexed { index, cardItem ->
        item(key = "$keyPrefix:${cardItem.key}") {
            CardSegment(
                isFirst = index == 0,
                isLast = index == lastIndex,
                outerHorizontalPadding = outerHorizontalPadding,
                outerTopPadding = if (index == 0) outerTopPadding else 0.dp,
                outerBottomPadding = if (index == lastIndex) outerBottomPadding else 0.dp,
                insidePadding = insidePadding,
                content = cardItem.content,
            )
        }
    }
}
