package com.storage.redirect.x.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.model.StoragePathMapping
import com.storage.redirect.x.data.repository.TemplateRepository
import com.storage.redirect.x.ui.component.SRX_TOP_BAR_TRAILING_ICON_END_PADDING
import com.storage.redirect.x.ui.component.SrxSmallTopAppBar
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val TEMPLATE_PAGE_HORIZONTAL_PADDING = 16.dp
private val TEMPLATE_SECTION_TOP_SPACING = 8.dp
private val TEMPLATE_ITEM_SPACING = 4.dp
private val TEMPLATE_CARD_CONTENT_PADDING = 16.dp
private val TEMPLATE_LIST_ROW_VERTICAL_PADDING = 12.dp
private val TEMPLATE_MAPPING_SUBTITLE_TOP_SPACING = 2.dp
private val TEMPLATE_HINT_SECTION_SPACING = 16.dp
private val TEMPLATE_HEADER_TOP_SPACING = 12.dp
private val TEMPLATE_HEADER_BOTTOM_SPACING = 8.dp
private val TEMPLATE_EMPTY_PLACEHOLDER_PADDING = 24.dp
private val TEMPLATE_DIALOG_FIELD_SPACING = 8.dp
private val TEMPLATE_DIALOG_BOTTOM_SPACING = 16.dp
private val TEMPLATE_DIALOG_BUTTON_SPACING = 12.dp
private val TEMPLATE_INLINE_HINT_TOP_SPACING = 4.dp
private val TEMPLATE_INLINE_HINT_BOTTOM_SPACING = 8.dp

