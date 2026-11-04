package com.storage.redirect.x.ui.page

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.icon.AppIcons
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.model.StoragePathMapping
import com.storage.redirect.x.data.repository.TemplateRepository
import com.storage.redirect.x.ui.component.PageMessageState
import com.storage.redirect.x.ui.component.PillButton
import com.storage.redirect.x.ui.component.PAGE_CONTENT_TOP_PADDING
import com.storage.redirect.x.ui.component.PAGE_GROUP_GAP
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PAGE_ITEM_GAP
import com.storage.redirect.x.ui.component.SrxBackButton
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.navigation.PageStackHost
import com.storage.redirect.x.ui.navigation.rememberPageStack
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import com.storage.redirect.x.ui.component.SrxIconButton as IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val TEMPLATE_CARD_HORIZONTAL_PADDING = 16.dp
private val TEMPLATE_CARD_VERTICAL_PADDING = 14.dp
private val TEMPLATE_DIALOG_FIELD_SPACING = 8.dp
private val TEMPLATE_DIALOG_BOTTOM_SPACING = 16.dp
private val TEMPLATE_DIALOG_BUTTON_SPACING = 12.dp

@Composable
fun TemplatesPage(onBack: () -> Unit = {}) {
    val templateRepo = remember { TemplateRepository() }
    var templates by remember { mutableStateOf(templateRepo.loadTemplates()) }
    // 路由为模板 id
    val editorStack = rememberPageStack<String>()
    val showNameDialog = remember { mutableStateOf(false) }
    var nameDialogInput by remember { mutableStateOf("") }
    var nameDialogError by remember { mutableStateOf(false) }

    fun refresh(newTemplates: List<RedirectTemplate> = templateRepo.loadTemplates()) {
        templates = newTemplates
    }

    PageStackHost(
        stack = editorStack,
        page = { templateId, onEditorBack ->
            // 只取打开时的内容，编辑页自行维护后续修改；删除后的退出转场仍有内容可画
            val template = remember(templateId) { templates.firstOrNull { it.id == templateId } }
            template?.let {
                TemplateEditorPage(
                    template = it,
                    onTemplateChange = { updated -> refresh(templateRepo.saveTemplate(updated)) },
                    onDelete = {
                        refresh(templateRepo.deleteTemplate(templateId))
                        onEditorBack()
                    },
                    onBack = onEditorBack,
                )
            }
        },
    ) {
        TemplateListContent(
            templates = templates,
            onBack = onBack,
            onAdd = {
                nameDialogInput = ""
                nameDialogError = false
                showNameDialog.value = true
            },
            onOpen = { template -> editorStack.push(template.id) },
        )
    }

    WindowDialog(
        show = showNameDialog.value,
        title = stringResource(R.string.templates_add),
        onDismissRequest = { showNameDialog.value = false },
    ) {
        TextField(
            value = nameDialogInput,
            onValueChange = {
                nameDialogInput = it
                nameDialogError = false
            },
            label = stringResource(R.string.templates_name),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("templates_name_input"),
        )
        if (nameDialogError) {
            Spacer(Modifier.height(TEMPLATE_DIALOG_FIELD_SPACING))
            Text(
                text = stringResource(R.string.templates_name_empty),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.height(TEMPLATE_DIALOG_BOTTOM_SPACING))
        TemplateDialogButtonRow(
            show = showNameDialog,
            confirmTag = "templates_name_confirm",
            cancelTag = "templates_name_cancel",
            onConfirm = {
                val name = nameDialogInput.trim()
                if (name.isEmpty()) {
                    nameDialogError = true
                    return@TemplateDialogButtonRow false
                }
                val template = RedirectTemplate(name = name).normalized()
                val saved = templateRepo.saveTemplate(template)
                refresh(saved)
                editorStack.push(template.id)
                true
            },
        )
    }
}

@Composable
private fun TemplateListContent(
    templates: List<RedirectTemplate>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (RedirectTemplate) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    SrxScaffold(
        modifier = Modifier.testTag("page_templates"),
        topBar = {
            SrxTopAppBar(
                title = stringResource(R.string.templates_title),
                navigationIcon = {
                    SrxBackButton(onClick = onBack, modifier = Modifier.testTag("templates_back_button"))
                },
                actions = {
                    IconButton(
                        onClick = onAdd,
                        modifier = Modifier.testTag("templates_add_button"),
                    ) {
                        Icon(
                            AppIcons.Add,
                            contentDescription = stringResource(R.string.templates_add),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (templates.isEmpty()) {
            PageMessageState(
                icon = AppIcons.Layers,
                title = stringResource(R.string.templates_empty),
                message = stringResource(R.string.empty_templates_summary),
                contentPadding = padding,
                modifier = Modifier.testTag("templates_empty_state"),
                action = {
                    PillButton(text = stringResource(R.string.templates_add), onClick = onAdd)
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = PAGE_HORIZONTAL_PADDING),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
            ) {
                item { Spacer(Modifier.height(PAGE_CONTENT_TOP_PADDING)) }
                items(templates, key = { it.id }) { template ->
                    TemplateCard(template = template, onClick = { onOpen(template) })
                    Spacer(Modifier.height(PAGE_ITEM_GAP))
                }
                item { Spacer(Modifier.height(PAGE_GROUP_GAP)) }
            }
        }
    }
}

@Composable
private fun TemplateEditorPage(
    template: RedirectTemplate,
    onTemplateChange: (RedirectTemplate) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var currentTemplate by remember(template.id) { mutableStateOf(template.normalized()) }
    val showRenameDialog = remember { mutableStateOf(false) }
    val showTemplateDeleteDialog = remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf(currentTemplate.name) }
    var renameError by remember { mutableStateOf(false) }

    val showPathDialog = remember { mutableStateOf(false) }
    var dialogPathInput by remember { mutableStateOf("") }
    var pathDialogErrorResId by remember { mutableStateOf<Int?>(null) }

    val showMappingDialog = remember { mutableStateOf(false) }
    var dialogMappingRequestPathInput by remember { mutableStateOf("") }
    var dialogMappingFinalPathInput by remember { mutableStateOf("") }
    var mappingDialogErrorResId by remember { mutableStateOf<Int?>(null) }

    val showDeleteDialog = remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TemplateDeleteTarget?>(null) }

    fun updateTemplate(updated: RedirectTemplate) {
        currentTemplate = updated.normalized()
        onTemplateChange(currentTemplate)
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    SrxScaffold(
        modifier = Modifier.testTag("page_template_editor"),
        topBar = {
            SrxTopAppBar(
                title = currentTemplate.name,
                navigationIcon = {
                    SrxBackButton(onClick = onBack, modifier = Modifier.testTag("template_editor_back_button"))
                },
                actions = {
                    IconButton(
                        onClick = {
                            renameInput = currentTemplate.name
                            renameError = false
                            showRenameDialog.value = true
                        },
                        modifier = Modifier.testTag("template_rename_button"),
                    ) {
                        Icon(AppIcons.Edit, contentDescription = stringResource(R.string.common_edit))
                    }
                    IconButton(
                        onClick = { showTemplateDeleteDialog.value = true },
                        modifier = Modifier.testTag("template_delete_button"),
                    ) {
                        Icon(AppIcons.Delete, contentDescription = stringResource(R.string.templates_delete))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = PAGE_HORIZONTAL_PADDING),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            item {
                RuleSectionTitle(
                    title = stringResource(
                        R.string.rule_config_section_count,
                        stringResource(R.string.rule_config_allowed_paths),
                        currentTemplate.allowedRealPaths.size,
                    ),
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    if (currentTemplate.allowedRealPaths.isEmpty()) {
                        RuleEmptyRow(stringResource(R.string.rule_config_no_paths))
                    }
                    currentTemplate.allowedRealPaths.forEachIndexed { index, path ->
                        AllowedPathRow(
                            path = path,
                            onDelete = {
                                deleteTarget = TemplateDeleteTarget.Path(index)
                                showDeleteDialog.value = true
                            },
                            modifier = Modifier.testTag("template_allowed_path_item_$index"),
                        )
                    }
                    CardActionRow {
                        CardActionButton(
                            icon = AppIcons.Add,
                            text = stringResource(R.string.rule_config_add_path),
                            onClick = {
                                dialogPathInput = ""
                                pathDialogErrorResId = null
                                showPathDialog.value = true
                            },
                            modifier = Modifier.testTag("template_add_allowed_path"),
                        )
                    }
                }
            }

            item {
                RuleSectionTitle(
                    title = stringResource(
                        R.string.rule_config_section_count,
                        stringResource(R.string.rule_config_path_mappings),
                        currentTemplate.pathMappings.size,
                    ),
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    if (currentTemplate.pathMappings.isEmpty()) {
                        RuleEmptyRow(stringResource(R.string.rule_config_no_mappings))
                    }
                    currentTemplate.pathMappings.forEachIndexed { index, mapping ->
                        PathMappingRow(
                            requestPath = mapping.requestPath,
                            finalPath = mapping.finalPath,
                            onDelete = {
                                deleteTarget = TemplateDeleteTarget.Mapping(index)
                                showDeleteDialog.value = true
                            },
                            modifier = Modifier.testTag("template_mapping_item_$index"),
                        )
                    }
                    CardActionRow {
                        CardActionButton(
                            icon = AppIcons.Add,
                            text = stringResource(R.string.rule_config_add_mapping),
                            onClick = {
                                dialogMappingRequestPathInput = ""
                                dialogMappingFinalPathInput = ""
                                mappingDialogErrorResId = null
                                showMappingDialog.value = true
                            },
                            modifier = Modifier.testTag("template_add_mapping"),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(PAGE_GROUP_GAP)) }
        }
    }

    WindowDialog(
        show = showRenameDialog.value,
        title = stringResource(R.string.templates_edit),
        onDismissRequest = { showRenameDialog.value = false },
    ) {
        TextField(
            value = renameInput,
            onValueChange = {
                renameInput = it
                renameError = false
            },
            label = stringResource(R.string.templates_name),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("template_rename_input"),
        )
        if (renameError) {
            Spacer(Modifier.height(TEMPLATE_DIALOG_FIELD_SPACING))
            Text(
                text = stringResource(R.string.templates_name_empty),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.height(TEMPLATE_DIALOG_BOTTOM_SPACING))
        TemplateDialogButtonRow(
            show = showRenameDialog,
            confirmTag = "template_rename_confirm",
            cancelTag = "template_rename_cancel",
            onConfirm = {
                val name = renameInput.trim()
                if (name.isEmpty()) {
                    renameError = true
                    return@TemplateDialogButtonRow false
                }
                updateTemplate(currentTemplate.copy(name = name))
                true
            },
        )
    }

    WindowDialog(
        show = showPathDialog.value,
        title = stringResource(R.string.rule_config_add_path),
        onDismissRequest = {
            pathDialogErrorResId = null
            showPathDialog.value = false
        },
    ) {
        RulePathField(
            value = dialogPathInput,
            onValueChange = {
                dialogPathInput = it
                pathDialogErrorResId = null
            },
            label = stringResource(R.string.rule_config_real_path),
            modifier = Modifier.testTag("template_allowed_path_input"),
        )
        pathDialogErrorResId?.let { errorResId ->
            Spacer(Modifier.height(TEMPLATE_DIALOG_FIELD_SPACING))
            Text(
                text = stringResource(errorResId),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.height(TEMPLATE_DIALOG_BOTTOM_SPACING))
        TemplateDialogButtonRow(
            show = showPathDialog,
            confirmTag = "template_allowed_path_confirm",
            cancelTag = "template_allowed_path_cancel",
            onConfirm = {
                val validation = RedirectConfig.validateRealPathRule(dialogPathInput)
                if (validation !is PathValidationResult.Valid) {
                    pathDialogErrorResId = resolvePathValidationErrorResId(validation)
                    return@TemplateDialogButtonRow false
                }
                // 追加到末尾，与同一路径的其他规则去重时以本次添加为准
                val paths = currentTemplate.allowedRealPaths + validation.normalized
                updateTemplate(currentTemplate.copy(allowedRealPaths = paths))
                true
            },
        )
    }

    WindowDialog(
        show = showMappingDialog.value,
        title = stringResource(R.string.rule_config_add_mapping),
        summary = stringResource(R.string.rule_config_mapping_hint),
        onDismissRequest = {
            mappingDialogErrorResId = null
            showMappingDialog.value = false
        },
    ) {
        RulePathField(
            value = dialogMappingRequestPathInput,
            onValueChange = {
                dialogMappingRequestPathInput = it
                mappingDialogErrorResId = null
            },
            label = stringResource(R.string.rule_config_mapping_request_path),
            modifier = Modifier.testTag("template_mapping_request_input"),
        )
        MappingDirectionArrow()
        RulePathField(
            value = dialogMappingFinalPathInput,
            onValueChange = {
                dialogMappingFinalPathInput = it
                mappingDialogErrorResId = null
            },
            label = stringResource(R.string.rule_config_mapping_final_path),
            modifier = Modifier.testTag("template_mapping_final_input"),
        )
        mappingDialogErrorResId?.let { errorResId ->
            Spacer(Modifier.height(TEMPLATE_DIALOG_FIELD_SPACING))
            Text(
                text = stringResource(errorResId),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.height(TEMPLATE_DIALOG_BOTTOM_SPACING))
        TemplateDialogButtonRow(
            show = showMappingDialog,
            confirmTag = "template_mapping_confirm",
            cancelTag = "template_mapping_cancel",
            onConfirm = {
                val requestValidation = RedirectConfig.validateMappingPath(dialogMappingRequestPathInput)
                if (requestValidation !is PathValidationResult.Valid) {
                    mappingDialogErrorResId = resolvePathValidationErrorResId(requestValidation)
                    return@TemplateDialogButtonRow false
                }
                val finalValidation = RedirectConfig.validateMappingPath(dialogMappingFinalPathInput)
                if (finalValidation !is PathValidationResult.Valid) {
                    mappingDialogErrorResId = resolvePathValidationErrorResId(finalValidation)
                    return@TemplateDialogButtonRow false
                }
                if (requestValidation.normalized == finalValidation.normalized) {
                    mappingDialogErrorResId = R.string.rule_config_error_same_paths
                    return@TemplateDialogButtonRow false
                }

                val mapping = StoragePathMapping(requestValidation.normalized, finalValidation.normalized)
                updateTemplate(currentTemplate.copy(pathMappings = currentTemplate.pathMappings + mapping))
                true
            },
        )
    }

    val isDeletePath = deleteTarget is TemplateDeleteTarget.Path
    WindowDialog(
        show = showDeleteDialog.value,
        title = stringResource(if (isDeletePath) R.string.rule_config_delete_path else R.string.rule_config_delete_mapping),
        summary = stringResource(
            if (isDeletePath) R.string.rule_config_delete_confirm else R.string.rule_config_delete_mapping_confirm,
        ),
        onDismissRequest = { showDeleteDialog.value = false },
    ) {
        TemplateDialogButtonRow(
            show = showDeleteDialog,
            confirmText = stringResource(R.string.common_delete),
            confirmTag = "template_rule_delete_confirm",
            cancelTag = "template_rule_delete_cancel",
            onConfirm = {
                val target = deleteTarget ?: return@TemplateDialogButtonRow true
                when (target) {
                    is TemplateDeleteTarget.Path -> {
                        val paths = currentTemplate.allowedRealPaths.toMutableList()
                        if (target.index in paths.indices) {
                            paths.removeAt(target.index)
                            updateTemplate(currentTemplate.copy(allowedRealPaths = paths))
                        }
                    }
                    is TemplateDeleteTarget.Mapping -> {
                        val mappings = currentTemplate.pathMappings.toMutableList()
                        if (target.index in mappings.indices) {
                            mappings.removeAt(target.index)
                            updateTemplate(currentTemplate.copy(pathMappings = mappings))
                        }
                    }
                }
                true
            },
        )
    }

    WindowDialog(
        show = showTemplateDeleteDialog.value,
        title = stringResource(R.string.templates_delete),
        summary = stringResource(R.string.templates_delete_confirm, currentTemplate.name),
        onDismissRequest = { showTemplateDeleteDialog.value = false },
    ) {
        TemplateDialogButtonRow(
            show = showTemplateDeleteDialog,
            confirmText = stringResource(R.string.common_delete),
            confirmTag = "template_delete_confirm",
            cancelTag = "template_delete_cancel",
            onConfirm = {
                onDelete()
                true
            },
        )
    }
}

// 模板卡片：名称 + 规则数量，点按进入编辑
@Composable
private fun TemplateCard(template: RedirectTemplate, onClick: () -> Unit) {
    val summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("template_item_${template.id}"),
        insideMargin = PaddingValues(horizontal = TEMPLATE_CARD_HORIZONTAL_PADDING, vertical = TEMPLATE_CARD_VERTICAL_PADDING),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.name,
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${stringResource(R.string.rule_config_allowed_paths)} ${template.allowedRealPaths.size} · ${stringResource(R.string.rule_config_path_mappings)} ${template.pathMappings.size}",
                    modifier = Modifier.padding(top = 2.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
                tint = summaryColor,
            )
        }
    }
}

@Composable
private fun TemplateDialogButtonRow(
    show: MutableState<Boolean>,
    confirmText: String = stringResource(R.string.common_ok),
    confirmTag: String = "template_dialog_confirm",
    cancelTag: String = "template_dialog_cancel",
    onConfirm: () -> Boolean,
) {
    Row {
        TextButton(
            text = stringResource(R.string.common_cancel),
            onClick = { show.value = false },
            modifier = Modifier
                .weight(1f)
                .testTag(cancelTag),
        )
        Spacer(Modifier.width(TEMPLATE_DIALOG_BUTTON_SPACING))
        TextButton(
            text = confirmText,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
                val shouldClose = onConfirm()
                if (shouldClose) {
                    show.value = false
                }
            },
            modifier = Modifier
                .weight(1f)
                .testTag(confirmTag),
        )
    }
}

private sealed class TemplateDeleteTarget {
    data class Path(val index: Int) : TemplateDeleteTarget()
    data class Mapping(val index: Int) : TemplateDeleteTarget()
}
