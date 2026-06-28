package com.storage.redirect.x.ui.page

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.BuildConfig
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.PreferenceKeys
import com.storage.redirect.x.ui.component.material.SegmentedColumn
import com.storage.redirect.x.ui.component.material.SegmentedDropdownItem
import com.storage.redirect.x.ui.component.material.SegmentedListItem
import com.storage.redirect.x.ui.component.material.SegmentedSwitchItem
import com.storage.redirect.x.ui.theme.AppThemeSettings
import com.storage.redirect.x.ui.theme.UiMode

private val MATERIAL_SETTINGS_TOP_SPACING = 16.dp
private val MATERIAL_SETTINGS_SECTION_SPACING = 20.dp
private val MATERIAL_SETTINGS_HORIZONTAL_PADDING = 16.dp
private val MATERIAL_SETTINGS_BOTTOM_SPACING = 16.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsPageMaterial(
    settingsViewModel: SettingsViewModel,
    uiState: SettingsUiState,
    onOpenTheme: () -> Unit,
    themeSettings: AppThemeSettings = AppThemeSettings(),
    onThemeSettingsChange: (AppThemeSettings) -> Unit = {},
    bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current

    if (uiState.isLogsPageVisible) {
        LogsPage(onBack = { settingsViewModel.setLogsPageVisible(false) })
        return
    }

    if (uiState.isLicensesPageVisible) {
        OpenSourceLicensesPage(onBack = { settingsViewModel.setLicensesPageVisible(false) })
        return
    }

    if (uiState.isUpdatePageVisible) {
        UpdatePage(
            onBack = { settingsViewModel.setUpdatePageVisible(false) },
            bottomInnerPadding = bottomInnerPadding,
        )
        return
    }

    val uiModeOptions = listOf(
        stringResource(R.string.settings_ui_mode_miuix),
        stringResource(R.string.settings_ui_mode_material),
    )
    val languageOptions = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_en),
        stringResource(R.string.language_zh_cn),
    )
    val languageIndex = AppPreferences.getInt(PreferenceKeys.LANGUAGE, 0).coerceIn(languageOptions.indices)
    val canOperateRoot = uiState.hasRoot == true && !uiState.isWorking
    val rootActionSummary = when {
        uiState.hasRoot != true -> stringResource(R.string.settings_backup_restore_root_required)
        uiState.isWorking -> stringResource(R.string.settings_action_running)
        else -> null
    }
    val fuseFixerSummary = rootActionSummary ?: stringResource(R.string.settings_fuse_fixer_summary)

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (uiState.hasRoot != true) return@rememberLauncherForActivityResult
        settingsViewModel.exportPackageConfigsZip(context.contentResolver, uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (uiState.hasRoot != true) return@rememberLauncherForActivityResult
        settingsViewModel.restorePackageConfigsFromUri(context.contentResolver, uri)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("page_settings")
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(MATERIAL_SETTINGS_TOP_SPACING))

            SegmentedColumn(
                title = stringResource(R.string.settings_appearance),
                modifier = Modifier.padding(horizontal = MATERIAL_SETTINGS_HORIZONTAL_PADDING),
                content = listOf(
                    {
                        SegmentedDropdownItem(
                            icon = Icons.Rounded.DesignServices,
                            title = stringResource(R.string.settings_ui_mode),
                            summary = stringResource(R.string.settings_ui_mode_summary),
                            items = uiModeOptions,
                            selectedIndex = UiMode.entries.indexOf(themeSettings.uiMode),
                            onItemSelected = { index ->
                                onThemeSettingsChange(themeSettings.copy(uiMode = UiMode.entries[index]))
                            },
                        )
                    },
                    {
                        MaterialArrowItem(
                            icon = Icons.Rounded.Style,
                            title = stringResource(R.string.theme_title),
                            summary = stringResource(R.string.settings_theme_summary),
                            onClick = onOpenTheme,
                        )
                    },
                    {
                        SegmentedDropdownItem(
                            title = stringResource(R.string.language_title),
                            items = languageOptions,
                            selectedIndex = languageIndex,
                            onItemSelected = { index ->
                                AppPreferences.put(PreferenceKeys.LANGUAGE, index)
                                (context as? Activity)?.recreate()
                            },
                        )
                    },
                ),
            )

            Spacer(Modifier.height(MATERIAL_SETTINGS_SECTION_SPACING))

            SegmentedColumn(
                title = stringResource(R.string.settings_tools),
                modifier = Modifier.padding(horizontal = MATERIAL_SETTINGS_HORIZONTAL_PADDING),
                content = listOf(
                    {
                        SegmentedSwitchItem(
                            title = stringResource(R.string.settings_fuse_fixer),
                            summary = fuseFixerSummary,
                            checked = uiState.isFuseFixerEnabled,
                            enabled = canOperateRoot,
                            onCheckedChange = { enabled ->
                                settingsViewModel.setFuseFixerEnabled(enabled)
                            },
                        )
                    },
                    {
                        MaterialArrowItem(
                            title = stringResource(R.string.logs_title),
                            summary = stringResource(R.string.settings_logs_entry_desc),
                            onClick = { settingsViewModel.setLogsPageVisible(true) },
                        )
                    },
                ),
            )

            Spacer(Modifier.height(MATERIAL_SETTINGS_SECTION_SPACING))

            SegmentedColumn(
                title = stringResource(R.string.settings_backup_restore),
                modifier = Modifier.padding(horizontal = MATERIAL_SETTINGS_HORIZONTAL_PADDING),
                content = listOf(
                    {
                        MaterialArrowItem(
                            icon = Icons.Rounded.FileUpload,
                            title = stringResource(R.string.settings_backup_title),
                            summary = rootActionSummary,
                            enabled = canOperateRoot,
                            onClick = {
                                if (!canOperateRoot) return@MaterialArrowItem
                                val fileName = "storage_redirect_x_apps_backup_${System.currentTimeMillis()}.zip"
                                backupLauncher.launch(fileName)
                            },
                        )
                    },
                    {
                        MaterialArrowItem(
                            icon = Icons.Rounded.Refresh,
                            title = stringResource(R.string.settings_restore_title),
                            summary = rootActionSummary,
                            enabled = canOperateRoot,
                            onClick = {
                                if (!canOperateRoot) return@MaterialArrowItem
                                restoreLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/octet-stream",
                                        "application/json",
                                        "text/plain",
                                    )
                                )
                            },
                        )
                    },
                ),
            )

            Spacer(Modifier.height(MATERIAL_SETTINGS_SECTION_SPACING))

            SegmentedColumn(
                title = stringResource(R.string.settings_about),
                modifier = Modifier.padding(horizontal = MATERIAL_SETTINGS_HORIZONTAL_PADDING),
                content = listOf(
                    {
                        MaterialArrowItem(
                            title = stringResource(R.string.settings_about),
                            value = "v${BuildConfig.VERSION_NAME}",
                            onClick = {},
                        )
                    },
                    {
                        MaterialArrowItem(
                            title = stringResource(R.string.settings_update),
                            modifier = Modifier.testTag("settings_update_entry"),
                            onClick = { settingsViewModel.setUpdatePageVisible(true) },
                        )
                    },
                    {
                        MaterialArrowItem(
                            title = stringResource(R.string.settings_open_source_licenses),
                            summary = stringResource(R.string.settings_open_source_licenses_desc),
                            onClick = { settingsViewModel.setLicensesPageVisible(true) },
                        )
                    },
                ),
            )

            Spacer(Modifier.height(MATERIAL_SETTINGS_BOTTOM_SPACING + bottomInnerPadding))
        }
    }
}

@Composable
private fun MaterialArrowItem(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    SegmentedListItem(
        modifier = modifier,
        enabled = enabled,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        leadingContent = icon?.let { { Icon(it, title) } },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            // 禁用态尾部内容统一降级为 disabled 色，避免误导可操作
            val trailingColor = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value != null) {
                    Text(
                        text = value,
                        color = trailingColor,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = trailingColor,
                )
            }
        },
    )
}
