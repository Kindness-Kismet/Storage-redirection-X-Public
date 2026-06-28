package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor

// 模块状态
enum class ModuleStatus { LOADING, INSTALLED, NOT_INSTALLED, ERROR }

// 模块状态卡片（双列布局）
@Composable
fun StatusCard(
    status: ModuleStatus,
    version: String,
    redirectedAppsCount: String,
    redirectCount: String,
    themeMode: Int = 0,
    errorMessage: String = "",
    modifier: Modifier = Modifier
) {
    val statusInfo = when (status) {
        ModuleStatus.INSTALLED -> StatusInfo(
            icon = Icons.Rounded.CheckCircleOutline,
            cardColor = when {
                isDynamicColor -> colorScheme.secondaryContainer
                isInDarkTheme(themeMode) -> Color(0xFF1A3825)
                else -> Color(0xFFDFFAE4)
            },
            iconColor = if (isDynamicColor) {
                colorScheme.primary.copy(alpha = 0.8f)
            } else {
                Color(0xFF36D167)
            },
            title = stringResource(R.string.home_module_installed),
            subtitle = "${stringResource(R.string.home_version)}: $version"
        )
        ModuleStatus.NOT_INSTALLED -> StatusInfo(
            icon = Icons.Rounded.ErrorOutline,
            cardColor = when {
                isDynamicColor -> colorScheme.errorContainer
                isInDarkTheme(themeMode) -> Color(0xFF310808)
                else -> Color(0xFFF8E2E2)
            },
            iconColor = if (isDynamicColor) {
                colorScheme.error.copy(alpha = 0.8f)
            } else {
                Color(0xFFF72727)
            },
            title = stringResource(R.string.home_module_not_installed),
            subtitle = "${stringResource(R.string.home_version)}: $version"
        )
        ModuleStatus.ERROR -> StatusInfo(
            icon = Icons.Rounded.ErrorOutline,
            cardColor = when {
                isDynamicColor -> colorScheme.errorContainer
                isInDarkTheme(themeMode) -> Color(0xFF310808)
                else -> Color(0xFFF8E2E2)
            },
            iconColor = if (isDynamicColor) {
                colorScheme.error.copy(alpha = 0.8f)
            } else {
                Color(0xFFF72727)
            },
            title = stringResource(R.string.common_error),
            subtitle = errorMessage
        )
        ModuleStatus.LOADING -> StatusInfo(
            icon = Icons.Rounded.HourglassEmpty,
            cardColor = colorScheme.secondaryContainer,
            iconColor = colorScheme.secondary,
            title = stringResource(R.string.common_loading),
            subtitle = "${stringResource(R.string.home_version)}: $version"
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 左列：状态卡片（装饰图标 + 文字叠加）
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.defaultColors(color = statusInfo.cardColor)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 装饰图标（偏移到右下角）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(38.dp, 45.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        imageVector = statusInfo.icon,
                        contentDescription = null,
                        tint = statusInfo.iconColor,
                        modifier = Modifier.size(170.dp)
                    )
                }
                // 文字内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = statusInfo.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = statusInfo.subtitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 右列：统计卡片
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                insideMargin = PaddingValues(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.home_redirected_apps),
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = redirectedAppsCount,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                insideMargin = PaddingValues(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.home_redirect_count),
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = redirectCount,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// 状态信息数据类
private data class StatusInfo(
    val icon: ImageVector,
    val cardColor: Color,
    val iconColor: Color,
    val title: String,
    val subtitle: String
)
