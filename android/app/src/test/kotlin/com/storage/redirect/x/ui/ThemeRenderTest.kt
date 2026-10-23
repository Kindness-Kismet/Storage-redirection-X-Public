package com.storage.redirect.x.ui

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.storage.redirect.x.ui.page.ColorPalettePage
import com.storage.redirect.x.ui.theme.*
import com.storage.redirect.x.ui.util.LocalTopBarBlurStyle
import java.io.File
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ThemeRenderTest {
    @Test fun lightSolid() = render("light-solid", ColorMode.LIGHT, false)

    @Test fun lightGlass() = render("light-glass", ColorMode.LIGHT, true)

    @Test fun darkSolid() = render("dark-solid", ColorMode.DARK, false)

    @Test fun darkGlass() = render("dark-glass", ColorMode.DARK, true)

    @Test
    fun progressiveGlass() =
        render("progressive", ColorMode.LIGHT, true, TopBarBlurStyle.Progressive)

    @OptIn(InternalComposeUiApi::class)
    private fun render(
        name: String,
        mode: ColorMode,
        blur: Boolean,
        style: TopBarBlurStyle = TopBarBlurStyle.Gaussian,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val settings =
            AppThemeSettings(colorMode = mode, enableBlur = blur, topBarBlurStyle = style)
        activity.setContent {
            CompositionLocalProvider(
                LocalEnableBlur provides blur,
                LocalTopBarBlurStyle provides style,
            ) {
                AppTheme(settings) { ColorPalettePage(settings, {}, {}) }
            }
        }
        val decor = activity.window.decorView
        repeat(30) {
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2553, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, 1179, 2553)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        fun roots(view: View): List<RootForTest> = buildList {
            if (view is RootForTest) add(view)
            if (view is ViewGroup) repeat(view.childCount) { addAll(roots(view.getChildAt(it))) }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::nodes)
        val labels =
            roots(decor)
                .flatMap { nodes(it.semanticsOwner.unmergedRootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
        assertTrue("Missing blur switch: $labels", "模糊效果" in labels)
        assertTrue("Missing theme selector: $labels", "主题模式" in labels)
        val titleNames = setOf("主题模式", "Monet 取色", "模糊效果", "界面缩放")
        val titleBounds =
            roots(decor)
                .flatMap { nodes(it.semanticsOwner.unmergedRootSemanticsNode) }
                .filter {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text in titleNames
                    } == true
                }
                .map { it.boundsInRoot }
        assertTrue(
            "Preference titles do not share a leading edge: $titleBounds",
            titleBounds.maxOf { it.left } - titleBounds.minOf { it.left } < 1f,
        )
        val renderNode = RenderNode("theme").apply { setPosition(0, 0, decor.width, decor.height) }
        decor.draw(renderNode.beginRecording(decor.width, decor.height))
        renderNode.endRecording()
        val hardware =
            HardwareRenderer::class
                .java
                .getDeclaredMethod(
                    "createHardwareBitmap",
                    RenderNode::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(null, renderNode, decor.width, decor.height) as Bitmap
        val bitmap = requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
        hardware.recycle()
        val output = File("build/reports/ui/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Theme rendered into an empty surface", bitmap.getPixel(500, 800) != 0)
        bitmap.recycle()
        controller.pause().stop().destroy()
    }
}
