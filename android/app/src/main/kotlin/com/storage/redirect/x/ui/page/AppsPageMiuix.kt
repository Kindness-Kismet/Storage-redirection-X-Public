package com.storage.redirect.x.ui.page

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.repository.TemplateApplyMode
import com.storage.redirect.x.ui.component.SRX_TOP_BAR_TRAILING_ICON_END_PADDING
import com.storage.redirect.x.ui.component.SrxSmallTopAppBar
import com.storage.redirect.x.ui.component.SrxTopAppBar
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private val APPS_POPUP_MARGIN = 8.dp
private val APPS_PAGE_TOP_SPACING = 6.dp
private val APPS_ITEM_HORIZONTAL_PADDING = 12.dp
private val APPS_ITEM_BOTTOM_SPACING = 12.dp
private val APPS_ITEM_INSIDE_VERTICAL_PADDING = 8.dp
private val APPS_ITEM_INSIDE_HORIZONTAL_PADDING = 16.dp
private val APPS_ITEM_CONTENT_GAP = 14.dp
private val APPS_ARROW_START_SPACING = 12.dp
private val APPS_SYSTEM_TAG_TOP_SPACING = 4.dp
private val APPS_TAG_CORNER_RADIUS = 6.dp
private val APPS_TAG_HORIZONTAL_PADDING = 4.dp
private val APPS_TAG_VERTICAL_PADDING = 2.dp
private val APPS_SKELETON_TEXT_SPACING = 6.dp
private val APPS_SKELETON_TEXT_CORNER_RADIUS = 4.dp

private val topBarPopupPositionProvider = object : PopupPositionProvider by ListPopupDefaults.DropdownPositionProvider {
    override fun getMargins(): PaddingValues = PaddingValues(
        horizontal = APPS_POPUP_MARGIN,
        vertical = APPS_POPUP_MARGIN,
    )
}

@Composable
internal fun AppsPageMiuix(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
) {
    val redirectedSet = remember(data.redirectedPackages) { data.redirectedPackages.toHashSet() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("page_apps"),
    ) {
        if (data.isBatchMode) {
            BatchTopBarMiuix(data = data, actions = actions)
        } else {
            NormalTopBarMiuix(data = data, dialogs = dialogs, actions = actions)
        }

        Spacer(Modifier.height(APPS_PAGE_TOP_SPACING))

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

        when {
            data.isLoading -> {
                SmallTitle(text = stringResource(R.string.apps_title))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(8) {
                        SkeletonListItem()
                    }
                }
            }

            data.filteredApps.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.apps_no_apps),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            else -> {
                SmallTitle(text = "${stringResource(R.string.apps_title)} (${data.filteredApps.size})")
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = data.listState,
                    contentPadding = PaddingValues(bottom = data.bottomInnerPadding),
                ) {
                    items(data.filteredApps, key = { it.packageName }) { app ->
                        AppListItem(
                            appName = app.appName,
                            packageName = app.packageName,
                            drawable = data.icons[app.packageName],
                            isRedirected = redirectedSet.contains(app.packageName),
                            isSystemApp = app.isSystemApp,
                            isSelected = data.selectedApps.any { it.packageName == app.packageName },
                            modifier = Modifier.testTag("app_item_${app.packageName}"),
                            onClick = { actions.onAppClick(app) },
                            onLongClick = { actions.onAppLongClick(app) },
                        )
                    }
                }
            }
        }
    }

    AppTemplateDialogs(data = data, dialogs = dialogs, actions = actions)
    RestoreRulesDialog(dialogs = dialogs, actions = actions)
}

@Composable
private fun BatchTopBarMiuix(data: AppsPageData, actions: AppsPageActions) {
    SrxSmallTopAppBar(
        title = stringResource(R.string.apps_selected_count, data.selectedBatchCount),
        navigationIcon = {
            IconButton(
                onClick = actions.onCancelBatch,
                modifier = Modifier.testTag("apps_multi_select_cancel"),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_cancel))
            }
        },
        actions = {
            IconButton(
                onClick = actions.onSelectAll,
                modifier = Modifier.testTag("apps_multi_select_select_all"),
            ) {
                Icon(MiuixIcons.SelectAll, contentDescription = stringResource(R.string.apps_select_all))
            }
            IconButton(
                onClick = actions.onInvertSelection,
                modifier = Modifier.testTag("apps_multi_select_invert"),
            ) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = stringResource(R.string.apps_invert_selection))
            }
            IconButton(
                onClick = actions.onShowRestoreRules,
                modifier = Modifier.testTag("apps_multi_select_restore"),
            ) {
                Icon(MiuixIcons.Undo, contentDescription = stringResource(R.string.apps_restore_rules))
            }
            IconButton(
                onClick = actions.onShowTemplatePicker,
                modifier = Modifier
                    .padding(end = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                    .testTag("apps_multi_select_apply_template"),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.apps_apply_template))
            }
        },
    )
}

