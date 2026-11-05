package com.storage.redirect.x.ui.page

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.ui.component.PAGE_CONTENT_TOP_PADDING
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PAGE_ITEM_GAP
import com.storage.redirect.x.ui.component.STATUS_SUCCESS_COLOR
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.icon.AppIcons
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// 权限状态
enum class PermissionState { UNKNOWN, CHECKING, GRANTED, DENIED }

private val PERMISSION_CARD_CONTENT_PADDING = 16.dp
private val PERMISSION_CARD_ICON_SIZE = 24.dp
private val PERMISSION_CARD_ICON_TEXT_SPACING = 12.dp
private val PERMISSION_CARD_ACTION_SPACING = 8.dp
// 卡片右侧的小号胶囊：授权按钮与已授权标签同高
private val PERMISSION_ACTION_HEIGHT = 34.dp
private const val PERMISSION_GRANTED_BACKGROUND_ALPHA = 0.12f

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

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    SrxScaffold(
        topBar = {
            SrxTopAppBar(
                title = stringResource(R.string.permission_title),
                subtitle = stringResource(R.string.permission_description),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(padding)
                .padding(horizontal = PAGE_HORIZONTAL_PADDING, vertical = PAGE_CONTENT_TOP_PADDING),
            verticalArrangement = Arrangement.spacedBy(PAGE_ITEM_GAP),
        ) {
            PermissionCard(
                icon = AppIcons.Shield,
                title = stringResource(R.string.permission_root),
                description = stringResource(R.string.permission_root_desc),
                state = rootState,
                onRequest = {
                    rootState = PermissionState.CHECKING
                    scope.launch {
                        val hasRoot = RootService.isRootAvailable()
                        rootState = if (hasRoot) PermissionState.GRANTED else PermissionState.DENIED
                    }
                }
            )
            // 通知权限（Android 13+ 才需要）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    icon = AppIcons.Notification,
                    title = stringResource(R.string.permission_notification),
                    description = stringResource(R.string.permission_notification_desc),
                    state = notificationState,
                    onRequest = {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            }
        }
    }
}

// 单项权限卡片：已授权显示绿色标签，否则显示授权按钮
@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    state: PermissionState,
    onRequest: () -> Unit
) {
    val colorScheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(PERMISSION_CARD_CONTENT_PADDING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(PERMISSION_CARD_ICON_SIZE)
            )
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
            Spacer(Modifier.width(PERMISSION_CARD_ACTION_SPACING))
            if (state == PermissionState.GRANTED) {
                GrantedBadge()
            } else {
                TextButton(
                    text = stringResource(
                        when (state) {
                            PermissionState.CHECKING -> R.string.permission_checking
                            PermissionState.DENIED -> R.string.permission_retry
                            else -> R.string.permission_grant
                        }
                    ),
                    onClick = onRequest,
                    enabled = state != PermissionState.CHECKING,
                    cornerRadius = PERMISSION_ACTION_HEIGHT / 2,
                    minHeight = PERMISSION_ACTION_HEIGHT,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 14.dp),
                    textStyle = MiuixTheme.textStyles.button.copy(fontSize = 14.sp),
                )
            }
        }
    }
}

@Composable
private fun GrantedBadge() {
    Text(
        text = stringResource(R.string.permission_granted),
        modifier = Modifier
            .background(
                STATUS_SUCCESS_COLOR.copy(alpha = PERMISSION_GRANTED_BACKGROUND_ALPHA),
                RoundedCornerShape(PERMISSION_ACTION_HEIGHT / 2),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = STATUS_SUCCESS_COLOR,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}
