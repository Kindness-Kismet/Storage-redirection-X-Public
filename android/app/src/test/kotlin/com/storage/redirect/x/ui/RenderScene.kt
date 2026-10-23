package com.storage.redirect.x.ui

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.*
import com.storage.redirect.x.data.cache.AppPreferences
import java.io.File
import java.time.Duration
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer

@OptIn(InternalComposeUiApi::class)
internal class RenderScene(content: @Composable () -> Unit) : AutoCloseable {
    init {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    private val controller =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    val activity = controller.get()
    val decor: View
        get() = activity.window.decorView

    init {
        controller.windowFocusChanged(true)
        ShadowChoreographer.setPaused(true)
        AppPreferences.init(activity)
        activity.setContent(content = content)
    }

    private fun windows(): List<View> {
        val manager =
            Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getInstance")
                .invoke(null)
        val field = manager.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return ((field.get(manager) as List<View>) + decor).distinct()
    }

    fun frames(count: Int = 40) =
        repeat(count) {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            shadowOf(Looper.getMainLooper()).idle()
            windows()
                .filter { it.isAttachedToWindow }
                .forEach { view ->
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2553, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, 1179, 2553)
                    roots(view).forEach { it.measureAndLayoutForTest() }
                }
            shadowOf(Looper.getMainLooper()).idle()
        }

    private fun roots(view: View): List<RootForTest> = buildList {
        if (view is RootForTest) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(roots(view.getChildAt(it))) }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    fun nodes(): List<SemanticsNode> {
        return windows()
            .flatMap { roots(it) }
            .distinct()
            .flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    }

    fun tag(tag: String): SemanticsNode =
        nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    fun texts(text: String): List<SemanticsNode> =
        nodes().filter {
            it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true
        }

    fun clickable(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }
            .firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            ?: descendants(node).first {
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }

    fun tapText(text: String) {
        clickable(texts(text).first())
            .config
            .getOrNull(SemanticsActions.OnClick)!!
            .action!!
            .invoke()
        frames()
    }

    fun tapTag(tag: String) {
        clickable(tag(tag)).config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
        frames()
    }

    fun longPress(tag: String) {
        val center = tag(tag).boundsInWindow.center
        val down = SystemClock.uptimeMillis()
        decor.dispatchTouchEvent(
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, center.x, center.y, 0)
        )
        frames(1)
        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 100))
        decor.dispatchTouchEvent(
            MotionEvent.obtain(
                down,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                center.x,
                center.y,
                0,
            )
        )
        frames()
    }

    fun capture(name: String) {
        val node = RenderNode(name).apply { setPosition(0, 0, decor.width, decor.height) }
        decor.draw(node.beginRecording(decor.width, decor.height))
        node.endRecording()
        val bitmap =
            HardwareRenderer::class
                .java
                .getDeclaredMethod(
                    "createHardwareBitmap",
                    RenderNode::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(null, node, decor.width, decor.height) as Bitmap
        val file = File("build/reports/ui/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    override fun close() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
