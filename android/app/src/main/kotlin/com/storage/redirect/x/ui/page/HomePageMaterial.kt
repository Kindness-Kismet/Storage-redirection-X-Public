package com.storage.redirect.x.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.DeviceInfoCache
import com.storage.redirect.x.data.cache.HomeDataCache
import com.storage.redirect.x.ui.component.ModuleStatus
import com.storage.redirect.x.ui.component.material.TonalCard

// 仿 KSU 主页：横向状态大卡 + 并排计数卡 + 信息卡，统一 16dp 间距、24dp 内距
private val MATERIAL_HOME_HORIZONTAL_PADDING = 16.dp
private val MATERIAL_HOME_SECTION_GAP = 16.dp
private val MATERIAL_HOME_CARD_INNER_PADDING = 24.dp
private val MATERIAL_HOME_INFO_ITEM_GAP = 16.dp
private val MATERIAL_HOME_TITLE_GAP = 4.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomePageMaterial(
    bottomInnerPadding: Dp,
    showActionsMenu: MutableState<Boolean>,
    showRestartDialog: MutableState<Boolean>,
    isRestarting: Boolean,
    onConfirmRestart: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = scrollBehavior,
                actions = {
                    Box {
                        IconButton(onClick = { showActionsMenu.value = true }) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = stringResource(R.string.home_actions_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = showActionsMenu.value,
                            onDismissRequest = { showActionsMenu.value = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_action_restart_media)) },
                                onClick = {
                                    showActionsMenu.value = false
                                    showRestartDialog.value = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MATERIAL_HOME_HORIZONTAL_PADDING),
            verticalArrangement = Arrangement.spacedBy(MATERIAL_HOME_SECTION_GAP),
        ) {
            MaterialStatusCard(
                status = HomeDataCache.moduleStatus,
                version = HomeDataCache.moduleVersion,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MATERIAL_HOME_SECTION_GAP),
            ) {
                MaterialCountCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.home_redirected_apps),
                    value = HomeDataCache.redirectedAppsCount,
                )
                MaterialCountCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.home_redirect_count),
                    value = HomeDataCache.redirectCount.toString(),
                )
            }

            MaterialDeviceInfoCard()

            Spacer(Modifier.height(bottomInnerPadding))
        }
    }

    if (showRestartDialog.value) {
        AlertDialog(
            onDismissRequest = { showRestartDialog.value = false },
            title = { Text(stringResource(R.string.home_action_restart_media_confirm_title)) },
            text = { Text(stringResource(R.string.home_action_restart_media_confirm_summary)) },
            confirmButton = {
                TextButton(onClick = onConfirmRestart, enabled = !isRestarting) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog.value = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

// 主状态卡：横向图标 + 标题/副标题
@Composable
private fun MaterialStatusCard(
    status: ModuleStatus,
    version: String,
) {
    val colorScheme = MaterialTheme.colorScheme
    val versionLabel = "${stringResource(R.string.home_version)}: $version"
    val visual = when (status) {
        ModuleStatus.INSTALLED -> StatusVisual(
            icon = Icons.Rounded.CheckCircleOutline,
            container = colorScheme.secondaryContainer,
            onContainer = colorScheme.onSecondaryContainer,
            title = stringResource(R.string.home_module_installed),
            subtitle = versionLabel,
        )
        ModuleStatus.NOT_INSTALLED -> StatusVisual(
            icon = Icons.Rounded.ErrorOutline,
            container = colorScheme.errorContainer,
            onContainer = colorScheme.onErrorContainer,
            title = stringResource(R.string.home_module_not_installed),
            subtitle = versionLabel,
        )
        ModuleStatus.ERROR -> StatusVisual(
            icon = Icons.Rounded.ErrorOutline,
            container = colorScheme.errorContainer,
            onContainer = colorScheme.onErrorContainer,
            title = stringResource(R.string.common_error),
            subtitle = versionLabel,
        )
        ModuleStatus.LOADING -> StatusVisual(
            icon = Icons.Rounded.HourglassEmpty,
            container = colorScheme.secondaryContainer,
            onContainer = colorScheme.onSecondaryContainer,
            title = stringResource(R.string.common_loading),
            subtitle = versionLabel,
        )
    }

    TonalCard(containerColor = visual.container) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MATERIAL_HOME_CARD_INNER_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                tint = visual.onContainer,
            )
            Column(modifier = Modifier.padding(start = 20.dp)) {
                Text(
                    text = visual.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = visual.onContainer,
                )
                Spacer(Modifier.height(MATERIAL_HOME_TITLE_GAP))
                Text(
                    text = visual.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = visual.onContainer,
                )
            }
        }
    }
}

// 计数卡：标签 + 数值，并排展示核心统计
@Composable
private fun MaterialCountCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    TonalCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MATERIAL_HOME_CARD_INNER_PADDING, vertical = 16.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(MATERIAL_HOME_TITLE_GAP))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// 设备信息卡：label/value 列表
@Composable
private fun MaterialDeviceInfoCard() {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MATERIAL_HOME_INFO_ITEM_GAP),
        ) {
            MaterialInfoItem(stringResource(R.string.home_device_model), DeviceInfoCache.deviceModel)
            MaterialInfoItem(stringResource(R.string.home_android_version), DeviceInfoCache.androidVersion)
            MaterialInfoItem(stringResource(R.string.home_kernel_version), DeviceInfoCache.kernelVersion)
            MaterialInfoItem(stringResource(R.string.home_selinux_status), DeviceInfoCache.selinuxStatus)
            MaterialInfoItem(stringResource(R.string.home_system_arch), DeviceInfoCache.systemArch)
        }
    }
}

@Composable
private fun MaterialInfoItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private data class StatusVisual(
    val icon: ImageVector,
    val container: Color,
    val onContainer: Color,
    val title: String,
    val subtitle: String,
)