@Composable
fun TemplatesPage(onBack: () -> Unit = {}) {
    val templateRepo = remember { TemplateRepository() }
    var templates by remember { mutableStateOf(templateRepo.loadTemplates()) }
    var editingTemplate by remember { mutableStateOf<RedirectTemplate?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameDialogInput by remember { mutableStateOf("") }
    var nameDialogError by remember { mutableStateOf(false) }
    var deleteTemplate by remember { mutableStateOf<RedirectTemplate?>(null) }

    fun refresh(newTemplates: List<RedirectTemplate> = templateRepo.loadTemplates()) {
        templates = newTemplates
    }

    editingTemplate?.let { template ->
        TemplateEditorPage(
            template = template,
            onTemplateChange = { updated ->
                val saved = templateRepo.saveTemplate(updated)
                refresh(saved)
                editingTemplate = updated.normalized()
            },
            onBack = { editingTemplate = null },
        )
        return
    }

    BackHandler { onBack() }

    Scaffold(
        modifier = Modifier.testTag("page_templates"),
        topBar = {
            SrxSmallTopAppBar(
                title = stringResource(R.string.templates_title),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                            .testTag("templates_back_button"),
                    ) {
                        Icon(MiuixIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            nameDialogInput = ""
                            nameDialogError = false
                            showNameDialog = true
                        },
                        modifier = Modifier
                            .padding(end = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                            .testTag("templates_add_button"),
                    ) {
                        Icon(
                            MiuixIcons.Add,
                            contentDescription = stringResource(R.string.templates_add),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (templates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = TEMPLATE_PAGE_HORIZONTAL_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.templates_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = TEMPLATE_PAGE_HORIZONTAL_PADDING),
            ) {
                item { Spacer(Modifier.height(TEMPLATE_SECTION_TOP_SPACING)) }
                items(templates, key = { it.id }) { template ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("template_item_${template.id}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = TEMPLATE_PAGE_HORIZONTAL_PADDING,
                                    vertical = TEMPLATE_LIST_ROW_VERTICAL_PADDING,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = template.name,
                                    style = MiuixTheme.textStyles.title4,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(TEMPLATE_MAPPING_SUBTITLE_TOP_SPACING))
                                Text(
                                    text = "${stringResource(R.string.rule_config_allowed_paths)} ${template.allowedRealPaths.size} · ${stringResource(R.string.rule_config_path_mappings)} ${template.pathMappings.size}",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { editingTemplate = template },
                                modifier = Modifier.testTag("template_edit_${template.id}"),
                            ) {
                                Icon(
                                    MiuixIcons.Edit,
                                    contentDescription = stringResource(R.string.common_edit),
                                )
                            }
                            IconButton(
                                onClick = { deleteTemplate = template },
                                modifier = Modifier.testTag("template_delete_${template.id}"),
                            ) {
                                Icon(
                                    MiuixIcons.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(TEMPLATE_ITEM_SPACING))
                }
                item { Spacer(Modifier.height(TEMPLATE_HINT_SECTION_SPACING)) }
            }
        }
    }

    WindowDialog(
        show = showNameDialog,
        title = stringResource(R.string.templates_add),
        onDismissRequest = { showNameDialog = false },
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
            show = rememberDialogState(showNameDialog) { showNameDialog = it },
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
                showNameDialog = false
                editingTemplate = template
                false
            },
        )
    }

    deleteTemplate?.let { template ->
        val showDeleteDialog = rememberDialogState(true) { if (!it) deleteTemplate = null }
        WindowDialog(
            show = showDeleteDialog.value,
            title = stringResource(R.string.templates_delete),
            summary = stringResource(R.string.templates_delete_confirm, template.name),
            onDismissRequest = { deleteTemplate = null },
        ) {
            TemplateDialogButtonRow(
                show = showDeleteDialog,
                confirmText = stringResource(R.string.common_delete),
                confirmTag = "template_delete_confirm",
                cancelTag = "template_delete_cancel",
                onConfirm = {
                    val saved = templateRepo.deleteTemplate(template.id)
                    refresh(saved)
                    true
                },
            )
        }
    }
}

@Composable
private fun TemplateEditorPage(
    template: RedirectTemplate,
    onTemplateChange: (RedirectTemplate) -> Unit,
    onBack: () -> Unit,
) {
    var currentTemplate by remember(template.id) { mutableStateOf(template.normalized()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf(currentTemplate.name) }
    var renameError by remember { mutableStateOf(false) }

    val showPathDialog = remember { mutableStateOf(false) }
    var pathDialogIndex by remember { mutableStateOf<Int?>(null) }
    var dialogPathInput by remember { mutableStateOf("") }
    var pathDialogErrorResId by remember { mutableStateOf<Int?>(null) }
    var showAllowedPathHint by remember { mutableStateOf(false) }

    val showMappingDialog = remember { mutableStateOf(false) }
    var mappingDialogIndex by remember { mutableStateOf<Int?>(null) }
    var dialogMappingRequestPathInput by remember { mutableStateOf("") }
    var dialogMappingFinalPathInput by remember { mutableStateOf("") }
    var mappingDialogErrorResId by remember { mutableStateOf<Int?>(null) }

    val showDeleteDialog = remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TemplateDeleteTarget?>(null) }

    fun updateTemplate(updated: RedirectTemplate) {
        currentTemplate = updated.normalized()
        onTemplateChange(currentTemplate)
    }

    BackHandler { onBack() }

    Scaffold(
        modifier = Modifier.testTag("page_template_editor"),
        topBar = {
            SrxSmallTopAppBar(
                title = currentTemplate.name,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                            .testTag("template_editor_back_button"),
                    ) {
                        Icon(MiuixIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            renameInput = currentTemplate.name
                            renameError = false
                            showRenameDialog = true
                        },
                        modifier = Modifier
                            .padding(end = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                            .testTag("template_rename_button"),
                    ) {
                        Icon(MiuixIcons.Edit, contentDescription = stringResource(R.string.common_edit))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TEMPLATE_PAGE_HORIZONTAL_PADDING),
        ) {
            item {
                TemplateSectionHeader(
                    title = "${stringResource(R.string.rule_config_allowed_paths)} (${currentTemplate.allowedRealPaths.size})",
                    onInfo = { showAllowedPathHint = !showAllowedPathHint },
                    onAdd = {
                        pathDialogIndex = null
                        dialogPathInput = ""
                        pathDialogErrorResId = null
                        showPathDialog.value = true
                    },
                    addTag = "template_add_allowed_path",
                    infoTag = "template_allowed_path_info",
                )
            }
            item {
                AnimatedVisibility(
                    visible = showAllowedPathHint,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        Spacer(Modifier.height(TEMPLATE_INLINE_HINT_TOP_SPACING))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.rule_config_allowed_paths_rule_hint),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(TEMPLATE_CARD_CONTENT_PADDING),
                            )
                        }
                        Spacer(Modifier.height(TEMPLATE_INLINE_HINT_BOTTOM_SPACING))
                    }
                }
            }

            if (currentTemplate.allowedRealPaths.isEmpty()) {
                item { TemplateEmptyPlaceholder(stringResource(R.string.rule_config_no_paths)) }
            } else {
                itemsIndexed(currentTemplate.allowedRealPaths) { index, path ->
                    TemplateRuleRow(
                        text = path,
                        index = index,
                        onEdit = {
                            pathDialogIndex = index
                            dialogPathInput = path
                            pathDialogErrorResId = null
                            showPathDialog.value = true
                        },
                        onDelete = {
                            deleteTarget = TemplateDeleteTarget.Path(index)
                            showDeleteDialog.value = true
                        },
                    )
                }
            }

            item {
                TemplateSectionHeader(
                    title = "${stringResource(R.string.rule_config_path_mappings)} (${currentTemplate.pathMappings.size})",
                    onAdd = {
                        mappingDialogIndex = null
                        dialogMappingRequestPathInput = ""
                        dialogMappingFinalPathInput = ""
                        mappingDialogErrorResId = null
                        showMappingDialog.value = true
                    },
                    addTag = "template_add_mapping",
                )
            }

            if (currentTemplate.pathMappings.isEmpty()) {
                item { TemplateEmptyPlaceholder(stringResource(R.string.rule_config_no_mappings)) }
            } else {
                itemsIndexed(currentTemplate.pathMappings) { index, mapping ->
                    TemplateMappingRow(
                        mapping = mapping,
                        index = index,
                        onEdit = {
                            mappingDialogIndex = index
                            dialogMappingRequestPathInput = mapping.requestPath
                            dialogMappingFinalPathInput = mapping.finalPath
                            mappingDialogErrorResId = null
                            showMappingDialog.value = true
                        },
                        onDelete = {
                            deleteTarget = TemplateDeleteTarget.Mapping(index)
                            showDeleteDialog.value = true
                        },
                    )
                }
            }

            item { Spacer(Modifier.height(TEMPLATE_HINT_SECTION_SPACING)) }
        }
    }

    WindowDialog(
        show = showRenameDialog,
        title = stringResource(R.string.templates_edit),
        onDismissRequest = { showRenameDialog = false },
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
            show = rememberDialogState(showRenameDialog) { showRenameDialog = it },
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

    val isEditingPath = pathDialogIndex != null
    WindowDialog(
        show = showPathDialog.value,
        title = stringResource(if (isEditingPath) R.string.rule_config_edit_path else R.string.rule_config_add_path),
        onDismissRequest = {
            pathDialogErrorResId = null
            showPathDialog.value = false
        },
    ) {
        TextField(
            value = dialogPathInput,
            onValueChange = {
                dialogPathInput = it
                pathDialogErrorResId = null
            },
            label = stringResource(R.string.rule_config_real_path),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("template_allowed_path_input"),
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
                val validation = RedirectConfig.validateAllowedPath(dialogPathInput)
                if (validation !is PathValidationResult.Valid) {
                    pathDialogErrorResId = resolveTemplatePathValidationErrorResId(validation)
                    return@TemplateDialogButtonRow false
                }
                val paths = currentTemplate.allowedRealPaths.toMutableList()
                val index = pathDialogIndex
                if (index == null) {
                    paths.add(validation.normalized)
                } else if (index in paths.indices) {
                    paths[index] = validation.normalized
                }
                updateTemplate(currentTemplate.copy(allowedRealPaths = paths))
                true
            },
        )
    }

    val isEditingMapping = mappingDialogIndex != null
    WindowDialog(
        show = showMappingDialog.value,
        title = stringResource(if (isEditingMapping) R.string.rule_config_edit_mapping else R.string.rule_config_add_mapping),
        onDismissRequest = {
            mappingDialogErrorResId = null
            showMappingDialog.value = false
        },
    ) {
        TextField(
            value = dialogMappingRequestPathInput,
            onValueChange = {
                dialogMappingRequestPathInput = it
                mappingDialogErrorResId = null
            },
            label = stringResource(R.string.rule_config_mapping_request_path),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("template_mapping_request_input"),
        )
        Spacer(Modifier.height(TEMPLATE_DIALOG_FIELD_SPACING))
        TextField(
            value = dialogMappingFinalPathInput,
            onValueChange = {
                dialogMappingFinalPathInput = it
                mappingDialogErrorResId = null
            },
            label = stringResource(R.string.rule_config_mapping_final_path),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("template_mapping_final_input"),
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
                    mappingDialogErrorResId = resolveTemplatePathValidationErrorResId(requestValidation)
                    return@TemplateDialogButtonRow false
                }
                val finalValidation = RedirectConfig.validateMappingPath(dialogMappingFinalPathInput)
                if (finalValidation !is PathValidationResult.Valid) {
                    mappingDialogErrorResId = resolveTemplatePathValidationErrorResId(finalValidation)
                    return@TemplateDialogButtonRow false
                }
                if (requestValidation.normalized == finalValidation.normalized) {
                    mappingDialogErrorResId = R.string.rule_config_error_same_paths
                    return@TemplateDialogButtonRow false
                }

                val mappings = currentTemplate.pathMappings.toMutableList()
                val mapping = StoragePathMapping(requestValidation.normalized, finalValidation.normalized)
                val index = mappingDialogIndex
                if (index == null) {
                    mappings.add(mapping)
                } else if (index in mappings.indices) {
                    mappings[index] = mapping
                }
                updateTemplate(currentTemplate.copy(pathMappings = mappings))
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
}