@Composable
private fun NormalTopBarMiuix(
    data: AppsPageData,
    dialogs: AppsPageDialogState,
    actions: AppsPageActions,
) {
    SrxTopAppBar(
        title = stringResource(R.string.apps_title),
        actions = {
            IconButton(onClick = actions.onOpenTemplates, modifier = Modifier.testTag("apps_templates_button")) {
                Icon(Icons.Rounded.Apps, contentDescription = stringResource(R.string.apps_templates))
            }
            IconButton(onClick = actions.onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.apps_refresh))
            }
            Box {
                IconButton(onClick = { dialogs.showUserMenu.value = true }) {
                    Icon(Icons.Rounded.People, contentDescription = stringResource(R.string.apps_switch_user))
                }
                OverlayListPopup(
                    show = dialogs.showUserMenu.value,
                    popupPositionProvider = topBarPopupPositionProvider,
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
            Box {
                IconButton(
                    onClick = { dialogs.showFilterMenu.value = true },
                    modifier = Modifier.padding(end = SRX_TOP_BAR_TRAILING_ICON_END_PADDING),
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.apps_filter))
                }
                OverlayListPopup(
                    show = dialogs.showFilterMenu.value,
                    popupPositionProvider = topBarPopupPositionProvider,
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
    val applyTargetName = stringResource(R.string.apps_selected_count, data.selectedBatchCount)
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
        summary = stringResource(R.string.apps_template_shared_uid_confirm_summary, data.sharedPackages.joinToString("\n")),
        onDismissRequest = actions.onDismissSharedConfirm,
    ) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("apps_template_shared_uid_dialog"),
        ) {
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
        val pickerSelectedId = remember { mutableStateOf<String?>(null) }
        LaunchedEffect(showTemplatePicker) {
            if (showTemplatePicker) pickerSelectedId.value = null
        }
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("apps_template_picker"),
        ) {
            if (templates.isEmpty()) {
                Text(
                    text = stringResource(R.string.apps_template_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismissTemplatePicker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apps_template_picker_cancel"),
                )
            } else {
                templates.forEach { template ->
                    TemplatePickerOption(
                        name = template.name,
                        isPicked = pickerSelectedId.value == template.id,
                        testTag = "apps_template_picker_item_${template.id}",
                        onClick = { pickerSelectedId.value = template.id },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    TextButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onDismissTemplatePicker,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("apps_template_picker_cancel"),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = stringResource(R.string.common_ok),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        enabled = pickerSelectedId.value != null,
                        onClick = {
                            val target = templates.firstOrNull { it.id == pickerSelectedId.value } ?: return@TextButton
                            onTemplateSelected(target)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("apps_template_picker_confirm"),
                    )
                }
            }
        }
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
private fun TemplatePickerOption(
    name: String,
    isPicked: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (isPicked) Modifier.background(colorScheme.primaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isPicked) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isPicked) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MiuixTheme.textStyles.body1,
            color = colorScheme.onSurface,
            fontWeight = if (isPicked) FontWeight(600) else FontWeight(500),
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun AppListItem(
    appName: String,
    packageName: String,
    drawable: Drawable?,
    isRedirected: Boolean,
    isSystemApp: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme

    Card(
        modifier = modifier
            .padding(horizontal = APPS_ITEM_HORIZONTAL_PADDING)
            .padding(bottom = APPS_ITEM_BOTTOM_SPACING),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
        onLongPress = onLongClick,
        colors = if (isSelected) {
            CardDefaults.defaultColors(color = colorScheme.primaryContainer)
        } else {
            CardDefaults.defaultColors()
        },
        insideMargin = PaddingValues(
            vertical = APPS_ITEM_INSIDE_VERTICAL_PADDING,
            horizontal = APPS_ITEM_INSIDE_HORIZONTAL_PADDING,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AppIcon(
                    drawable = drawable,
                    modifier = Modifier.size(48.dp),
                    placeholderColor = colorScheme.onSurface.copy(alpha = 0.08f),
                )
                if (isRedirected || isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .background(colorScheme.primary, CircleShape)
                            .border(1.5.dp, colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(
                                if (isSelected) R.string.apps_selected else R.string.apps_tag_redirected,
                            ),
                            modifier = Modifier.size(12.dp),
                            tint = colorScheme.onPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(APPS_ITEM_CONTENT_GAP))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight(550),
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    text = packageName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                if (isSystemApp) {
                    Spacer(Modifier.height(APPS_SYSTEM_TAG_TOP_SPACING))
                    StatusTag(
                        label = stringResource(R.string.apps_system),
                        backgroundColor = colorScheme.secondaryContainer,
                        textColor = colorScheme.onSecondaryContainer,
                    )
                }
            }
            Image(
                imageVector = MiuixIcons.ChevronForward,
                modifier = Modifier
                    .padding(start = APPS_ARROW_START_SPACING)
                    .size(width = 10.dp, height = 16.dp),
                colorFilter = ColorFilter.tint(colorScheme.onSurfaceVariantActions),
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun StatusTag(label: String, backgroundColor: Color, textColor: Color) {
    Box(
        modifier = Modifier.background(
            color = backgroundColor,
            shape = RoundedCornerShape(APPS_TAG_CORNER_RADIUS),
        ),
    ) {
        Text(
            modifier = Modifier.padding(
                horizontal = APPS_TAG_HORIZONTAL_PADDING,
                vertical = APPS_TAG_VERTICAL_PADDING,
            ),
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight(750),
            color = textColor,
            maxLines = 1,
        )
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
            .padding(horizontal = APPS_ITEM_HORIZONTAL_PADDING)
            .padding(bottom = APPS_ITEM_BOTTOM_SPACING),
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
                    .size(48.dp)
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
