// Adapted from Mishka (YuKongA), GPL-3.0.
package com.storage.redirect.x.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 状态语义色 token。集中管理运行状态、延迟、操作按钮的色谱并支持深浅色， 调用方一律走 token，避免散落的 `Color(0xFF...)`。 */
object StatusColors {

    /** 中性灰：未测试 / 未知态 */
    val neutral: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalAppDarkMode.current) Gray400Dark else Gray500Light

    /** 红：失败 / 停止 / 高延迟 */
    val danger: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalAppDarkMode.current) Red300Dark else Red600Light

    /** 黄：进行中 / 警告 / 中等延迟 */
    val warning: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalAppDarkMode.current) Amber300Dark else Amber700Light

    /** 绿：运行中 / 健康 / 低延迟 */
    val healthy: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalAppDarkMode.current) Green300Dark else Green600Light

    /** 延迟语义：null=未测、<0=超时、<200=优、<500=一般、>=500=差 */
    @Composable
    @ReadOnlyComposable
    fun delay(value: Int?): Color =
        when {
            value == null -> neutral
            value < 0 -> danger
            value < 200 -> healthy
            value < 500 -> warning
            else -> danger
        }

    /** 运行状态前景色。Monet 开启时 Running 跟随动态取色（primary）， Pending/Stopped 保持固定警示黄/红——警示语义不随壁纸取色漂移。 */
    @Composable
    @ReadOnlyComposable
    fun runState(state: RunState): Color =
        when (state) {
            RunState.Running ->
                if (LocalAppMonetEnabled.current) MiuixTheme.colorScheme.primary else healthy

            RunState.Pending -> warning
            RunState.Stopped -> danger
        }

    /** 运行状态卡片背景色。Monet 开启时 Running 用动态 secondaryContainer。 */
    @Composable
    @ReadOnlyComposable
    fun runStateContainer(state: RunState): Color {
        val isDark = LocalAppDarkMode.current
        return when (state) {
            RunState.Running ->
                if (LocalAppMonetEnabled.current) {
                    MiuixTheme.colorScheme.secondaryContainer
                } else if (isDark) {
                    Color(0xFF1A3825)
                } else {
                    Color(0xFFDFFAE4)
                }

            RunState.Pending -> if (isDark) Color(0xFF3A3420) else Color(0xFFFFF8E1)
            RunState.Stopped -> if (isDark) Color(0xFF3A2020) else Color(0xFFFDE8E8)
        }
    }

    /** 三色按钮（restart/stop/reload）的容器与文字色 */
    @Composable
    @ReadOnlyComposable
    fun actionButton(action: ActionKind): ActionPalette {
        val isDark = LocalAppDarkMode.current
        return when (action) {
            ActionKind.Restart ->
                ActionPalette(
                    container = if (isDark) Color(0xFF1B3A26) else Color(0xFFDFF5E3),
                    content = if (isDark) Color(0xFF66BB6A) else Color(0xFF43A047),
                )

            ActionKind.Stop ->
                ActionPalette(
                    container = if (isDark) Color(0xFF3A1B1B) else Color(0xFFFDE8E8),
                    content = if (isDark) Color(0xFFEF5350) else Color(0xFFE53935),
                )

            ActionKind.Reload ->
                ActionPalette(
                    container = if (isDark) Color(0xFF1B2D3A) else Color(0xFFE3F2FD),
                    content = if (isDark) Color(0xFF42A5F5) else Color(0xFF1E88E5),
                )
        }
    }

    val trafficUpload: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalAppDarkMode.current) Green300Dark else Green600Light

    val trafficDownload: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalAppDarkMode.current) Blue300Dark else Blue600Light

    /** 订阅配额用量：逼近耗尽时转警示色，其余跟随主题 primary */
    @Composable
    @ReadOnlyComposable
    fun usage(progress: Float): Color =
        when {
            progress >= 0.9f -> danger
            progress >= 0.75f -> warning
            else -> MiuixTheme.colorScheme.primary
        }

    /** Proxy 节点选中态背景 */
    val selectedNodeContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalAppDarkMode.current) Color(0xFF1A3040) else Color(0xFFE3F2FD)

    /** Proxy 节点固定态背景（URLTest / Fallback 被钉住），暖调以区别于冷调选中态 */
    val fixedNodeContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalAppDarkMode.current) Color(0xFF3A3420) else Color(0xFFFFF8E1)

    // —— 内部调色板。色值是手挑的，名字里的色阶只是记号，与 Material 官方色阶并不逐一对应 ——
    private val Green300Dark = Color(0xFF81C784)
    private val Green600Light = Color(0xFF4CAF50)
    private val Amber300Dark = Color(0xFFF9A825)
    private val Amber700Light = Color(0xFFFFB300)
    private val Red300Dark = Color(0xFFEF9A9A)
    private val Red600Light = Color(0xFFE53935)
    private val Gray400Dark = Color(0xFFBDBDBD)
    private val Gray500Light = Color(0xFF9E9E9E)
    private val Blue300Dark = Color(0xFF42A5F5)
    private val Blue600Light = Color(0xFF1E88E5)
}

enum class RunState {
    Running,
    Pending,
    Stopped,
}

enum class ActionKind {
    Restart,
    Stop,
    Reload,
}

data class ActionPalette(val container: Color, val content: Color)
