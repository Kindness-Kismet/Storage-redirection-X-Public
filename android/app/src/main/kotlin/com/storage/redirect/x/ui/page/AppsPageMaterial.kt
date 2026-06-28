package com.storage.redirect.x.ui.page

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.AppInfo
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.repository.TemplateApplyMode
import com.storage.redirect.x.data.service.SystemUser
import com.storage.redirect.x.ui.component.material.LocalListItemShapes
import com.storage.redirect.x.ui.component.material.SegmentedListItem
import com.storage.redirect.x.ui.component.material.defaultSegmentedColors
import com.storage.redirect.x.ui.component.material.defaultSingleSegmentedShape

// 仿 KSU 超级用户列表：LargeFlexibleTopAppBar + 搜索 + 分段列表项 + 状态标签
private val APPS_M_CONTENT_PADDING = 16.dp
private val APPS_M_ITEM_GAP = 2.dp
private val APPS_M_ICON_SIZE = 48.dp
private val APPS_M_TAG_GAP = 4.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppsPageMaterial(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val redirectedSet = remember(data.redirectedPackages) { data.redirectedPackages.toHashSet() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("page_apps")
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (data.isBatchMode) {
                BatchTopBar(
                    selectedCount = data.selectedBatchCount,
                    onCancel = actions.onCancelBatch,
                    onSelectAll = actions.onSelectAll,
                    onInvert = actions.onInvertSelection,
                    onRestore = actions.onShowRestoreRules,
                    onApplyTemplate = actions.onShowTemplatePicker,
                )
            } else {
                NormalTopBar(
                    scrollBehavior = scrollBehavior,
                    systemUsers = data.systemUsers,
                    currentUserIndex = data.currentUserIndex,
                    showSystemApps = data.showSystemApps,
                    redirectedFirst = data.redirectedFirst,
                    isSortByName = data.isSortByName,
                    showUserMenu = dialogs.showUserMenu,
                    showFilterMenu = dialogs.showFilterMenu,
                    onOpenTemplates = actions.onOpenTemplates,
                    onRefresh = actions.onRefresh,
                    onUserSelected = actions.onUserSelected,
                    onSortByName = actions.onSortByName,
                    onSortByInstallTime = actions.onSortByInstallTime,
                    onToggleSystemApps = actions.onToggleSystemApps,
                    onToggleRedirectedFirst = actions.onToggleRedirectedFirst,
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TextField(
                value = data.searchQuery,
                onValueChange = actions.onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = APPS_M_CONTENT_PADDING, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.apps_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (data.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { actions.onSearchChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_cancel))
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            when {
                data.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                data.filteredApps.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.apps_no_apps),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = data.listState,
                    verticalArrangement = Arrangement.spacedBy(APPS_M_ITEM_GAP),
                    contentPadding = PaddingValues(
                        start = APPS_M_CONTENT_PADDING,
                        end = APPS_M_CONTENT_PADDING,
                        top = 4.dp,
                        bottom = APPS_M_CONTENT_PADDING + data.bottomInnerPadding,
                    ),
                ) {
                    itemsIndexed(data.filteredApps, key = { _, app -> app.packageName }) { index, app ->
                        AppListItemMaterial(
                            app = app,
                            drawable = data.icons[app.packageName],
                            isRedirected = redirectedSet.contains(app.packageName),
                            isSelected = data.selectedApps.any { it.packageName == app.packageName },
                            shapes = defaultSingleSegmentedShape(index, data.filteredApps.size),
                            onClick = { actions.onAppClick(app) },
                            onLongClick = { actions.onAppLongClick(app) },
                        )
                    }
                }
            }
        }
    }

    AppMaterialDialogs(
        templates = data.templates,
        selectedTemplate = data.selectedTemplate,
        sharedPackages = data.sharedPackages,
        selectedBatchCount = data.selectedBatchCount,
        showTemplatePicker = dialogs.showTemplatePicker,
        showApplyModeDialog = dialogs.showApplyModeDialog,
        showSharedUidConfirmDialog = dialogs.showSharedUidConfirmDialog,
        showRestoreRulesDialog = dialogs.showRestoreRulesDialog,
        onTemplateSelected = actions.onTemplateSelected,
        onApplyModeSelected = actions.onApplyModeSelected,
        onConfirmSharedApply = actions.onConfirmSharedApply,
        onConfirmRestoreRules = actions.onConfirmRestoreRules,
        onDismissTemplatePicker = actions.onDismissTemplatePicker,
        onDismissApplyMode = actions.onDismissApplyMode,
        onDismissSharedConfirm = actions.onDismissSharedConfirm,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppListItemMaterial(
    app: AppInfo,
    drawable: Drawable?,
    isRedirected: Boolean,
    isSelected: Boolean,
    shapes: ListItemShapes,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val baseColors = defaultSegmentedColors()
    val itemColors = if (isSelected) {
        baseColors.copy(
            containerColor = colorScheme.primaryContainer,
            disabledContainerColor = colorScheme.primaryContainer,
        )
    } else {
        baseColors
    }
    val showTags = isRedirected || app.isSystemApp

    CompositionLocalProvider(LocalListItemShapes provides shapes) {
        SegmentedListItem(
            modifier = Modifier.testTag("app_item_${app.packageName}"),
            onClick = onClick,
            onLongClick = onLongClick,
            colors = itemColors,
            leadingContent = {
                AppIcon(
                    drawable = drawable,
                    modifier = Modifier.size(APPS_M_ICON_SIZE),
                    placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
            },
            headlineContent = {
                Text(text = app.appName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(text = app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = if (showTags) {
                {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(APPS_M_TAG_GAP),
                    ) {
                        if (isRedirected) {
                            MaterialStatusTag(
                                label = stringResource(R.string.apps_tag_redirected),
                                backgroundColor = colorScheme.primary,
                                contentColor = colorScheme.onPrimary,
                            )
                        }
                        if (app.isSystemApp) {
                            MaterialStatusTag(
                                label = stringResource(R.string.apps_system),
                                backgroundColor = colorScheme.secondaryContainer,
                                contentColor = colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun MaterialStatusTag(label: String, backgroundColor: Color, contentColor: Color) {
    Box(modifier = Modifier.background(color = backgroundColor, shape = RoundedCornerShape(4.dp))) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NormalTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    systemUsers: List<SystemUser>,
    currentUserIndex: Int,
    showSystemApps: Boolean,
    redirectedFirst: Boolean,
    isSortByName: Boolean,
    showUserMenu: MutableState<Boolean>,
    showFilterMenu: MutableState<Boolean>,
    onOpenTemplates: () -> Unit,
    onRefresh: () -> Unit,
    onUserSelected: (Int) -> Unit,
    onSortByName: () -> Unit,
    onSortByInstallTime: () -> Unit,
    onToggleSystemApps: () -> Unit,
    onToggleRedirectedFirst: () -> Unit,
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.apps_title)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        scrollBehavior = scrollBehavior,
        actions = {
            IconButton(onClick = onOpenTemplates, modifier = Modifier.testTag("apps_templates_button")) {
                Icon(Icons.Rounded.Apps, contentDescription = stringResource(R.string.apps_templates))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.apps_refresh))
            }
            Box {
                IconButton(onClick = { showUserMenu.value = true }) {
                    Icon(Icons.Rounded.People, contentDescription = stringResource(R.string.apps_switch_user))
                }
                DropdownMenu(expanded = showUserMenu.value, onDismissRequest = { showUserMenu.value = false }) {
                    systemUsers.forEachIndexed { index, user ->
                        DropdownMenuItem(
                            text = { Text("${user.name} (${user.id})") },
                            trailingIcon = { if (index == currentUserIndex) Icon(Icons.Rounded.Check, null) },
                            onClick = { onUserSelected(index) },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { showFilterMenu.value = true }) {
                    Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.apps_filter))
                }
                DropdownMenu(expanded = showFilterMenu.value, onDismissRequest = { showFilterMenu.value = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.apps_sort_by_name)) },
                        trailingIcon = { if (isSortByName) Icon(Icons.Rounded.Check, null) },
                        onClick = onSortByName,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.apps_sort_by_install_time)) },
                        trailingIcon = { if (!isSortByName) Icon(Icons.Rounded.Check, null) },
                        onClick = onSortByInstallTime,
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.apps_show_system_apps)) },
                        trailingIcon = { Checkbox(checked = showSystemApps, onCheckedChange = null) },
                        onClick = onToggleSystemApps,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.apps_redirected_first)) },
                        trailingIcon = { Checkbox(checked = redirectedFirst, onCheckedChange = null) },
                        onClick = onToggleRedirectedFirst,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchTopBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onRestore: () -> Unit,
    onApplyTemplate: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.apps_selected_count, selectedCount)) },
        navigationIcon = {
            IconButton(onClick = onCancel, modifier = Modifier.testTag("apps_multi_select_cancel")) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_cancel))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll, modifier = Modifier.testTag("apps_multi_select_select_all")) {
                Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.apps_select_all))
            }
            IconButton(onClick = onInvert, modifier = Modifier.testTag("apps_multi_select_invert")) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = stringResource(R.string.apps_invert_selection))
            }
            IconButton(onClick = onRestore, modifier = Modifier.testTag("apps_multi_select_restore")) {
                Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = stringResource(R.string.apps_restore_rules))
            }
            IconButton(onClick = onApplyTemplate, modifier = Modifier.testTag("apps_multi_select_apply_template")) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.apps_apply_template))
            }
        },
    )
}