@Composable
private fun TemplateSectionHeader(
    title: String,
    onAdd: () -> Unit,
    onInfo: (() -> Unit)? = null,
    addTag: String? = null,
    infoTag: String? = null,
) {
    Spacer(Modifier.height(TEMPLATE_HEADER_TOP_SPACING))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = TEMPLATE_HEADER_BOTTOM_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            modifier = Modifier.weight(1f),
        )
        onInfo?.let {
            IconButton(
                onClick = it,
                modifier = infoTag?.let { tag -> Modifier.testTag(tag) } ?: Modifier,
            ) {
                Icon(
                    MiuixIcons.Info,
                    contentDescription = stringResource(R.string.rule_config_allowed_paths_rule_hint_desc),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
        IconButton(
            onClick = onAdd,
            modifier = addTag?.let { Modifier.testTag(it) } ?: Modifier,
        ) {
            Icon(
                MiuixIcons.Add,
                contentDescription = stringResource(R.string.common_add),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TemplateRuleRow(text: String, onEdit: () -> Unit, onDelete: () -> Unit, index: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("template_allowed_path_item_$index"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TEMPLATE_PAGE_HORIZONTAL_PADDING,
                    vertical = TEMPLATE_LIST_ROW_VERTICAL_PADDING,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("template_allowed_path_edit_$index"),
            ) {
                Icon(MiuixIcons.Edit, contentDescription = stringResource(R.string.common_edit))
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("template_allowed_path_delete_$index"),
            ) {
                Icon(
                    MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(Modifier.height(TEMPLATE_ITEM_SPACING))
}

@Composable
private fun TemplateMappingRow(mapping: StoragePathMapping, index: Int, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("template_mapping_item_$index"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TEMPLATE_PAGE_HORIZONTAL_PADDING,
                    vertical = TEMPLATE_LIST_ROW_VERTICAL_PADDING,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${stringResource(R.string.rule_config_mapping_request_path)}: ${mapping.requestPath}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(TEMPLATE_MAPPING_SUBTITLE_TOP_SPACING))
                Text(
                    text = "${stringResource(R.string.rule_config_mapping_final_path)}: ${mapping.finalPath}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("template_mapping_edit_$index"),
            ) {
                Icon(MiuixIcons.Edit, contentDescription = stringResource(R.string.common_edit))
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("template_mapping_delete_$index"),
            ) {
                Icon(
                    MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(Modifier.height(TEMPLATE_ITEM_SPACING))
}

@Composable
private fun TemplateEmptyPlaceholder(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TEMPLATE_EMPTY_PLACEHOLDER_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun rememberDialogState(currentValue: Boolean, onChange: (Boolean) -> Unit): MutableState<Boolean> {
    val latestOnChange by rememberUpdatedState(onChange)
    return remember(currentValue) {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = currentValue
                set(newValue) = latestOnChange(newValue)

            override fun component1(): Boolean = currentValue
            override fun component2(): (Boolean) -> Unit = { latestOnChange(it) }
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

private fun resolveTemplatePathValidationErrorResId(result: PathValidationResult): Int {
    return when (result) {
        PathValidationResult.Empty -> R.string.rule_config_error_empty
        PathValidationResult.Absolute -> R.string.rule_config_error_absolute
        PathValidationResult.Traversal -> R.string.rule_config_error_traversal
        PathValidationResult.AndroidDataPath -> R.string.rule_config_error_android_prefix
        PathValidationResult.WildcardNotAllowed -> R.string.rule_config_error_mapping_wildcard
        PathValidationResult.ExclusionNotAllowed -> R.string.rule_config_error_mapping_exclusion
        is PathValidationResult.Valid -> R.string.rule_config_error_empty
    }
}

private sealed class TemplateDeleteTarget {
    data class Path(val index: Int) : TemplateDeleteTarget()
    data class Mapping(val index: Int) : TemplateDeleteTarget()
}
