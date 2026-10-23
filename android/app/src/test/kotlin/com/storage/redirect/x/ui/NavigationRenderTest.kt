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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.storage.redirect.x.MainPage
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.ui.navigation.AppNavigator
import com.storage.redirect.x.ui.navigation.LocalNavigator
import com.storage.redirect.x.ui.theme.*
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
@OptIn(InternalComposeUiApi::class)
class NavigationRenderTest {
    @Test
    fun fourTabsUseTheSameNavigationSurface() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        AppPreferences.init(activity)
        val navigator = AppNavigator()
        activity.setContent {
            CompositionLocalProvider(
                LocalEnableBlur provides true,
                LocalNavigator provides navigator,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { MainPage() }
            }
        }
        val decor = activity.window.decorView
        fun frames() =
            repeat(80) {
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
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
        fun nodes(): List<SemanticsNode> =
            roots(decor).flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
        frames()
        for (tab in listOf("home", "apps", "monitor", "settings")) {
            val target =
                nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "nav_$tab" }
            assertNotNull(target.config.getOrNull(SemanticsActions.OnClick)?.action)
            target.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
            frames()
            val selected =
                nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "nav_$tab" }
            assertEquals(true, selected.config.getOrNull(SemanticsProperties.Selected))
            assertNotNull(
                nodes().firstOrNull {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "page_$tab"
                }
            )
            val node = RenderNode(tab).apply { setPosition(0, 0, decor.width, decor.height) }
            decor.draw(node.beginRecording(decor.width, decor.height))
            node.endRecording()
            val image =
                HardwareRenderer::class
                    .java
                    .getDeclaredMethod(
                        "createHardwareBitmap",
                        RenderNode::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    .invoke(null, node, decor.width, decor.height) as Bitmap
            val output = File("build/reports/ui/$tab.png")
            requireNotNull(output.parentFile).mkdirs()
            image.outputStream(output)
        }
        controller.pause().stop().destroy()
    }

    private fun Bitmap.outputStream(output: File) {
        output.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
        recycle()
    }
}
