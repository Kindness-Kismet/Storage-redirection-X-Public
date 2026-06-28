package com.storage.redirect.x.ui.component.bottombar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private const val FLOATING_BOTTOM_BAR_VALUE_VISIBILITY_THRESHOLD = 0.001f

private val LocalFloatingTabScale = staticCompositionLocalOf { { 1f } }

internal fun shouldDispatchSelection(selectedIndex: Int, targetIndex: Int): Boolean {
    return selectedIndex != targetIndex
}

@Composable
fun RowScope.FloatingBottomBarTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalFloatingTabScale.current
    Column(
        modifier = modifier
            .clip(ContinuousCapsule)
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    isBlurEnabled: Boolean,
    isDarkTheme: Boolean,
    enabled: Boolean = true,
    tabTags: List<String> = emptyList(),
    accentColor: Color,
    containerColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    val isLightTheme = !isDarkTheme
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { 8.dp.toPx() }
    // 模糊态降低不透明度透出背景，非模糊态为实色
    val resolvedContainerColor = if (isBlurEnabled) {
        containerColor.copy(alpha = 0.4f)
    } else {
        containerColor
    }
    val tabsBackdrop = rememberLayerBackdrop()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    var pendingSelectionPropagation by remember { mutableStateOf(false) }
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)

    class BottomBarAnimationHolder {
        var instance: BottomBarPressAnimation? = null
    }

    val holder = remember { BottomBarAnimationHolder() }
    val pressAnimation = remember(animationScope, tabsCount, density, isLtr) {
        BottomBarPressAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            valueVisibilityThreshold = FLOATING_BOTTOM_BAR_VALUE_VISIBILITY_THRESHOLD,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                val animation = holder.instance ?: return@BottomBarPressAnimation true
                if (tabWidthPx == 0f) return@BottomBarPressAnimation false
                val currentValue = animation.value
                val indicatorX = currentValue * tabWidthPx
                val padding = with(density) { 4.dp.toPx() }
                val globalTouchX = if (isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                pendingSelectionPropagation = shouldDispatchSelection(latestSelectedIndex, targetIndex)
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            },
        ).also { holder.instance = it }
    }

    LaunchedEffect(selectedIndex) {
        currentIndex = selectedIndex
        pendingSelectionPropagation = false
        pressAnimation.animateToValue(selectedIndex.toFloat())
    }
    LaunchedEffect(pressAnimation) {
        snapshotFlow { currentIndex to pendingSelectionPropagation }
            .drop(1)
            .collectLatest { (index, shouldPropagate) ->
                if (!shouldPropagate) return@collectLatest
                onSelected(index)
                pendingSelectionPropagation = false
            }
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx) {
        BottomBarInteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) {
                        (pressAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    } else {
                        size.width - (pressAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    },
                    size.height / 2f,
                )
            },
        )
    }

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .testTag("bottom_bar_track")
                .onGloballyPositioned { coordinates ->
                    totalWidthPx = coordinates.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = contentWidthPx / tabsCount
                }
                .graphicsLayer { translationX = panelOffset }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        if (isBlurEnabled) {
                            blur(blurRadiusPx)
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = 0f)
                    },
                    shadow = {
                        Shadow.Default.copy(
                            color = Color.Black.copy(alpha = if (isLightTheme) 0.1f else 0.2f),
                        )
                    },
                    onDrawSurface = { drawRect(resolvedContainerColor) },
                )
                .then(if (isBlurEnabled) interactiveHighlight.modifier else Modifier)
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )

        CompositionLocalProvider(
            LocalFloatingTabScale provides {
                if (isBlurEnabled) lerp(1f, 1.2f, pressAnimation.pressProgress) else 1f
            },
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { ContinuousCapsule },
                        effects = {
                            if (isBlurEnabled) {
                                blur(blurRadiusPx)
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = 0f)
                        },
                        onDrawSurface = { drawRect(resolvedContainerColor) },
                    )
                    .height(56.dp)
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }

        if (tabWidthPx > 0f) {
            val indicatorTag = tabTags.getOrNull(currentIndex)?.let { "bottom_indicator_$it" }
                ?: "bottom_indicator_$currentIndex"
            Box(
                Modifier
                    .testTag(indicatorTag)
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        val contentWidth = totalWidthPx - with(density) { 8.dp.toPx() }
                        val singleTabWidth = contentWidth / tabsCount
                        val progressOffset = pressAnimation.value * singleTabWidth
                        translationX = if (isLtr) {
                            progressOffset + panelOffset
                        } else {
                            -progressOffset + panelOffset
                        }
                    }
                    .then(if (isBlurEnabled && enabled) interactiveHighlight.gestureModifier else Modifier)
                    .then(if (enabled) pressAnimation.modifier else Modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { ContinuousCapsule },
                        effects = {},
                        highlight = {
                            Highlight.Default.copy(alpha = 0f)
                        },
                        shadow = {
                            Shadow(alpha = 0f)
                        },
                        innerShadow = {
                            InnerShadow(radius = 0.dp, alpha = 0f)
                        },
                        onDrawSurface = {
                            drawRect(
                                color = if (isLightTheme) {
                                    Color.Black.copy(alpha = 0.1f)
                                } else {
                                    Color.White.copy(alpha = 0.1f)
                                },
                            )
                        },
                    )
                    .height(56.dp)
                    .width(with(density) { ((totalWidthPx - 8.dp.toPx()) / tabsCount).toDp() }),
            )
        }
    }
}
