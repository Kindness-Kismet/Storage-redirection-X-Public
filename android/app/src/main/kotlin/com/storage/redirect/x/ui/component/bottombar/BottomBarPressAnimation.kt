package com.storage.redirect.x.ui.component.bottombar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val THRESHOLD_COMPARISON_EPSILON = 1e-6f
private const val PRESS_PROGRESS_VISIBILITY_THRESHOLD = 0.001f
private const val SCALE_VISIBILITY_THRESHOLD = 0.001f
private const val RELEASE_SETTLE_THRESHOLD_FRACTION = 0.025f

internal fun shouldAnimateToValue(
    currentValue: Float,
    targetValue: Float,
    valueVisibilityThreshold: Float,
): Boolean {
    return abs(currentValue - targetValue) + THRESHOLD_COMPARISON_EPSILON >= valueVisibilityThreshold
}

internal fun calculateReleaseSettleThreshold(valueRange: ClosedRange<Float>): Float {
    return (valueRange.endInclusive - valueRange.start) * RELEASE_SETTLE_THRESHOLD_FRACTION
}

internal fun shouldReleaseSettle(
    currentValue: Float,
    targetValue: Float,
    releaseSettleThreshold: Float,
): Boolean {
    return abs(currentValue - targetValue) < releaseSettleThreshold
}

class BottomBarPressAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    valueRange: ClosedRange<Float>,
    private val valueVisibilityThreshold: Float,
    initialScale: Float,
    private val pressedScale: Float,
    private val canDrag: (Offset) -> Boolean = { true },
    private val onDragStarted: BottomBarPressAnimation.(position: Offset) -> Unit,
    private val onDragStopped: BottomBarPressAnimation.() -> Unit,
    private val onDrag: BottomBarPressAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring(1f, 1000f, valueVisibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, valueVisibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, PRESS_PROGRESS_VISIBILITY_THRESHOLD)
    private val scaleXAnimationSpec = spring(0.6f, 250f, SCALE_VISIBILITY_THRESHOLD)
    private val scaleYAnimationSpec = spring(0.7f, 250f, SCALE_VISIBILITY_THRESHOLD)
    private val valueAnimation = Animatable(initialValue, valueVisibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, PRESS_PROGRESS_VISIBILITY_THRESHOLD)
    private val scaleXAnimation = Animatable(initialScale, SCALE_VISIBILITY_THRESHOLD)
    private val scaleYAnimation = Animatable(initialScale, SCALE_VISIBILITY_THRESHOLD)
    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()
    private val valueRange = valueRange
    private val initialScale = initialScale

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectBottomBarDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            val isInside = canDrag(change.position)
            val wasInside = canDrag(change.previousPosition)
            if (isInside && wasInside) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val releaseSettleThreshold = calculateReleaseSettleThreshold(valueRange)
                snapshotFlow { valueAnimation.value }
                    .filter {
                        shouldReleaseSettle(
                            currentValue = it,
                            targetValue = valueAnimation.targetValue,
                            releaseSettleThreshold = releaseSettleThreshold,
                        )
                    }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch {
                valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() }
            }
        }
    }

    fun animateToValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        if (!shouldAnimateToValue(
                currentValue = this.value,
                targetValue = targetValue,
                valueVisibilityThreshold = valueVisibilityThreshold,
            )
        ) {
            return
        }
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val targetVelocity = velocityTracker.calculateVelocity().x /
            (valueRange.endInclusive - valueRange.start)
        animationScope.launch {
            velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec)
        }
    }
}

class BottomBarInteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, PRESS_PROGRESS_VISIBILITY_THRESHOLD)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val pressProgressAnimation = Animatable(0f, PRESS_PROGRESS_VISIBILITY_THRESHOLD)
    private val positionAnimation = Animatable(
        Offset.Zero,
        Offset.VectorConverter,
        Offset.VisibilityThreshold,
    )
    private var startPosition = Offset.Zero

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            drawRect(
                Color.White.copy(alpha = 0.06f * progress),
                blendMode = BlendMode.Plus,
            )
            val highlightPosition = position(size, positionAnimation.value)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f * progress),
                        Color.Transparent,
                    ),
                    center = Offset(
                        highlightPosition.x.coerceIn(0f, size.width),
                        highlightPosition.y.coerceIn(0f, size.height),
                    ),
                    radius = size.minDimension * 1.2f,
                ),
                blendMode = BlendMode.Plus,
            )
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectBottomBarDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }
}
