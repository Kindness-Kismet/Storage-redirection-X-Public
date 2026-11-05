package com.storage.redirect.x.ui.page

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.repository.TemplateApplyMode
import com.storage.redirect.x.ui.component.BottomFloatingBar
import com.storage.redirect.x.ui.component.PAGE_CONTENT_TOP_PADDING
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PAGE_ITEM_GAP
import com.storage.redirect.x.ui.component.PillButton
import com.storage.redirect.x.ui.component.PageMessageState
import com.storage.redirect.x.ui.component.STATUS_WARNING_COLOR
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.component.SrxSmallTopAppBar
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.component.StatusTag
import com.storage.redirect.x.ui.component.TOP_BAR_POPUP_POSITION_PROVIDER
import com.storage.redirect.x.ui.icon.AppIcons
import com.storage.redirect.x.ui.util.topBarSurface
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import com.storage.redirect.x.ui.component.SrxIconButton as IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

private val APPS_ITEM_INSIDE_VERTICAL_PADDING = 10.dp
private val APPS_ITEM_INSIDE_HORIZONTAL_PADDING = 16.dp
private val APPS_ICON_SIZE = 44.dp
private val APPS_ITEM_CONTENT_GAP = 14.dp
private val APPS_TRAILING_START_SPACING = 12.dp
private val APPS_TAG_TOP_SPACING = 4.dp
private val APPS_TAG_GAP = 4.dp
private val APPS_SKELETON_TEXT_SPACING = 6.dp
private val APPS_SKELETON_TEXT_CORNER_RADIUS = 4.dp

