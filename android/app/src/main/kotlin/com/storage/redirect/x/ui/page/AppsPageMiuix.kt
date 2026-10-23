package com.storage.redirect.x.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.repository.TemplateApplyMode
import com.storage.redirect.x.ui.component.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AppsPageMiuix(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
) {
    val redirected = remember(data.redirectedPackages) { data.redirectedPackages.toHashSet() }
    val selected =
        remember(data.selectedApps) { data.selectedApps.map { it.packageName }.toHashSet() }
    val visibleCount =
        data.filteredApps.count { it.packageName in if (data.isBatchMode) selected else redirected }
    val title =
        if (data.isBatchMode)
            "${stringResource(R.string.apps_selected_count, visibleCount)} / ${data.filteredApps.size}"
        else stringResource(R.string.nav_apps)
    SearchScreenScaffold(
        title = title,
        label = stringResource(R.string.apps_search),
        query = data.searchQuery,
        onQueryChange = actions.onSearchChange,
        isResultEmpty = data.filteredApps.isEmpty(),
        bottomPadding = data.bottomInnerPadding,
        modifier = Modifier.testTag("page_apps"),
        listState = data.listState,
        isRefreshing = data.isRefreshing,
        onRefresh = actions.onRefresh,
        navigationIcon = { if (data.isBatchMode) BackButton(actions.onCancelBatch) },
        emptyResult = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.apps_no_apps))
            }
        },
        floatingActionButton = {
            if (data.isBatchMode)
                FloatingActionButton(
                    onClick = actions.onShowTemplatePicker,
                    modifier =
                        Modifier.padding(bottom = data.bottomInnerPadding)
                            .testTag("apps_selection_confirm"),
                ) {
                    Icon(
                        MiuixIcons.Basic.Check,
                        stringResource(R.string.common_ok),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                }
        },
        actions = {
            if (data.isBatchMode) {
                AppFilterMenu(data, dialogs, actions)
                ActionButton(
                    MiuixIcons.SelectAll,
                    stringResource(R.string.apps_select_all),
                    onClick = actions.onSelectAll,
                )
                ActionButton(
                    MiuixIcons.Undo,
                    stringResource(R.string.apps_restore_rules),
                    onClick = actions.onShowRestoreRules,
                )
            } else {
                ActionButton(
                    MiuixIcons.GridView,
                    stringResource(R.string.apps_templates),
                    Modifier.testTag("apps_templates_button"),
                    onClick = actions.onOpenTemplates,
                )
                AppUserMenu(data, dialogs, actions)
                AppFilterMenu(data, dialogs, actions)
            }
        },
    ) {
        item {
            SmallTitle(
                "${stringResource(R.string.apps_title)}  $visibleCount / ${data.filteredApps.size}"
            )
        }
        if (data.isLoading && data.filteredApps.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (data.filteredApps.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.apps_no_apps))
                }
            }
        } else {
            items(data.filteredApps, key = { it.packageName }, contentType = { "app" }) { app ->
                val missingIcon = data.icons[app.packageName] == null
                LaunchedEffect(app.packageName, missingIcon, data.iconGeneration) {
                    if (missingIcon) actions.onRequestIcon(app.packageName)
                }
                Card(
                    modifier =
                        Modifier.animateItem()
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                            .testTag("app_item_${app.packageName}")
                            .semantics {
                                onLongClick {
                                    actions.onAppLongClick(app)
                                    true
                                }
                            },
                    onClick = { actions.onAppClick(app) },
                    onLongPress = { actions.onAppLongClick(app) },
                    showIndication = true,
                ) {
                    BasicComponent(
                        title = app.appName,
                        summary = app.packageName,
                        startAction = {
                            AppIcon(
                                data.icons[app.packageName],
                                Modifier.padding(end = 6.dp).size(40.dp),
                                MiuixTheme.colorScheme.secondaryContainer,
                            )
                        },
                        endActions = {
                            Checkbox(
                                state =
                                    androidx.compose.ui.state.ToggleableState(
                                        if (data.isBatchMode) app.packageName in selected
                                        else app.packageName in redirected
                                    ),
                                onClick = { actions.onAppClick(app) },
                            )
                        },
                    )
                }
            }
        }
    }
    ChoiceDialog(
        show = dialogs.showTemplatePicker.value,
        title = stringResource(R.string.apps_apply_template),
        items = data.templates.map { it.name },
        onDismiss = actions.onDismissTemplatePicker,
        onSelect = { actions.onTemplateSelected(data.templates[it]) },
    )
    ChoiceDialog(
        show = dialogs.showApplyModeDialog.value,
        title = stringResource(R.string.apps_template_apply_mode_title),
        items =
            listOf(
                stringResource(R.string.apps_template_apply_replace),
                stringResource(R.string.apps_template_apply_merge),
            ),
        onDismiss = actions.onDismissApplyMode,
        onSelect = {
            actions.onApplyModeSelected(
                if (it == 0) TemplateApplyMode.Replace else TemplateApplyMode.Merge
            )
        },
    )
    WindowDialog(
        show = dialogs.showSharedUidConfirmDialog.value,
        title = stringResource(R.string.apps_template_shared_uid_confirm_title),
        summary =
            stringResource(
                R.string.apps_template_shared_uid_confirm_summary,
                data.sharedPackages.joinToString("\n"),
            ),
        onDismissRequest = actions.onDismissSharedConfirm,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                stringResource(R.string.common_cancel),
                onClick = actions.onDismissSharedConfirm,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                stringResource(R.string.common_ok),
                onClick = actions.onConfirmSharedApply,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
    RestoreRulesDialog(dialogs = dialogs, actions = actions)
}

