package com.storage.redirect.x.ui.page

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.DeviceInfoCache
import com.storage.redirect.x.data.cache.HomeDataCache
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.ModuleRepository
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.ui.component.ModuleStatus
import com.storage.redirect.x.ui.component.PAGE_CONTENT_TOP_PADDING
import com.storage.redirect.x.ui.component.PAGE_GROUP_GAP
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PreferenceIcon
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.component.StatusCard
import com.storage.redirect.x.ui.icon.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// 设备信息数值最大宽度，超出换行保持右对齐
private val HOME_INFO_VALUE_MAX_WIDTH = 180.dp
private val HOME_DIALOG_BUTTON_SPACING = 12.dp

@Composable
fun HomePage(
    themeMode: Int = 0,
    bottomInnerPadding: Dp = 0.dp,
    onMenuStateChange: (Boolean) -> Unit = {},
    onTemplatesClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val moduleRepo = remember { ModuleRepository() }
    val configRepo = remember { ConfigRepository() }
    val scope = rememberCoroutineScope()

    var isRestartDialogVisible by remember { mutableStateOf(false) }
    var isRestarting by remember { mutableStateOf(false) }

    LaunchedEffect(isRestartDialogVisible) {
        onMenuStateChange(isRestartDialogVisible)
    }

    LaunchedEffect(Unit) {
        HomeDataCache.ensureLoaded(moduleRepo, configRepo, context.packageManager, context.packageName)
        DeviceInfoCache.ensureLoaded()
        while (isActive) {
            delay(3000)
            HomeDataCache.refreshStats(moduleRepo)
        }
    }

    val onConfirmRestart: () -> Unit = {
        isRestartDialogVisible = false
        scope.launch {
            isRestarting = true
            Toast.makeText(context, R.string.home_action_restart_media_running, Toast.LENGTH_SHORT).show()
            val config = configRepo.load(0)
            val isOk = RootService.restartMediaProvider(config.redirectApps)
            isRestarting = false
            val msgRes = if (isOk) {
                R.string.home_action_restart_media_success
            } else {
                R.string.home_action_restart_media_failed
            }
            Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
        }
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val isModuleInstalled = HomeDataCache.moduleStatus == ModuleStatus.INSTALLED

    SrxScaffold(
        modifier = Modifier.testTag("page_home"),
        topBar = {
            SrxTopAppBar(
                title = stringResource(R.string.home_title),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val topPadding = innerPadding.calculateTopPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
        ) {
            Spacer(Modifier.height(topPadding + PAGE_CONTENT_TOP_PADDING))

            StatusCard(
                status = HomeDataCache.moduleStatus,
                version = HomeDataCache.moduleVersion,
                redirectedAppsCount = HomeDataCache.redirectedAppsCount,
                redirectCount = HomeDataCache.redirectCount,
                themeMode = themeMode,
                modifier = Modifier.padding(horizontal = PAGE_HORIZONTAL_PADDING)
            )

            Spacer(Modifier.height(PAGE_GROUP_GAP))

            QuickActionsCard(
                isRestartEnabled = isModuleInstalled && !isRestarting,
                isModuleInstalled = isModuleInstalled,
                onTemplatesClick = onTemplatesClick,
                onLogsClick = onLogsClick,
                onRestartClick = { isRestartDialogVisible = true },
            )

            Spacer(Modifier.height(PAGE_GROUP_GAP))

            DeviceInfoCard()

            Spacer(Modifier.height(PAGE_GROUP_GAP + bottomInnerPadding))
        }
    }

    WindowDialog(
        show = isRestartDialogVisible,
        title = stringResource(R.string.home_action_restart_media_confirm_title),
        onDismissRequest = { isRestartDialogVisible = false }
    ) {
        Column {
            Text(text = stringResource(R.string.home_action_restart_media_confirm_summary))
            Spacer(Modifier.height(HOME_DIALOG_BUTTON_SPACING))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { isRestartDialogVisible = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(HOME_DIALOG_BUTTON_SPACING))
                TextButton(
                    text = stringResource(R.string.common_ok),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = !isRestarting,
                    onClick = onConfirmRestart,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// 快捷操作：模板、日志、重启媒体工具；重启依赖模块运行
@Composable
private fun QuickActionsCard(
    isRestartEnabled: Boolean,
    isModuleInstalled: Boolean,
    onTemplatesClick: () -> Unit,
    onLogsClick: () -> Unit,
    onRestartClick: () -> Unit,
) {
    SmallTitle(text = stringResource(R.string.home_quick_actions))
    Card(modifier = Modifier.padding(horizontal = PAGE_HORIZONTAL_PADDING)) {
        ArrowPreference(
            title = stringResource(R.string.home_action_templates),
            summary = stringResource(R.string.home_action_templates_summary),
            startAction = { PreferenceIcon(AppIcons.Layers) },
            modifier = Modifier.testTag("home_action_templates"),
            onClick = onTemplatesClick,
        )
        ArrowPreference(
            title = stringResource(R.string.home_action_logs),
            summary = stringResource(R.string.home_action_logs_summary),
            startAction = { PreferenceIcon(AppIcons.Document) },
            modifier = Modifier.testTag("home_action_logs"),
            onClick = onLogsClick,
        )
        ArrowPreference(
            title = stringResource(R.string.home_action_restart_media),
            summary = if (isModuleInstalled) {
                stringResource(R.string.home_action_restart_media_summary)
            } else {
                stringResource(R.string.home_action_restart_media_unavailable)
            },
            startAction = { PreferenceIcon(AppIcons.Restart, isEnabled = isRestartEnabled) },
            modifier = Modifier.testTag("home_action_restart_media"),
            enabled = isRestartEnabled,
            onClick = onRestartClick,
        )
    }
}

// 设备信息：标题 + 右侧数值单行
@Composable
private fun DeviceInfoCard() {
    SmallTitle(text = stringResource(R.string.home_device_info))
    Card(modifier = Modifier.padding(horizontal = PAGE_HORIZONTAL_PADDING)) {
        DeviceInfoRow(stringResource(R.string.home_device_model), DeviceInfoCache.deviceModel)
        DeviceInfoRow(stringResource(R.string.home_android_version), DeviceInfoCache.androidVersion)
        DeviceInfoRow(stringResource(R.string.home_kernel_version), DeviceInfoCache.kernelVersion)
        DeviceInfoRow(stringResource(R.string.home_selinux_status), DeviceInfoCache.selinuxStatus)
        DeviceInfoRow(stringResource(R.string.home_system_arch), DeviceInfoCache.systemArch)
    }
}

@Composable
private fun DeviceInfoRow(title: String, value: String) {
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = value,
                modifier = Modifier.widthIn(max = HOME_INFO_VALUE_MAX_WIDTH),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.End,
            )
        },
    )
}