@Composable
internal fun AppsPageMiuix(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var batchBarSpace by remember { mutableStateOf(0.dp) }
    // 批量模式底栏已隐藏，列表底部只为批量栏留空
    val listBottomPadding = if (data.isBatchMode) {
        batchBarSpace + PAGE_ITEM_GAP
    } else {
        data.bottomInnerPadding
    }

    // 搜索栏随顶栏固定，列表从两者下方滑过
    SrxScaffold(
        modifier = Modifier.testTag("page_apps"),
        topBar = {
            Column(Modifier.topBarSurface()) {
                if (data.isBatchMode) {
                    BatchTopBarMiuix(data = data, actions = actions)
                } else {
                    NormalTopBarMiuix(data = data, dialogs = dialogs, actions = actions, scrollBehavior = scrollBehavior)
                }
                Spacer(Modifier.height(PAGE_CONTENT_TOP_PADDING))
                SearchBar(
                    inputField = {
                        InputField(
                            query = data.searchQuery,
                            onQueryChange = actions.onSearchChange,
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            label = stringResource(R.string.apps_search),
                        )
                    },
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {}
                Spacer(Modifier.height(10.dp))
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            val topPadding = innerPadding.calculateTopPadding()
            if (data.isLoading) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topPadding),
                    userScrollEnabled = false,
                ) {
                    item { SmallTitle(text = stringResource(R.string.apps_title)) }
                    items(8) {
                        SkeletonListItem()
                    }
                }
            } else {
                AppList(
                    data = data,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    topPadding = topPadding,
                    bottomPadding = listBottomPadding,
                )
            }

            BottomFloatingBar(
                isVisible = data.isBatchMode,
                onSpaceChange = { batchBarSpace = it },
            ) {
                PillButton(
                    text = stringResource(R.string.apps_restore_rules),
                    onClick = actions.onShowRestoreRules,
                    modifier = Modifier.testTag("apps_multi_select_restore"),
                )
                PillButton(
                    text = stringResource(R.string.apps_batch_apply_template),
                    onClick = actions.onShowTemplatePicker,
                    modifier = Modifier.testTag("apps_multi_select_apply_template"),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    AppTemplateDialogs(data = data, dialogs = dialogs, actions = actions)
    RestoreRulesDialog(dialogs = dialogs, actions = actions)
}

// 空列表也放进 LazyColumn，保证无结果时仍可下拉刷新
@Composable
private fun AppList(
    data: AppsPageData,
    actions: AppsPageActions,
    scrollBehavior: ScrollBehavior,
    topPadding: Dp,
    bottomPadding: Dp,
) {
    val redirectedSet = remember(data.redirectedPackages) { data.redirectedPackages.toHashSet() }

    PullToRefresh(
        isRefreshing = data.isRefreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier.fillMaxSize(),
        // 刷新指示器从顶栏下方出现
        contentPadding = PaddingValues(top = topPadding),
        topAppBarScrollBehavior = scrollBehavior,
        refreshTexts = listOf(
            stringResource(R.string.common_pull_to_refresh),
            stringResource(R.string.common_release_to_refresh),
            stringResource(R.string.common_refreshing),
            stringResource(R.string.common_refresh_complete),
        ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().overScrollVertical(),
            overscrollEffect = null,
            state = data.listState,
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        ) {
            if (data.filteredApps.isEmpty()) {
                item(key = "empty") {
                    val hasQuery = data.searchQuery.isNotBlank()
                    PageMessageState(
                        icon = if (hasQuery) AppIcons.Search else AppIcons.Grid,
                        title = stringResource(if (hasQuery) R.string.apps_no_match else R.string.apps_no_apps),
                        message = stringResource(if (hasQuery) R.string.apps_no_match_summary else R.string.empty_apps_summary),
                        modifier = Modifier.fillParentMaxSize().testTag("apps_empty_state"),
                        action = {
                            PillButton(
                                text = stringResource(if (hasQuery) R.string.browser_clear_search else R.string.apps_refresh),
                                onClick = { if (hasQuery) actions.onSearchChange("") else actions.onRefresh() },
                            )
                        },
                    )
                }
                return@LazyColumn
            }
            item(key = "title") {
                SmallTitle(text = stringResource(R.string.apps_list_count, data.filteredApps.size))
            }
            items(data.filteredApps, key = { it.packageName }) { app ->
                AppListItem(
                    appName = app.appName,
                    packageName = app.packageName,
                    drawable = data.icons[app.packageName],
                    isRedirected = redirectedSet.contains(app.packageName),
                    isSystemApp = app.isSystemApp,
                    isSharedUid = app.packageName in data.sharedUidPackages,
                    isBatchMode = data.isBatchMode,
                    isSelected = data.selectedApps.any { it.packageName == app.packageName },
                    modifier = Modifier.testTag("app_item_${app.packageName}"),
                    onClick = { actions.onAppClick(app) },
                    onLongClick = { actions.onAppLongClick(app) },
                )
            }
        }
    }
}

@Composable
private fun BatchTopBarMiuix(data: AppsPageData, actions: AppsPageActions) {
    SrxSmallTopAppBar(
        title = pluralStringResource(R.plurals.apps_selected_count, data.selectedBatchCount, data.selectedBatchCount),
        applyBlur = false,
        navigationIcon = {
            IconButton(
                onClick = actions.onCancelBatch,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .testTag("apps_multi_select_cancel"),
            ) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.common_cancel))
            }
        },
        actions = {
            IconButton(
                onClick = actions.onSelectAll,
                modifier = Modifier.testTag("apps_multi_select_select_all"),
            ) {
                Icon(AppIcons.SelectAll, contentDescription = stringResource(R.string.apps_select_all))
            }
            IconButton(
                onClick = actions.onInvertSelection,
                modifier = Modifier.testTag("apps_multi_select_invert"),
            ) {
                Icon(AppIcons.Swap, contentDescription = stringResource(R.string.apps_invert_selection))
            }
        },
    )
}

