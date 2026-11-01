package com.storage.redirect.x.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.icon.AppIcons
import com.storage.redirect.x.ui.theme.isInDarkTheme
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor

// 模块状态
enum class ModuleStatus { LOADING, INSTALLED, DISABLED, NOT_RUNNING, NOT_INSTALLED }

// 模块状态卡：状态头部 + 统计行；已激活绿色，未激活黄色，未安装红色，加载中灰色
@Composable
fun StatusCard(
    status: ModuleStatus,
    version: String,
    redirectedAppsCount: String,
    redirectCount: Int,
    modifier: Modifier = Modifier,
    themeMode: Int = 0,
) {
    val palette = statusPalette(status, isInDarkTheme(themeMode))
    val title = when (status) {
        ModuleStatus.INSTALLED -> stringResource(R.string.home_module_installed)
        ModuleStatus.DISABLED -> stringResource(R.string.home_module_disabled)
        ModuleStatus.NOT_RUNNING -> stringResource(R.string.home_module_not_running)
        ModuleStatus.NOT_INSTALLED -> stringResource(R.string.home_module_not_installed)
        ModuleStatus.LOADING -> stringResource(R.string.common_loading)
    }
    val subtitle = when (status) {
        ModuleStatus.INSTALLED -> stringResource(R.string.home_version_value, version)
        ModuleStatus.DISABLED -> stringResource(R.string.home_module_disabled_hint)
        ModuleStatus.NOT_RUNNING -> stringResource(R.string.home_module_not_running_hint)
        ModuleStatus.NOT_INSTALLED -> stringResource(R.string.home_module_not_installed_hint)
        ModuleStatus.LOADING -> stringResource(R.string.common_loading)
    }
    val icon = when (status) {
        ModuleStatus.INSTALLED -> AppIcons.CheckCircle
        ModuleStatus.DISABLED, ModuleStatus.NOT_RUNNING -> AppIcons.Warning
        ModuleStatus.NOT_INSTALLED -> AppIcons.Warning
        ModuleStatus.LOADING -> AppIcons.Pending
    }
    val dividerColor = colorScheme.onSurface.copy(alpha = 0.08f)

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 16.dp),
        colors = CardDefaults.defaultColors(color = palette.container),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = palette.accent)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 18.dp), color = dividerColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(top = 14.dp),
        ) {
            StatItem(
                value = redirectedAppsCount,
                label = stringResource(R.string.home_redirected_apps),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp),
                color = dividerColor,
            )
            StatItem(
                value = NumberFormat.getIntegerInstance().format(redirectCount),
                label = stringResource(R.string.home_redirect_count),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatItem(value: String, label: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface,
        )
        Text(
            text = label,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

private data class StatusPalette(val container: Color, val accent: Color)

// 动态取色时跟随主题色，否则使用固定的成功 / 错误配色
@Composable
private fun statusPalette(status: ModuleStatus, isDark: Boolean): StatusPalette = when (status) {
    ModuleStatus.INSTALLED -> if (isDynamicColor) {
        StatusPalette(colorScheme.secondaryContainer, colorScheme.primary)
    } else {
        StatusPalette(
            container = if (isDark) Color(0xFF11261A) else Color(0xFFE9F7EE),
            accent = if (isDark) Color(0xFF3CCB6C) else Color(0xFF1FAF54),
        )
    }
    ModuleStatus.DISABLED, ModuleStatus.NOT_RUNNING -> if (isDynamicColor) {
        StatusPalette(colorScheme.tertiaryContainer, STATUS_WARNING_COLOR)
    } else {
        StatusPalette(
            container = if (isDark) Color(0xFF332611) else Color(0xFFFFF7E6),
            accent = if (isDark) Color(0xFFFFB300) else STATUS_WARNING_COLOR,
        )
    }
    ModuleStatus.NOT_INSTALLED -> if (isDynamicColor) {
        StatusPalette(colorScheme.errorContainer, colorScheme.error)
    } else {
        StatusPalette(
            container = if (isDark) Color(0xFF310808) else Color(0xFFF8E2E2),
            accent = if (isDark) Color(0xFFFF5C5C) else Color(0xFFE5302F),
        )
    }
    ModuleStatus.LOADING -> StatusPalette(colorScheme.surfaceContainer, colorScheme.onSurfaceVariantSummary)
}
