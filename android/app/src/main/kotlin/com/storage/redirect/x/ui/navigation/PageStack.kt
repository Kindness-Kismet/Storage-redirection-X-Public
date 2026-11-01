package com.storage.redirect.x.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import java.io.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面准备就绪回调：页面完成首屏数据准备后调用，触发进场平移动画
val LocalPageReady = compositionLocalOf<() -> Unit> { {} }

// 页面栈：转场照搬 only_player 的 NavHost，下层页面始终保持组合，返回后状态不丢
@Stable
class PageStack<T : Serializable> internal constructor(
    private val scope: CoroutineScope,
    restoredRoutes: List<T>,
) {
    internal val entries = mutableStateListOf<PageEntry<T>>().apply {
        restoredRoutes.forEach { add(PageEntry(it, isShown = true)) }
    }

    // 不含已确认出栈、正在播放退出转场的页面
    val routes: List<T> get() = entries.filterNot { it.isRemoved }.map { it.route }

    val hasPages: Boolean get() = entries.any { !it.isRemoved }

    // 退出转场也播完后才为空
    val isEmpty: Boolean get() = entries.isEmpty()

    fun push(route: T, waitForReady: Boolean = false) {
        val entry = PageEntry(route, isShown = false, waitForReady = waitForReady)
        entries.add(entry)
        scope.launch {
            if (waitForReady) {
                entry.awaitReady()
            }
            entry.startEnter(this)
        }
    }

    fun pop() {
        val entry = topEntry() ?: return
        entry.isLeaving = true
        entry.isRemoved = true
        scope.launch {
            entry.time.snapTo(0f)
            finishPop(entry)
        }
    }

    // 直接回到底层，不播转场
    fun clear() {
        entries.clear()
    }

    // 页面自身的返回按钮只关闭自己，连点不会多退一层
    internal fun pop(entry: PageEntry<T>) {
        if (entry === topEntry()) pop()
    }

    // 预测返回：手势进度直接拖动出栈转场，松手后沿曲线播完剩余部分，取消则退回
    internal suspend fun startGesturePop(): PageEntry<T>? {
        val entry = topEntry() ?: return null
        entry.isLeaving = true
        entry.time.snapTo(0f)
        return entry
    }

    internal fun cancelGesturePop(entry: PageEntry<T>) {
        scope.launch {
            entry.time.animateTo(0f, pageTimeSpec(entry.time.value))
            entry.isLeaving = false
            entry.time.snapTo(1f)
        }
    }

    internal fun finishGesturePop(entry: PageEntry<T>) {
        entry.isRemoved = true
        scope.launch { finishPop(entry) }
    }

    private fun topEntry(): PageEntry<T>? = entries.lastOrNull { !it.isRemoved }

    private suspend fun finishPop(entry: PageEntry<T>) {
        entry.time.animateTo(1f, pageTimeSpec(1f - entry.time.value))
        entries.remove(entry)
    }
}

internal class PageEntry<T>(
    val route: T,
    isShown: Boolean,
    private val waitForReady: Boolean = false,
) {
    // 线性时间进度，0 为转场起点
    val time = Animatable(if (isShown) 1f else 0f)
    var isLeaving by mutableStateOf(false)
    var isRemoved by mutableStateOf(false)
    private var isStarted = isShown
    private val readyChannel = Channel<Unit>(Channel.CONFLATED)

    fun notifyReady() {
        readyChannel.trySend(Unit)
    }

    suspend fun awaitReady(timeoutMs: Long = 250L) {
        if (!waitForReady || isStarted) return
        withTimeoutOrNull(timeoutMs) {
            readyChannel.receive()
        }
    }

    fun startEnter(scope: CoroutineScope) {
        if (isStarted) return
        isStarted = true
        scope.launch {
            time.animateTo(1f, pageTimeSpec())
        }
    }

    // 1 为完全展示；进场与出场各自沿时间正向套曲线，对应 NavHost 的 enter 与 popExit
    fun shownFraction(): Float {
        val t = time.value
        if (isLeaving) return 1f - PageTransitionEasing.transform(t)
        return if (t >= 1f) 1f else PageTransitionEasing.transform(t)
    }
}

// 路由需可序列化，Activity 重建后恢复页面栈
@Composable
fun <T : Serializable> rememberPageStack(): PageStack<T> {
    val scope = rememberCoroutineScope()
    return rememberSaveable(
        saver = listSaver(
            save = { stack -> stack.routes },
            restore = { routes -> PageStack(scope, routes) },
        ),
    ) { PageStack(scope, emptyList()) }
}

@Composable
fun <T : Serializable> PageStackHost(
    stack: PageStack<T>,
    page: @Composable (route: T, onBack: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
) {
    val entries = stack.entries
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val surface = MiuixTheme.colorScheme.surface
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().pageLayer(null, entries.firstOrNull(), direction, stack.hasPages)) {
            base()
        }
        // 晚于底层页面注册，优先级更高；上层页面内部的返回处理再晚注册，优先于这里
        PredictiveBackHandler(enabled = stack.hasPages) { progress ->
            val entry = stack.startGesturePop() ?: return@PredictiveBackHandler
            try {
                progress.collect { event -> entry.time.snapTo(event.progress) }
            } catch (e: CancellationException) {
                stack.cancelGesturePop(entry)
                throw e
            }
            stack.finishGesturePop(entry)
        }
        entries.forEachIndexed { index, entry ->
            key(entry) {
                val isCovered = entries.drop(index + 1).any { !it.isRemoved }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pageLayer(entry, entries.getOrNull(index + 1), direction, isCovered)
                        .background(surface)
                        // 拦下触摸，避免穿透到下层页面
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                ) {
                    CompositionLocalProvider(LocalPageReady provides { entry.notifyReady() }) {
                        page(entry.route) { stack.pop(entry) }
                    }
                }
            }
        }
    }
}

// 自身进出整屏平移，上层进出时随之平移 1/4；被上层完全盖住时不绘制、不暴露无障碍节点
private fun Modifier.pageLayer(
    entry: PageEntry<*>?,
    above: PageEntry<*>?,
    direction: Float,
    isCovered: Boolean,
): Modifier = graphicsLayer {
    val enterOffset = entry?.let { size.width * (1f - it.shownFraction()) } ?: 0f
    val parallaxOffset = above?.let { size.width * PAGE_PARALLAX_FRACTION * it.shownFraction() } ?: 0f
    translationX = (enterOffset - parallaxOffset) * direction
}
    .drawWithContent { if (above == null || above.shownFraction() < 1f) drawContent() }
    .then(if (isCovered) Modifier.clearAndSetSemantics {} else Modifier)
