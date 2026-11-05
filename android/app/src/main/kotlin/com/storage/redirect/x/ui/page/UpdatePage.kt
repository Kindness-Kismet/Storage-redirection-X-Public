package com.storage.redirect.x.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.UpdateChannel
import com.storage.redirect.x.data.repository.UpdateResult
import com.storage.redirect.x.ui.component.PAGE_CONTENT_TOP_PADDING
import com.storage.redirect.x.ui.component.PAGE_GROUP_GAP
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PreferenceIcon
import com.storage.redirect.x.ui.component.SrxBackButton
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.icon.AppIcons
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun UpdatePage(
    uiState: UpdateUiState,
    onCheck: () -> Unit,
    onCheckOnLaunchChange: (Boolean) -> Unit,
    onChannelChange: (UpdateChannel) -> Unit,
    onBack: () -> Unit,
    bottomInnerPadding: Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val status = when {
        uiState.isChecking -> stringResource(R.string.settings_update_checking)
        uiState.result is UpdateResult.Available -> stringResource(
            R.string.settings_update_toast,
            uiState.result.info.latestVersion,
        )
        uiState.result == UpdateResult.UpToDate -> stringResource(R.string.settings_update_latest)
        uiState.result == UpdateResult.Failed -> stringResource(R.string.settings_update_failed)
        else -> null
    }
    SrxScaffold(
        modifier = Modifier.testTag("page_update"),
        topBar = {
            SrxTopAppBar(
                title = stringResource(R.string.settings_update),
                scrollBehavior = scrollBehavior,
                navigationIcon = { SrxBackButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
        ) {
            Spacer(Modifier.height(innerPadding.calculateTopPadding() + PAGE_CONTENT_TOP_PADDING))
            Card(Modifier.padding(horizontal = PAGE_HORIZONTAL_PADDING)) {
                SwitchPreference(
                    title = stringResource(R.string.settings_update_check_on_launch),
                    startAction = { PreferenceIcon(AppIcons.Refresh) },
                    checked = uiState.shouldCheckOnLaunch,
                    onCheckedChange = onCheckOnLaunchChange,
                    modifier = Modifier.testTag("update_check_on_launch"),
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.settings_update_channel),
                    startAction = { PreferenceIcon(AppIcons.Swap) },
                    items = listOf(
                        stringResource(R.string.settings_update_channel_stable),
                        stringResource(R.string.settings_update_channel_test),
                    ),
                    selectedIndex = uiState.channel.ordinal,
                    onSelectedIndexChange = { onChannelChange(UpdateChannel.entries[it]) },
                    modifier = Modifier.testTag("update_channel"),
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_update_check_now),
                    startAction = { PreferenceIcon(AppIcons.Update, isEnabled = !uiState.isChecking) },
                    summary = status,
                    enabled = !uiState.isChecking,
                    onClick = onCheck,
                    modifier = Modifier.testTag("update_check_now"),
                )
            }
            Spacer(Modifier.height(PAGE_GROUP_GAP + bottomInnerPadding))
        }
    }
}