@Composable
private fun AppMaterialDialogs(
    templates: List<RedirectTemplate>,
    selectedTemplate: RedirectTemplate?,
    sharedPackages: List<String>,
    selectedBatchCount: Int,
    showTemplatePicker: MutableState<Boolean>,
    showApplyModeDialog: MutableState<Boolean>,
    showSharedUidConfirmDialog: MutableState<Boolean>,
    showRestoreRulesDialog: MutableState<Boolean>,
    onTemplateSelected: (RedirectTemplate) -> Unit,
    onApplyModeSelected: (TemplateApplyMode) -> Unit,
    onConfirmSharedApply: () -> Unit,
    onConfirmRestoreRules: () -> Unit,
    onDismissTemplatePicker: () -> Unit,
    onDismissApplyMode: () -> Unit,
    onDismissSharedConfirm: () -> Unit,
) {
    if (showTemplatePicker.value) {
        var pickedId by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = onDismissTemplatePicker,
            title = { Text(stringResource(R.string.apps_apply_template)) },
            text = {
                if (templates.isEmpty()) {
                    Text(stringResource(R.string.apps_template_empty))
                } else {
                    Column {
                        templates.forEach { template ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pickedId = template.id }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = pickedId == template.id, onClick = { pickedId = template.id })
                                Text(template.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pickedId != null,
                    onClick = { templates.firstOrNull { it.id == pickedId }?.let(onTemplateSelected) },
                ) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissTemplatePicker) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showApplyModeDialog.value) {
        val templateName = selectedTemplate?.name.orEmpty()
        val targetName = stringResource(R.string.apps_selected_count, selectedBatchCount)
        AlertDialog(
            onDismissRequest = onDismissApplyMode,
            title = { Text(stringResource(R.string.apps_template_apply_mode_title)) },
            text = { Text(stringResource(R.string.apps_template_apply_mode_summary, templateName, targetName)) },
            confirmButton = {
                TextButton(onClick = { onApplyModeSelected(TemplateApplyMode.Merge) }) {
                    Text(stringResource(R.string.apps_template_apply_merge))
                }
            },
            dismissButton = {
                TextButton(onClick = { onApplyModeSelected(TemplateApplyMode.Replace) }) {
                    Text(stringResource(R.string.apps_template_apply_replace))
                }
            },
        )
    }

    if (showSharedUidConfirmDialog.value) {
        AlertDialog(
            onDismissRequest = onDismissSharedConfirm,
            title = { Text(stringResource(R.string.apps_template_shared_uid_confirm_title)) },
            text = { Text(stringResource(R.string.apps_template_shared_uid_confirm_summary, sharedPackages.joinToString("\n"))) },
            confirmButton = {
                TextButton(onClick = onConfirmSharedApply) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissSharedConfirm) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showRestoreRulesDialog.value) {
        AlertDialog(
            onDismissRequest = { showRestoreRulesDialog.value = false },
            title = { Text(stringResource(R.string.apps_restore_rules_title)) },
            text = { Text(stringResource(R.string.apps_restore_rules_summary)) },
            confirmButton = {
                TextButton(onClick = onConfirmRestoreRules) { Text(stringResource(R.string.apps_template_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreRulesDialog.value = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
