package com.storage.redirect.x.ui.page

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.service.RootService
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 权限状态
enum class PermissionState { UNKNOWN, CHECKING, GRANTED, DENIED }
// 页面间距：横向边距，头部留白，标题区间距，卡片间距，底部收尾间距
private val PERMISSION_PAGE_HORIZONTAL_PADDING = 24.dp
private val PERMISSION_PAGE_HEADER_TOP_SPACING = 80.dp
private val PERMISSION_PAGE_TITLE_TOP_SPACING = 24.dp
private val PERMISSION_PAGE_TITLE_BODY_SPACING = 8.dp
private val PERMISSION_PAGE_SECTION_SPACING = 32.dp
private val PERMISSION_PAGE_CARD_SPACING = 12.dp
private val PERMISSION_PAGE_BOTTOM_SPACING = 32.dp
private val PERMISSION_CARD_CONTENT_PADDING = 16.dp
private val PERMISSION_CARD_ICON_TEXT_SPACING = 12.dp
private val PERMISSION_CARD_ACTION_SPACING = 8.dp

@Composable
fun PermissionPage(onAllGranted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rootState by remember { mutableStateOf(PermissionState.UNKNOWN) }
    var notificationState by remember { mutableStateOf(PermissionState.UNKNOWN) }

    // 检查通知权限状态
    fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // 通知权限请求回调
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationState = if (granted) PermissionState.GRANTED else PermissionState.DENIED
    }

    // 初始化时自动检查所有权限状态
    LaunchedEffect(Unit) {
        notificationState = if (checkNotificationPermission()) PermissionState.GRANTED else PermissionState.UNKNOWN
        // 自动检测 ROOT，su 已授权过的应用不会弹窗
        rootState = PermissionState.CHECKING
        val hasRoot = RootService.isRootAvailable()
        rootState = if (hasRoot) PermissionState.GRANTED else PermissionState.UNKNOWN
    }

    // 所有权限都已授予时自动跳转
    LaunchedEffect(rootState, notificationState) {
        if (rootState == PermissionState.GRANTED &&
            notificationState == PermissionState.GRANTED
        ) {
            onAllGranted()
        }
    }

    val colorScheme = MiuixTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PERMISSION_PAGE_HORIZONTAL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(PERMISSION_PAGE_HEADER_TOP_SPACING))

        // 图标
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MiuixIcons.Lock,
                contentDescription = null,
                tint = colorScheme.onPrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(PERMISSION_PAGE_TITLE_TOP_SPACING))

        Text(
            text = stringResource(R.string.permission_title),
            style = MiuixTheme.textStyles.headline1,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(PERMISSION_PAGE_TITLE_BODY_SPACING))

        Text(
            text = stringResource(R.string.permission_description),
            style = MiuixTheme.textStyles.body1,
            color = colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(PERMISSION_PAGE_SECTION_SPACING))

        // ROOT 权限
        PermissionCard(
            title = stringResource(R.string.permission_root),
            description = stringResource(R.string.permission_root_desc),
            state = rootState,
            buttonText = when (rootState) {
                PermissionState.UNKNOWN -> stringResource(R.string.permission_grant)
                PermissionState.CHECKING -> stringResource(R.string.permission_checking)
                PermissionState.GRANTED -> stringResource(R.string.permission_granted)
                PermissionState.DENIED -> stringResource(R.string.permission_retry)
            },
            onRequest = {
                rootState = PermissionState.CHECKING
                scope.launch {
                    val hasRoot = RootService.isRootAvailable()
                    rootState = if (hasRoot) PermissionState.GRANTED else PermissionState.DENIED
                }
            }
        )

        Spacer(Modifier.height(PERMISSION_PAGE_CARD_SPACING))

        // 通知权限（Android 13+ 才需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                title = stringResource(R.string.permission_notification),
                description = stringResource(R.string.permission_notification_desc),
                state = notificationState,
                buttonText = when (notificationState) {
                    PermissionState.UNKNOWN -> stringResource(R.string.permission_grant)
                    PermissionState.CHECKING -> stringResource(R.string.permission_checking)
                    PermissionState.GRANTED -> stringResource(R.string.permission_granted)
                    PermissionState.DENIED -> stringResource(R.string.permission_retry)
                },
                onRequest = {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            )

            Spacer(Modifier.height(PERMISSION_PAGE_CARD_SPACING))
        }

        Spacer(Modifier.height(PERMISSION_PAGE_BOTTOM_SPACING))
    }
}

// 单项权限卡片
@Composable
private fun PermissionCard(
    title: String,
    description: String,
    state: PermissionState,
    buttonText: String,
    onRequest: () -> Unit
) {
    val colorScheme = MiuixTheme.colorScheme
    val isGranted = state == PermissionState.GRANTED
    val isChecking = state == PermissionState.CHECKING

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(PERMISSION_CARD_CONTENT_PADDING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isGranted) colorScheme.primaryContainer
                        else colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGranted) MiuixIcons.Basic.Check else MiuixIcons.Info,
                    contentDescription = null,
                    tint = if (isGranted) colorScheme.primary else colorScheme.secondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(PERMISSION_CARD_ICON_TEXT_SPACING))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.W500
                )
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.onSurfaceVariantSummary
                )
            }

            if (!isGranted) {
                Spacer(Modifier.width(PERMISSION_CARD_ACTION_SPACING))
                TextButton(
                    text = buttonText,
                    onClick = onRequest,
                    enabled = !isChecking
                )
            }
        }
    }
}
