package com.storage.redirect.x.ui.page

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.DeviceInfoCache
import com.storage.redirect.x.data.cache.HomeDataCache
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.ModuleRepository
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.ui.component.*
import com.storage.redirect.x.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun HomePage(bottomInnerPadding: Dp = 0.dp) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val moduleRepo = remember { ModuleRepository() }
    val configRepo = remember { ConfigRepository() }
    val scope = rememberCoroutineScope()
    var showRestart by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    LaunchedEffect(navigator.configVersion) {
        launch { DeviceInfoCache.ensureLoaded() }
        HomeDataCache.ensureLoaded(
            moduleRepo,
            configRepo,
            context.packageManager,
            context.packageName,
            force = navigator.configVersion > 0,
        )
    }
    ScreenScaffold(
        title = stringResource(R.string.nav_home),
        bottomPadding = bottomInnerPadding,
        modifier = Modifier.testTag("page_home"),
    ) {
        item(key = "status") {
            HomeStatusCards(
                HomeDataCache.moduleStatus,
                HomeDataCache.moduleVersion,
                HomeDataCache.redirectedAppsCount,
                HomeDataCache.redirectCount.toString(),
            )
        }
        item(key = "restart_media") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(vertical = 6.dp)) {
                TextButton(
                    text =
                        stringResource(
                            if (restarting) R.string.home_action_restart_media_running
                            else R.string.home_action_restart_media
                        ),
                    onClick = { showRestart = true },
                    modifier = Modifier.weight(1f).testTag("home_restart_media"),
                    enabled = !restarting,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
        item { SmallTitle(stringResource(R.string.home_device_info)) }
        groupedCardItems(
            "device",
            listOf(
                CardItem("model") {
                    BasicComponent(
                        title = stringResource(R.string.home_device_model),
                        summary = DeviceInfoCache.deviceModel.ifBlank { "--" },
                    )
                },
                CardItem("android") {
                    BasicComponent(
                        title = stringResource(R.string.home_android_version),
                        summary = DeviceInfoCache.androidVersion.ifBlank { "--" },
                    )
                },
                CardItem("kernel") {
                    BasicComponent(
                        title = stringResource(R.string.home_kernel_version),
                        summary = DeviceInfoCache.kernelVersion.ifBlank { "--" },
                    )
                },
                CardItem("selinux") {
                    BasicComponent(
                        title = stringResource(R.string.home_selinux_status),
                        endActions = { Text(DeviceInfoCache.selinuxStatus) },
                    )
                },
                CardItem("arch") {
                    BasicComponent(
                        title = stringResource(R.string.home_system_arch),
                        endActions = { Text(DeviceInfoCache.systemArch.ifBlank { "--" }) },
                    )
                },
            ),
        )
        item { SmallTitle(stringResource(R.string.settings_tools)) }
        groupedCardItems(
            "tools",
            listOf(
                CardItem("templates") {
                    ArrowPreference(
                        title = stringResource(R.string.apps_templates),
                        onClick = { navigator.push("templates") },
                    )
                },
                CardItem("logs") {
                    ArrowPreference(
                        title = stringResource(R.string.logs_title),
                        onClick = { navigator.push("logs") },
                    )
                },
            ),
        )
    }
    WindowDialog(
        show = showRestart,
        title = stringResource(R.string.home_action_restart_media_confirm_title),
        summary = stringResource(R.string.home_action_restart_media_confirm_summary),
        onDismissRequest = { showRestart = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                stringResource(R.string.common_cancel),
                onClick = { showRestart = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                stringResource(R.string.common_ok),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showRestart = false
                    scope.launch {
                        restarting = true
                        val ok = RootService.restartMediaProvider(configRepo.load(0).redirectApps)
                        restarting = false
                        Toast.makeText(
                                context,
                                if (ok) R.string.home_action_restart_media_success
                                else R.string.home_action_restart_media_failed,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
