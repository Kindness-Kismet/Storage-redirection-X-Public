package com.storage.redirect.x.ui.page

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.DeviceInfoCache
import com.storage.redirect.x.data.cache.HomeDataCache
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.ModuleRepository
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.ui.component.SRX_TOP_BAR_TRAILING_ICON_END_PADDING
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.component.StatusCard
import com.storage.redirect.x.ui.theme.LocalUiMode
import com.storage.redirect.x.ui.theme.UiMode
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面间距：顶栏下留白，区块间距，卡片横向边距，信息卡正文内边距与收尾间距
private val HOME_PAGE_TOP_SPACING = 6.dp
private val HOME_PAGE_SECTION_SPACING = 12.dp
private val HOME_PAGE_HORIZONTAL_PADDING = 12.dp
private val HOME_PAGE_BOTTOM_SPACING = 12.dp
private val HOME_INFO_TEXT_TOP_SPACING = 2.dp
private val HOME_INFO_TEXT_DEFAULT_BOTTOM_SPACING = 24.dp
private val HOME_INFO_TEXT_NO_BOTTOM_SPACING = 0.dp
private val HOME_INFO_CARD_CONTENT_PADDING = 16.dp
private val HOME_POPUP_MARGIN = 8.dp
private val HOME_DIALOG_BUTTON_SPACING = 12.dp

// 顶栏弹出菜单沿用 Apps 页样式，统一与系统状态栏保持安全距离
private val homeTopBarPopupPositionProvider = object : PopupPositionProvider by ListPopupDefaults.DropdownPositionProvider {
    override fun getMargins(): PaddingValues = PaddingValues(
        horizontal = HOME_POPUP_MARGIN,
        vertical = HOME_POPUP_MARGIN
    )
}

@Composable
fun HomePage(
    themeMode: Int = 0,
    bottomInnerPadding: Dp = 0.dp,
    onMenuStateChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val moduleRepo = remember { ModuleRepository() }
    val configRepo = remember { ConfigRepository() }
    val scope = rememberCoroutineScope()

    val showActionsMenu = remember { mutableStateOf(false) }
    val showRestartMediaDialog = remember { mutableStateOf(false) }
    var isRestarting by remember { mutableStateOf(false) }

    // 菜单展开状态上抛，供父级禁用底栏
    LaunchedEffect(showActionsMenu.value) {
        onMenuStateChange(showActionsMenu.value)
    }

    LaunchedEffect(Unit) {
        HomeDataCache.ensureLoaded(moduleRepo, configRepo, context.packageManager, context.packageName)
        DeviceInfoCache.ensureLoaded()
    }

    val onConfirmRestart: () -> Unit = {
        showRestartMediaDialog.value = false
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

    when (LocalUiMode.current) {
        UiMode.Miuix -> HomePageMiuix(
            themeMode = themeMode,
            bottomInnerPadding = bottomInnerPadding,
            showActionsMenu = showActionsMenu,
            showRestartDialog = showRestartMediaDialog,
            isRestarting = isRestarting,
            onConfirmRestart = onConfirmRestart,
        )

        UiMode.Material -> HomePageMaterial(
            bottomInnerPadding = bottomInnerPadding,
            showActionsMenu = showActionsMenu,
            showRestartDialog = showRestartMediaDialog,
            isRestarting = isRestarting,
            onConfirmRestart = onConfirmRestart,
        )
    }
}

@Composable
private fun HomePageMiuix(
    themeMode: Int,
    bottomInnerPadding: Dp,
    showActionsMenu: MutableState<Boolean>,
    showRestartDialog: MutableState<Boolean>,
    isRestarting: Boolean,
    onConfirmRestart: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("page_home"),
    ) {
        SrxTopAppBar(
            title = stringResource(R.string.home_title),
            scrollBehavior = scrollBehavior,
            actions = {
                Box {
                    IconButton(
                        onClick = { showActionsMenu.value = true },
                        modifier = Modifier.padding(end = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = stringResource(R.string.home_actions_menu)
                        )
                    }
                    OverlayListPopup(
                        show = showActionsMenu.value,
                        popupPositionProvider = homeTopBarPopupPositionProvider,
                        alignment = PopupPositionProvider.Align.TopEnd,
                        onDismissRequest = { showActionsMenu.value = false },
                    ) {
                        ListPopupColumn {
                            DropdownImpl(
                                text = stringResource(R.string.home_action_restart_media),
                                optionSize = 1,
                                isSelected = false,
                                index = 0,
                                dropdownColors = DropdownDefaults.dropdownColors(),
                                onSelectedIndexChange = {
                                    showActionsMenu.value = false
                                    showRestartDialog.value = true
                                }
                            )
                        }
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(HOME_PAGE_TOP_SPACING))

            // 模块状态卡片
            StatusCard(
                status = HomeDataCache.moduleStatus,
                version = HomeDataCache.moduleVersion,
                redirectedAppsCount = HomeDataCache.redirectedAppsCount,
                redirectCount = HomeDataCache.redirectCount.toString(),
                themeMode = themeMode,
                modifier = Modifier.padding(horizontal = HOME_PAGE_HORIZONTAL_PADDING)
            )

            Spacer(Modifier.height(HOME_PAGE_SECTION_SPACING))

            // 设备信息
            InfoCard()

            Spacer(Modifier.height(HOME_PAGE_BOTTOM_SPACING + bottomInnerPadding))
        }
    }

    WindowDialog(
        show = showRestartDialog.value,
        title = stringResource(R.string.home_action_restart_media_confirm_title),
        onDismissRequest = { showRestartDialog.value = false }
    ) {
        Column {
            Text(text = stringResource(R.string.home_action_restart_media_confirm_summary))
            Spacer(Modifier.height(HOME_DIALOG_BUTTON_SPACING))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { showRestartDialog.value = false },
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

// 设备信息卡片
@Composable
private fun InfoCard() {
    val colorScheme = MiuixTheme.colorScheme

    @Composable
    fun InfoText(
        title: String,
        content: String,
        bottomPadding: Dp = HOME_INFO_TEXT_DEFAULT_BOTTOM_SPACING
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface
        )
        Text(
            text = content,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = HOME_INFO_TEXT_TOP_SPACING, bottom = bottomPadding)
        )
    }

    Card(modifier = Modifier.padding(horizontal = HOME_PAGE_HORIZONTAL_PADDING)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HOME_INFO_CARD_CONTENT_PADDING)
        ) {
            InfoText(
                title = stringResource(R.string.home_device_model),
                content = DeviceInfoCache.deviceModel
            )
            InfoText(
                title = stringResource(R.string.home_android_version),
                content = DeviceInfoCache.androidVersion
            )
            InfoText(
                title = stringResource(R.string.home_kernel_version),
                content = DeviceInfoCache.kernelVersion
            )
            InfoText(
                title = stringResource(R.string.home_selinux_status),
                content = DeviceInfoCache.selinuxStatus
            )
            InfoText(
                title = stringResource(R.string.home_system_arch),
                content = DeviceInfoCache.systemArch,
                bottomPadding = HOME_INFO_TEXT_NO_BOTTOM_SPACING
            )
        }
    }
}
