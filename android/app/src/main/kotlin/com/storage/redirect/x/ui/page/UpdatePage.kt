package com.storage.redirect.x.ui.page

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import java.util.Locale
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.PreferenceKeys
import com.storage.redirect.x.data.repository.UpdateRepository
import com.storage.redirect.x.data.repository.UpdateResult
import com.storage.redirect.x.ui.component.MarkdownText
import com.storage.redirect.x.ui.component.SRX_TOP_BAR_TRAILING_ICON_END_PADDING
import com.storage.redirect.x.ui.component.SrxTopAppBar
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面间距
private val UPDATE_PAGE_TOP_SPACING = 6.dp
private val UPDATE_PAGE_HORIZONTAL_PADDING = 12.dp
private val UPDATE_PAGE_BOTTOM_SPACING = 12.dp
private val UPDATE_DIALOG_CONTENT_SPACING = 12.dp
private val UPDATE_DIALOG_BUTTON_SPACING = 12.dp
private val UPDATE_RELEASE_NOTES_MAX_HEIGHT = 360.dp

@Composable
fun UpdatePage(
    onBack: () -> Unit,
    bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { UpdateRepository() }

    BackHandler(onBack = onBack)

    var checkOnLaunch by remember {
        mutableStateOf(AppPreferences.getBoolean(PreferenceKeys.CHECK_UPDATE_ON_LAUNCH, true))
    }
    var isChecking by remember { mutableStateOf(false) }
    val showUpdateDialog = remember { mutableStateOf(false) }
    val showLatestDialog = remember { mutableStateOf(false) }
    val showErrorDialog = remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateResult?>(null) }

    Column(modifier = Modifier.fillMaxSize().testTag("page_update")) {
        SrxTopAppBar(
            title = stringResource(R.string.settings_update),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                ) {
                    Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.common_close))
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(UPDATE_PAGE_TOP_SPACING))

            Card(modifier = Modifier.padding(horizontal = UPDATE_PAGE_HORIZONTAL_PADDING)) {
                SwitchPreference(
                    title = stringResource(R.string.settings_update_check_on_launch),
                    checked = checkOnLaunch,
                    onCheckedChange = { enabled ->
                        checkOnLaunch = enabled
                        AppPreferences.put(PreferenceKeys.CHECK_UPDATE_ON_LAUNCH, enabled)
                    },
                    modifier = Modifier.testTag("update_check_on_launch")
                )

                ArrowPreference(
                    title = stringResource(R.string.settings_update_check_now),
                    modifier = Modifier.testTag("update_check_now"),
                    summary = if (isChecking) stringResource(R.string.settings_update_checking) else null,
                    onClick = {
                        if (isChecking) return@ArrowPreference
                        scope.launch {
                            isChecking = true
                            try {
                                val result = repo.checkForUpdate()
                                updateResult = result
                                if (result.hasUpdate) {
                                    showUpdateDialog.value = true
                                } else {
                                    showLatestDialog.value = true
                                }
                            } catch (_: Exception) {
                                showErrorDialog.value = true
                            }
                            isChecking = false
                        }
                    }
                )
            }

            Spacer(Modifier.height(UPDATE_PAGE_BOTTOM_SPACING + bottomInnerPadding))
        }
    }

    // 发现新版本弹窗
    updateResult?.let { result ->
        UpdateAvailableDialog(
            show = showUpdateDialog,
            result = result,
            onDownload = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl)))
            }
        )
    }

    WindowDialog(
        show = showLatestDialog.value,
        title = stringResource(R.string.settings_update),
        onDismissRequest = { showLatestDialog.value = false },
    ) {
        Text(text = stringResource(R.string.settings_update_latest))
        Spacer(Modifier.height(UPDATE_DIALOG_CONTENT_SPACING))
        TextButton(
            text = stringResource(R.string.common_ok),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = { showLatestDialog.value = false },
            modifier = Modifier.fillMaxWidth()
        )
    }

    WindowDialog(
        show = showErrorDialog.value,
        title = stringResource(R.string.settings_update),
        onDismissRequest = { showErrorDialog.value = false },
    ) {
        Text(text = stringResource(R.string.settings_update_failed))
        Spacer(Modifier.height(UPDATE_DIALOG_CONTENT_SPACING))
        TextButton(
            text = stringResource(R.string.common_ok),
            onClick = { showErrorDialog.value = false },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun pickReleaseNotesForLocale(releaseNotes: String): String {
    val body = releaseNotes.trim()
    if (body.isEmpty()) return ""
    return if (Locale.getDefault().language == "zh") {
        body.substringBefore("<details>").trim()
    } else {
        body.substringAfter("<summary>English</summary>", body)
            .substringBefore("</details>")
            .trim()
    }
}

@Composable
private fun UpdateAvailableDialog(
    show: MutableState<Boolean>,
    result: UpdateResult,
    onDownload: () -> Unit,
) {
    val dialogTitle = "v${result.latestVersion} ${stringResource(R.string.settings_update_release_notes)}"
    WindowDialog(
        show = show.value,
        title = dialogTitle,
        onDismissRequest = { show.value = false },
    ) {
        Column {
            val releaseNotes = pickReleaseNotesForLocale(result.releaseNotes)
            if (releaseNotes.isNotBlank()) {
                MarkdownText(
                    text = releaseNotes,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = UPDATE_RELEASE_NOTES_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(UPDATE_DIALOG_CONTENT_SPACING))
            }

            Row {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { show.value = false },
                    modifier = Modifier.weight(1f).testTag("update_dialog_cancel")
                )
                Spacer(Modifier.width(UPDATE_DIALOG_BUTTON_SPACING))
                TextButton(
                    text = stringResource(R.string.settings_update_download),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        show.value = false
                        onDownload()
                    },
                    modifier = Modifier.weight(1f).testTag("update_dialog_download")
                )
            }
        }
    }
}