@Composable
private fun NormalTopBarMiuix(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
    scrollBehavior: ScrollBehavior,
) {
    SrxTopAppBar(
        title = stringResource(R.string.apps_title),
        scrollBehavior = scrollBehavior,
        applyBlur = false,
        actions = {
            IconButton(onClick = actions.onOpenTemplates, modifier = Modifier.testTag("apps_templates_button")) {
                Icon(AppIcons.Layers, contentDescription = stringResource(R.string.apps_templates))
            }
            // 单用户设备无需切换入口
            if (data.systemUsers.size > 1) {
                Box {
                    IconButton(onClick = { dialogs.showUserMenu.value = true }) {
                        Icon(AppIcons.Group, contentDescription = stringResource(R.string.apps_switch_user))
                    }
                    OverlayListPopup(
                        show = dialogs.showUserMenu.value,
                        popupPositionProvider = TOP_BAR_POPUP_POSITION_PROVIDER,
                        alignment = PopupPositionProvider.Align.TopEnd,
                        onDismissRequest = { dialogs.showUserMenu.value = false },
                    ) {
                        ListPopupColumn {
                            data.systemUsers.forEachIndexed { index, user ->
                                DropdownImpl(
                                    text = "${user.name} (${user.id})",
                                    optionSize = data.systemUsers.size,
                                    isSelected = index == data.currentUserIndex,
                                    index = index,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = actions.onUserSelected,
                                )
                            }
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { dialogs.showFilterMenu.value = true }) {
                    Icon(AppIcons.More, contentDescription = stringResource(R.string.apps_filter))
                }
                OverlayListPopup(
                    show = dialogs.showFilterMenu.value,
                    popupPositionProvider = TOP_BAR_POPUP_POSITION_PROVIDER,
                    alignment = PopupPositionProvider.Align.TopEnd,
                    onDismissRequest = { dialogs.showFilterMenu.value = false },
                ) {
                    ListPopupColumn {
                        DropdownImpl(
                            text = stringResource(R.string.apps_sort_by_name),
                            optionSize = 4,
                            isSelected = data.isSortByName,
                            index = 0,
                            dropdownColors = DropdownDefaults.dropdownColors(),
                            onSelectedIndexChange = { actions.onSortByName() },
                        )
                        DropdownImpl(
                            text = stringResource(R.string.apps_sort_by_install_time),
                            optionSize = 4,
                            isSelected = !data.isSortByName,
                            index = 1,
                            dropdownColors = DropdownDefaults.dropdownColors(),
                            onSelectedIndexChange = { actions.onSortByInstallTime() },
                        )
                        DropdownImpl(
                            text = stringResource(R.string.apps_show_system_apps),
                            optionSize = 4,
                            isSelected = data.showSystemApps,
                            index = 2,
                            dropdownColors = DropdownDefaults.dropdownColors(),
                            onSelectedIndexChange = { actions.onToggleSystemApps() },
                        )
                        DropdownImpl(
                            text = stringResource(R.string.apps_redirected_first),
                            optionSize = 4,
                            isSelected = data.redirectedFirst,
                            index = 3,
                            dropdownColors = DropdownDefaults.dropdownColors(),
                            onSelectedIndexChange = { actions.onToggleRedirectedFirst() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun AppTemplateDialogs(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
) {
    TemplatePickerDialog(
        templates = data.templates,
        showTemplatePicker = dialogs.showTemplatePicker.value,
        onTemplateSelected = actions.onTemplateSelected,
        onDismissTemplatePicker = actions.onDismissTemplatePicker,
    )

    val templateName = data.selectedTemplate?.name.orEmpty()
    val applyTargetName = pluralStringResource(R.plurals.apps_selected_count, data.selectedBatchCount, data.selectedBatchCount)
    WindowDialog(
        show = dialogs.showApplyModeDialog.value,
        title = stringResource(R.string.apps_template_apply_mode_title),
        summary = stringResource(R.string.apps_template_apply_mode_summary, templateName, applyTargetName),
        onDismissRequest = actions.onDismissApplyMode,
    ) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("apps_template_apply_mode_dialog"),
        ) {
            Row {
                TextButton(
                    text = stringResource(R.string.apps_template_apply_replace),
                    onClick = { actions.onApplyModeSelected(TemplateApplyMode.Replace) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("apps_template_apply_replace"),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.apps_template_apply_merge),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { actions.onApplyModeSelected(TemplateApplyMode.Merge) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("apps_template_apply_merge"),
                )
            }
        }
    }

    WindowDialog(
        show = dialogs.showSharedUidConfirmDialog.value,
        title = stringResource(R.string.apps_template_shared_uid_confirm_title),
        summary = stringResource(R.string.apps_template_shared_uid_confirm_summary),
        onDismissRequest = actions.onDismissSharedConfirm,
    ) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("apps_template_shared_uid_dialog"),
        ) {
            // 包名多时对话框超高会把按钮挤出屏幕，列表限制高度可滚动
            LazyColumn(
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .heightIn(max = 240.dp),
            ) {
                items(data.sharedPackages) { pkg ->
                    Text(
                        text = pkg,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Row {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = actions.onDismissSharedConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("apps_template_shared_uid_cancel"),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.common_ok),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = actions.onConfirmSharedApply,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("apps_template_shared_uid_ok"),
                )
            }
        }
    }
}

@Composable
private fun TemplatePickerDialog(
    templates: List<RedirectTemplate>,
    showTemplatePicker: Boolean,
    onTemplateSelected: (RedirectTemplate) -> Unit,
    onDismissTemplatePicker: () -> Unit,
) {
    WindowDialog(
        show = showTemplatePicker,
        title = stringResource(R.string.apps_apply_template),
        onDismissRequest = onDismissTemplatePicker,
    ) {
        TemplatePickerContent(
            templates = templates,
            testTag = "apps_template_picker",
            onPick = onTemplateSelected,
            onCancel = onDismissTemplatePicker,
        )
    }
}

@Composable
private fun RestoreRulesDialog(dialogs: AppsPageDialogState, actions: AppsPageActions) {
    WindowDialog(
        show = dialogs.showRestoreRulesDialog.value,
        title = stringResource(R.string.apps_restore_rules_title),
        summary = stringResource(R.string.apps_restore_rules_summary),
        onDismissRequest = { dialogs.showRestoreRulesDialog.value = false },
    ) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("apps_restore_rules_dialog"),
        ) {
            Row {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { dialogs.showRestoreRulesDialog.value = false },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("apps_restore_rules_cancel"),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.apps_template_restore),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = actions.onConfirmRestoreRules,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("apps_restore_rules_confirm"),
                )
            }
        }
    }
}

@Composable
private fun AppListItem(
    appName: String,
    packageName: String,
    drawable: Drawable?,
    isRedirected: Boolean,
    isSystemApp: Boolean,
    isSharedUid: Boolean,
    isBatchMode: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme

    Card(
        modifier = modifier
            .padding(horizontal = PAGE_HORIZONTAL_PADDING)
            .padding(bottom = PAGE_ITEM_GAP),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
        onLongPress = onLongClick,
        colors = if (isSelected) {
            CardDefaults.defaultColors(color = colorScheme.tertiaryContainer)
        } else {
            CardDefaults.defaultColors()
        },
        insideMargin = PaddingValues(
            vertical = APPS_ITEM_INSIDE_VERTICAL_PADDING,
            horizontal = APPS_ITEM_INSIDE_HORIZONTAL_PADDING,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                drawable = drawable,
                modifier = Modifier.size(APPS_ICON_SIZE),
                placeholderColor = colorScheme.onSurface.copy(alpha = 0.08f),
            )
            Spacer(Modifier.width(APPS_ITEM_CONTENT_GAP))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    fontSize = 17.sp,
                    fontWeight = FontWeight(550),
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    text = packageName,
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                if (isRedirected || isSharedUid || isSystemApp) {
                    Spacer(Modifier.height(APPS_TAG_TOP_SPACING))
                    Row(horizontalArrangement = Arrangement.spacedBy(APPS_TAG_GAP)) {
                        if (isRedirected) {
                            StatusTag(label = stringResource(R.string.apps_tag_redirected), color = colorScheme.primary)
                        }
                        if (isSharedUid) {
                            StatusTag(label = stringResource(R.string.apps_tag_shared_uid), color = STATUS_WARNING_COLOR)
                        }
                        if (isSystemApp) {
                            StatusTag(label = stringResource(R.string.apps_system), color = colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
            // 批量模式由整张卡片响应点击，勾选框只展示状态
            if (isBatchMode) {
                Checkbox(
                    state = ToggleableState(isSelected),
                    onClick = null,
                    modifier = Modifier.padding(start = APPS_TRAILING_START_SPACING),
                )
            } else {
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    modifier = Modifier
                        .padding(start = APPS_TRAILING_START_SPACING)
                        .size(16.dp),
                    tint = colorScheme.onSurfaceVariantActions,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun SkeletonListItem() {
    val shimmerAlpha by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0.08f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    val color = MiuixTheme.colorScheme.onSurface.copy(alpha = shimmerAlpha)

    Card(
        modifier = Modifier
            .padding(horizontal = PAGE_HORIZONTAL_PADDING)
            .padding(bottom = PAGE_ITEM_GAP),
        insideMargin = PaddingValues(
            vertical = APPS_ITEM_INSIDE_VERTICAL_PADDING,
            horizontal = APPS_ITEM_INSIDE_HORIZONTAL_PADDING,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(APPS_ICON_SIZE)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(APPS_ITEM_CONTENT_GAP))
            Column {
                Box(
                    modifier = Modifier
                        .height(14.dp)
                        .width(120.dp)
                        .clip(RoundedCornerShape(APPS_SKELETON_TEXT_CORNER_RADIUS))
                        .background(color),
                )
                Spacer(Modifier.height(APPS_SKELETON_TEXT_SPACING))
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .width(200.dp)
                        .clip(RoundedCornerShape(APPS_SKELETON_TEXT_CORNER_RADIUS))
                        .background(color),
                )
            }
        }
    }
}