@Composable
private fun RestoreRulesDialog(dialogs: AppsPageDialogState, actions: AppsPageActions) {
    WindowDialog(
        show = dialogs.showRestoreRulesDialog.value,
        title = stringResource(R.string.apps_restore_rules_title),
        summary = stringResource(R.string.apps_restore_rules_summary),
        onDismissRequest = { dialogs.showRestoreRulesDialog.value = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                stringResource(R.string.common_cancel),
                onClick = { dialogs.showRestoreRulesDialog.value = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                stringResource(R.string.apps_template_restore),
                onClick = actions.onConfirmRestoreRules,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun AppUserMenu(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
) {
    val current by rememberUpdatedState(actions)
    val names = data.systemUsers.map { "${it.name} (${it.id})" }
    val entry =
        remember(names, data.currentUserIndex) {
            DropdownEntry(
                names.mapIndexed { index, name ->
                    DropdownItem(
                        text = name,
                        selected = index == data.currentUserIndex,
                        onClick = { current.onUserSelected(index) },
                    )
                }
            )
        }
    OverlayIconDropdownMenu(
        entry = entry,
        modifier = Modifier.padding(horizontal = 4.dp).testTag("apps_user_menu"),
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
        onExpandedChange = { dialogs.showUserMenu.value = it },
    ) {
        Icon(
            MiuixIcons.ContactsCircle,
            stringResource(R.string.apps_switch_user),
            Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AppFilterMenu(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
) {
    val current by rememberUpdatedState(actions)
    val labels =
        listOf(
            stringResource(R.string.apps_sort_by_name),
            stringResource(R.string.apps_sort_by_install_time),
            stringResource(R.string.apps_show_system_apps),
            stringResource(R.string.apps_redirected_first),
            stringResource(R.string.apps_invert_selection),
        )
    val entries =
        remember(
            labels,
            data.isSortByName,
            data.showSystemApps,
            data.redirectedFirst,
            data.isBatchMode,
        ) {
            buildList {
                add(
                    DropdownEntry(
                        listOf(
                            DropdownItem(
                                text = labels[0],
                                selected = data.isSortByName,
                                onClick = { current.onSortByName() },
                            ),
                            DropdownItem(
                                text = labels[1],
                                selected = !data.isSortByName,
                                onClick = { current.onSortByInstallTime() },
                            ),
                        )
                    )
                )
                add(
                    DropdownEntry(
                        listOf(
                            DropdownItem(
                                text = labels[2],
                                selected = data.showSystemApps,
                                onClick = { current.onToggleSystemApps() },
                            ),
                            DropdownItem(
                                text = labels[3],
                                selected = data.redirectedFirst,
                                onClick = { current.onToggleRedirectedFirst() },
                            ),
                        )
                    )
                )
                if (data.isBatchMode)
                    add(
                        DropdownEntry(
                            listOf(
                                DropdownItem(
                                    text = labels[4],
                                    onClick = { current.onInvertSelection() },
                                )
                            )
                        )
                    )
            }
        }
    OverlayIconDropdownMenu(
        entries = entries,
        modifier = Modifier.padding(horizontal = 4.dp).testTag("apps_filter_menu"),
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
        collapseOnSelection = true,
        onExpandedChange = { dialogs.showFilterMenu.value = it },
    ) {
        Icon(MiuixIcons.Tune, stringResource(R.string.apps_filter), Modifier.size(20.dp))
    }
}
