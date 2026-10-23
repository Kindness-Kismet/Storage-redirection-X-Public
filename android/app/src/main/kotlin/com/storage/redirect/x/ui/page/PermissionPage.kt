package com.storage.redirect.x.ui.page

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.service.RootService
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 权限状态
enum class PermissionState {
    UNKNOWN,
    CHECKING,
    GRANTED,
    DENIED,
}

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
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationState = if (granted) PermissionState.GRANTED else PermissionState.DENIED
        }

    // 初始化时自动检查所有权限状态
    LaunchedEffect(Unit) {
        notificationState =
            if (checkNotificationPermission()) PermissionState.GRANTED else PermissionState.UNKNOWN
        // 自动检测 ROOT，su 已授权过的应用不会弹窗
        rootState = PermissionState.CHECKING
        val hasRoot = RootService.isRootAvailable()
        rootState = if (hasRoot) PermissionState.GRANTED else PermissionState.UNKNOWN
    }

    // 所有权限都已授予时自动跳转
    LaunchedEffect(rootState, notificationState) {
        if (rootState == PermissionState.GRANTED && notificationState == PermissionState.GRANTED) {
            onAllGranted()
        }
    }

    com.storage.redirect.x.ui.component.ScreenScaffold(
        title = stringResource(R.string.permission_title)
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            PermissionCard(
                title = stringResource(R.string.permission_root),
                description = stringResource(R.string.permission_root_desc),
                state = rootState,
                onRequest = {
                    rootState = PermissionState.CHECKING
                    scope.launch {
                        rootState =
                            if (RootService.isRootAvailable()) PermissionState.GRANTED
                            else PermissionState.DENIED
                    }
                },
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item {
                PermissionCard(
                    title = stringResource(R.string.permission_notification),
                    description = stringResource(R.string.permission_notification_desc),
                    state = notificationState,
                    onRequest = {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    state: PermissionState,
    onRequest: () -> Unit,
) {
    val granted = state == PermissionState.GRANTED
    val checking = state == PermissionState.CHECKING
    val label =
        stringResource(
            when (state) {
                PermissionState.GRANTED -> R.string.permission_granted
                PermissionState.CHECKING -> R.string.permission_checking
                PermissionState.DENIED -> R.string.permission_retry
                PermissionState.UNKNOWN -> R.string.permission_grant
            }
        )
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        top.yukonga.miuix.kmp.basic.BasicComponent(
            title = title,
            summary = description,
            endActions = {
                if (granted)
                    com.storage.redirect.x.ui.component.StatusBadge(
                        label,
                        MiuixTheme.colorScheme.primary,
                    )
                else TextButton(text = label, onClick = onRequest, enabled = !checking)
            },
        )
    }
}
