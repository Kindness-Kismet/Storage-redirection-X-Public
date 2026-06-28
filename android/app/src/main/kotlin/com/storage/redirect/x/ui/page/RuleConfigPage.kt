package com.storage.redirect.x.ui.page

import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.StoragePathMapping
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.PathBrowserRepository
import com.storage.redirect.x.data.repository.SharedUidRepository
import com.storage.redirect.x.data.repository.TemplateRepository
import com.storage.redirect.x.ui.component.SRX_TOP_BAR_TRAILING_ICON_END_PADDING
import com.storage.redirect.x.ui.component.SrxSmallTopAppBar
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面间距：列表横向边距，区块间距，行内边距，弹窗字段间距与提示卡片间距
private val RULE_CONFIG_PAGE_HORIZONTAL_PADDING = 16.dp
private val RULE_CONFIG_SECTION_TOP_SPACING = 8.dp
private val RULE_CONFIG_ITEM_SPACING = 4.dp
private val RULE_CONFIG_CARD_CONTENT_PADDING = 16.dp
private val RULE_CONFIG_LIST_ROW_VERTICAL_PADDING = 12.dp
private val RULE_CONFIG_MAPPING_SUBTITLE_TOP_SPACING = 2.dp
private val RULE_CONFIG_HINT_SECTION_SPACING = 16.dp
private val RULE_CONFIG_HEADER_TOP_SPACING = 12.dp
private val RULE_CONFIG_HEADER_BOTTOM_SPACING = 8.dp
private val RULE_CONFIG_EMPTY_PLACEHOLDER_PADDING = 24.dp
private val RULE_CONFIG_DIALOG_FIELD_SPACING = 8.dp
private val RULE_CONFIG_DIALOG_BOTTOM_SPACING = 16.dp
private val RULE_CONFIG_DIALOG_BUTTON_SPACING = 12.dp
private val RULE_CONFIG_INLINE_HINT_TOP_SPACING = 4.dp
private val RULE_CONFIG_INLINE_HINT_BOTTOM_SPACING = 8.dp

@Composable
fun RuleConfigPage(packageName: String, appName: String, userId: Int = 0, onBack: (modified: Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val configRepo = remember { ConfigRepository() }
    val sharedUidRepo = remember { SharedUidRepository() }
    val pathBrowserRepo = remember { PathBrowserRepository() }
    val templateRepo = remember { TemplateRepository() }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf(RedirectConfig()) }
    var sharedPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasModified by remember { mutableStateOf(false) }
    var pendingSaveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 路径弹窗状态（null = 添加，非 null = 编辑对应索引）
    val showPathDialog = remember { mutableStateOf(false) }
    var pathDialogIndex by remember { mutableStateOf<Int?>(null) }
    var dialogPathInput by remember { mutableStateOf("") }
    var pathDialogErrorResId by remember { mutableStateOf<Int?>(null) }
    var showAllowedPathHint by remember { mutableStateOf(false) }

    // 映射弹窗状态
    val showMappingDialog = remember { mutableStateOf(false) }
    var mappingDialogIndex by remember { mutableStateOf<Int?>(null) }
    var dialogMappingRequestPathInput by remember { mutableStateOf("") }
    var dialogMappingFinalPathInput by remember { mutableStateOf("") }
    var mappingDialogErrorResId by remember { mutableStateOf<Int?>(null) }

    // 删除确认弹窗
    val showDeleteDialog = remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    val showSaveTemplateDialog = remember { mutableStateOf(false) }
    var saveTemplateNameInput by remember { mutableStateOf("") }
    var saveTemplateNameError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sharedUidRepo.load()
        sharedPackages = sharedUidRepo.getSharedPackages(packageName)
        config = configRepo.loadPackages(userId, listOf(packageName) + sharedPackages)
        isLoading = false
    }

    // 保存配置并同步到共享 UID 的所有包（UI 立即更新，IO 异步写入）
    fun saveConfig(newConfig: RedirectConfig) {
        var finalConfig = newConfig
        val affectedPackages = linkedSetOf(packageName)

        // 同步配置到共享 UID 的其他包
        if (sharedPackages.isNotEmpty()) {
            val currentAppConfig = newConfig.getAppConfig(packageName)
            val isEnabled = newConfig.redirectApps.contains(packageName)

            for (sharedPkg in sharedPackages) {
                affectedPackages.add(sharedPkg)
                finalConfig = if (isEnabled) {
                    finalConfig.addApp(sharedPkg)
                } else {
                    finalConfig.removeApp(sharedPkg)
                }
                // 同步 allowedRealPaths 和 pathMappings
                if (currentAppConfig != null) {
                    finalConfig = finalConfig.setAppConfig(sharedPkg, currentAppConfig)
                }
            }
        }

        config = finalConfig
        hasModified = true

        // 取消上一次未完成的保存，避免快速切换时旧配置覆盖新配置
        pendingSaveJob?.cancel()
        pendingSaveJob = scope.launch {
            configRepo.saveAppConfigs(finalConfig, affectedPackages)
        }
    }

    val appConfig = config.getAppConfig(packageName)
    val allowedPaths = appConfig?.allowedRealPaths ?: emptyList()
    val pathMappings = appConfig?.pathMappings ?: emptyList()
    val isRedirected = config.redirectApps.contains(packageName)

    var browseRoute by remember { mutableStateOf<PathBrowseRoute?>(null) }

    BackHandler(enabled = browseRoute == null) { onBack(hasModified) }

    val decodedAppName = try {
        java.net.URLDecoder.decode(appName, "UTF-8")
    } catch (_: Exception) {
        appName
    }

    browseRoute?.let { route ->
        PathBrowserPage(
            title = stringResource(R.string.rule_config_browser_title),
            browserRepository = pathBrowserRepo,
            userId = userId,
            packageName = if (route.target == BrowseTarget.MappingCurrentPath) packageName else null,
            onBack = {
                // 恢复对话框 show 状态，弥补离开 composition 时的重置
                when (route.target) {
                    BrowseTarget.Path -> showPathDialog.value = true
                    BrowseTarget.MappingTargetPath, BrowseTarget.MappingCurrentPath ->
                        showMappingDialog.value = true
                }
                browseRoute = null
            },
            onPathSelect = { selected ->
                when (route.target) {
                    BrowseTarget.Path -> {
                        dialogPathInput = selected
                        pathDialogErrorResId = null
                        showPathDialog.value = true
                    }

                    BrowseTarget.MappingTargetPath -> {
                        dialogMappingFinalPathInput = selected
                        mappingDialogErrorResId = null
                        showMappingDialog.value = true
                    }

                    BrowseTarget.MappingCurrentPath -> {
                        dialogMappingRequestPathInput = selected
                        mappingDialogErrorResId = null
                        showMappingDialog.value = true
                    }
                }
                browseRoute = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            SrxSmallTopAppBar(
                title = decodedAppName,
                navigationIcon = {
                    IconButton(
                        onClick = { onBack(hasModified) },
                        modifier = Modifier.padding(start = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                    ) {
                        Icon(MiuixIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (allowedPaths.isEmpty() && pathMappings.isEmpty()) {
                                Toast.makeText(context, R.string.apps_template_save_failed_empty, Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            saveTemplateNameInput = decodedAppName
                            saveTemplateNameError = false
                            showSaveTemplateDialog.value = true
                        },
                        modifier = Modifier
                            .padding(end = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                            .testTag("rule_config_save_as_template"),
                    ) {
                        Icon(
                            MiuixIcons.Backup,
                            contentDescription = stringResource(R.string.apps_save_as_template),
                        )
                    }
                },
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = RULE_CONFIG_PAGE_HORIZONTAL_PADDING)
            ) {
                // 重定向开关
                item {
                    Spacer(Modifier.height(RULE_CONFIG_SECTION_TOP_SPACING))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = stringResource(R.string.rule_config_enable_redirect),
                            summary = if (isRedirected) stringResource(R.string.rule_config_redirect_enabled)
                            else stringResource(R.string.rule_config_redirect_disabled),
                            checked = isRedirected,
                            onCheckedChange = { enabled ->
                                val newConfig = if (enabled) config.addApp(packageName)
                                else config.removeApp(packageName)
                                saveConfig(newConfig)
                            }
                        )
                    }
                }

                // 共享 UID 提示
                if (sharedPackages.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(RULE_CONFIG_SECTION_TOP_SPACING))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(RULE_CONFIG_CARD_CONTENT_PADDING)) {
                                Text(
                                    text = stringResource(R.string.rule_config_shared_uid_title),
                                    style = MiuixTheme.textStyles.title4,
                                    color = MiuixTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(RULE_CONFIG_ITEM_SPACING))
                                Text(
                                    text = stringResource(R.string.rule_config_shared_uid_hint),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(Modifier.height(RULE_CONFIG_ITEM_SPACING))
                                for (pkg in sharedPackages) {
                                    Text(
                                        text = "· $pkg",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }

                // 允许访问路径
                item {
                    SectionHeader(
                        title = "${stringResource(R.string.rule_config_allowed_paths)} (${allowedPaths.size})",
                        onInfo = { showAllowedPathHint = !showAllowedPathHint },
                        onAdd = {
                            pathDialogIndex = null
                            dialogPathInput = ""
                            pathDialogErrorResId = null
                            showPathDialog.value = true
                        }
                    )
                }
                item {
                    AnimatedVisibility(
                        visible = showAllowedPathHint,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(RULE_CONFIG_INLINE_HINT_TOP_SPACING))
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.rule_config_allowed_paths_rule_hint),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(RULE_CONFIG_CARD_CONTENT_PADDING)
                                )
                            }
                            Spacer(Modifier.height(RULE_CONFIG_INLINE_HINT_BOTTOM_SPACING))
                        }
                    }
                }

                if (allowedPaths.isEmpty()) {
                    item { EmptyPlaceholder(stringResource(R.string.rule_config_no_paths)) }
                } else {
                    itemsIndexed(allowedPaths) { index, path ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = RULE_CONFIG_PAGE_HORIZONTAL_PADDING,
                                        vertical = RULE_CONFIG_LIST_ROW_VERTICAL_PADDING
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = path,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = {
                                    pathDialogIndex = index
                                    dialogPathInput = path
                                    pathDialogErrorResId = null
                                    showPathDialog.value = true
                                }) {
                                    Icon(MiuixIcons.Edit, contentDescription = stringResource(R.string.common_edit))
                                }
                                IconButton(onClick = {
                                    deleteTarget = DeleteTarget.Path(index)
                                    showDeleteDialog.value = true
                                }) {
                                    Icon(
                                        MiuixIcons.Delete,
                                        contentDescription = stringResource(R.string.common_delete),
                                        tint = MiuixTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(RULE_CONFIG_ITEM_SPACING))
                    }
                }

                // 路径映射
                item {
                    SectionHeader(
                        title = "${stringResource(R.string.rule_config_path_mappings)} (${pathMappings.size})",
                        onAdd = {
                            mappingDialogIndex = null
                            dialogMappingRequestPathInput = ""
                            dialogMappingFinalPathInput = ""
                            mappingDialogErrorResId = null
                            showMappingDialog.value = true
                        }
                    )
                }

                if (pathMappings.isEmpty()) {
                    item { EmptyPlaceholder(stringResource(R.string.rule_config_no_mappings)) }
                } else {
                    itemsIndexed(pathMappings) { index, mapping ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = RULE_CONFIG_PAGE_HORIZONTAL_PADDING,
                                        vertical = RULE_CONFIG_LIST_ROW_VERTICAL_PADDING
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${stringResource(R.string.rule_config_mapping_request_path)}: ${mapping.requestPath}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(RULE_CONFIG_MAPPING_SUBTITLE_TOP_SPACING))
                                    Text(
                                        text = "${stringResource(R.string.rule_config_mapping_final_path)}: ${mapping.finalPath}",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = {
                                    mappingDialogIndex = index
                                    dialogMappingRequestPathInput = mapping.requestPath
                                    dialogMappingFinalPathInput = mapping.finalPath
                                    mappingDialogErrorResId = null
                                    showMappingDialog.value = true
                                }) {
                                    Icon(MiuixIcons.Edit, contentDescription = stringResource(R.string.common_edit))
                                }
                                IconButton(onClick = {
                                    deleteTarget = DeleteTarget.Mapping(index)
                                    showDeleteDialog.value = true
                                }) {
                                    Icon(
                                        MiuixIcons.Delete,
                                        contentDescription = stringResource(R.string.common_delete),
                                        tint = MiuixTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(RULE_CONFIG_ITEM_SPACING))
                    }
                }

                // 提示信息
                item {
                    Spacer(Modifier.height(RULE_CONFIG_HINT_SECTION_SPACING))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.rule_config_restart_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(RULE_CONFIG_CARD_CONTENT_PADDING)
                        )
                    }
                    Spacer(Modifier.height(RULE_CONFIG_HINT_SECTION_SPACING))
                }
            }
        }
    }

    // 路径添加/编辑弹窗（合并）
    val isEditingPath = pathDialogIndex != null
    WindowDialog(
        show = showPathDialog.value,
        title = stringResource(
            if (isEditingPath) R.string.rule_config_edit_path else R.string.rule_config_add_path
        ),
        onDismissRequest = {
            pathDialogErrorResId = null
            showPathDialog.value = false
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = dialogPathInput,
                onValueChange = {
                    dialogPathInput = it
                    pathDialogErrorResId = null
                },
                label = stringResource(R.string.rule_config_real_path),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(RULE_CONFIG_DIALOG_FIELD_SPACING))
            TextButton(
                text = stringResource(R.string.rule_config_browse),
                onClick = {
                    browseRoute = PathBrowseRoute(BrowseTarget.Path)
                }
            )
        }
        pathDialogErrorResId?.let { errorResId ->
            Spacer(Modifier.height(RULE_CONFIG_DIALOG_FIELD_SPACING))
            Text(
                text = stringResource(errorResId),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2
            )
        }
        Spacer(Modifier.height(RULE_CONFIG_DIALOG_BOTTOM_SPACING))
        DialogButtonRow(
            show = showPathDialog,
            onConfirm = {
                val validation = RedirectConfig.validateAllowedPath(dialogPathInput)
                when (validation) {
                    is PathValidationResult.Valid -> {
                        pathDialogErrorResId = null
                        scope.launch {
                            val newConfig = if (isEditingPath) {
                                val targetIndex = pathDialogIndex ?: return@launch
                                config.updateAllowedRealPath(packageName, targetIndex, validation.normalized)
                            } else {
                                config.addAllowedRealPath(packageName, validation.normalized)
                            }
                            saveConfig(newConfig)
                        }
                        true
                    }

                    PathValidationResult.Empty -> {
                        pathDialogErrorResId = R.string.rule_config_error_empty
                        false
                    }

                    PathValidationResult.Absolute -> {
                        pathDialogErrorResId = R.string.rule_config_error_absolute
                        false
                    }

                    PathValidationResult.Traversal -> {
                        pathDialogErrorResId = R.string.rule_config_error_traversal
                        false
                    }

                    PathValidationResult.AndroidDataPath -> {
                        pathDialogErrorResId = R.string.rule_config_error_android_prefix
                        false
                    }

                    PathValidationResult.WildcardNotAllowed -> {
                        pathDialogErrorResId = R.string.rule_config_error_mapping_wildcard
                        false
                    }

                    PathValidationResult.ExclusionNotAllowed -> {
                        pathDialogErrorResId = R.string.rule_config_error_mapping_exclusion
                        false
                    }
                }
            }
        )
    }

    // 映射添加/编辑弹窗（合并）
    val isEditingMapping = mappingDialogIndex != null
    WindowDialog(
        show = showMappingDialog.value,
        title = stringResource(
            if (isEditingMapping) R.string.rule_config_edit_mapping else R.string.rule_config_add_mapping
        ),
        onDismissRequest = {
            mappingDialogErrorResId = null
            showMappingDialog.value = false
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = dialogMappingRequestPathInput,
                onValueChange = {
                    dialogMappingRequestPathInput = it
                    mappingDialogErrorResId = null
                },
                label = stringResource(R.string.rule_config_mapping_request_path),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(RULE_CONFIG_DIALOG_FIELD_SPACING))
            TextButton(
                text = stringResource(R.string.rule_config_browse),
                onClick = {
                    browseRoute = PathBrowseRoute(BrowseTarget.MappingCurrentPath)
                }
            )
        }
        Spacer(Modifier.height(RULE_CONFIG_DIALOG_FIELD_SPACING))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = dialogMappingFinalPathInput,
                onValueChange = {
                    dialogMappingFinalPathInput = it
                    mappingDialogErrorResId = null
                },
                label = stringResource(R.string.rule_config_mapping_final_path),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(RULE_CONFIG_DIALOG_FIELD_SPACING))
            TextButton(
                text = stringResource(R.string.rule_config_browse),
                onClick = {
                    browseRoute = PathBrowseRoute(BrowseTarget.MappingTargetPath)
                }
            )
        }
        mappingDialogErrorResId?.let { errorResId ->
            Spacer(Modifier.height(RULE_CONFIG_DIALOG_FIELD_SPACING))
            Text(
                text = stringResource(errorResId),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2
            )
        }
        Spacer(Modifier.height(RULE_CONFIG_DIALOG_BOTTOM_SPACING))
        DialogButtonRow(
            show = showMappingDialog,
            onConfirm = {
                val mappingRequestPathValidation = RedirectConfig.validateMappingPath(dialogMappingRequestPathInput)
                if (mappingRequestPathValidation !is PathValidationResult.Valid) {
                    mappingDialogErrorResId = resolvePathValidationErrorResId(mappingRequestPathValidation)
                    return@DialogButtonRow false
                }

                val mappingFinalPathValidation = RedirectConfig.validateMappingPath(dialogMappingFinalPathInput)
                if (mappingFinalPathValidation !is PathValidationResult.Valid) {
                    mappingDialogErrorResId = resolvePathValidationErrorResId(mappingFinalPathValidation)
                    return@DialogButtonRow false
                }

                if (mappingRequestPathValidation.normalized == mappingFinalPathValidation.normalized) {
                    mappingDialogErrorResId = R.string.rule_config_error_same_paths
                    return@DialogButtonRow false
                }

                mappingDialogErrorResId = null
                scope.launch {
                    val mapping = StoragePathMapping(
                        mappingRequestPathValidation.normalized,
                        mappingFinalPathValidation.normalized
                    )
                    val newConfig = if (isEditingMapping) {
                        val targetIndex = mappingDialogIndex ?: return@launch
                        config.updatePathMapping(packageName, targetIndex, mapping)
                    } else {
                        config.addPathMapping(packageName, mapping)
                    }
                    saveConfig(newConfig)
                }
                true
            }
        )
    }

    WindowDialog(
        show = showSaveTemplateDialog.value,
        title = stringResource(R.string.templates_save_from_app_title),
        onDismissRequest = { showSaveTemplateDialog.value = false },
    ) {
        TextField(
            value = saveTemplateNameInput,
            onValueChange = {
                saveTemplateNameInput = it
                saveTemplateNameError = false
            },
            label = stringResource(R.string.templates_name),
            modifier = Modifier.fillMaxWidth().testTag("rule_config_save_template_name_input"),
        )
        if (saveTemplateNameError) {
            Spacer(Modifier.height(RULE_CONFIG_DIALOG_FIELD_SPACING))
            Text(
                text = stringResource(R.string.templates_name_empty),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.height(RULE_CONFIG_DIALOG_BOTTOM_SPACING))
        DialogButtonRow(
            show = showSaveTemplateDialog,
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                val templateName = saveTemplateNameInput.trim()
                if (templateName.isEmpty()) {
                    saveTemplateNameError = true
                    return@DialogButtonRow false
                }
                val currentConfig = config.getAppConfig(packageName) ?: return@DialogButtonRow false
                templateRepo.saveTemplate(templateRepo.createTemplateFromApp(templateName, currentConfig))
                Toast.makeText(context, R.string.templates_saved, Toast.LENGTH_SHORT).show()
                true
            },
        )
    }

    // 删除确认弹窗（合并路径/映射）
    val isDeletePath = deleteTarget is DeleteTarget.Path
    WindowDialog(
        show = showDeleteDialog.value,
        title = stringResource(
            if (isDeletePath) R.string.rule_config_delete_path else R.string.rule_config_delete_mapping
        ),
        summary = stringResource(
            if (isDeletePath) R.string.rule_config_delete_confirm else R.string.rule_config_delete_mapping_confirm
        ),
        onDismissRequest = { showDeleteDialog.value = false }
    ) {
        DialogButtonRow(
            show = showDeleteDialog,
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    val target = deleteTarget ?: return@launch
                    val newConfig = when (target) {
                        is DeleteTarget.Path -> config.removeAllowedRealPath(packageName, target.index)
                        is DeleteTarget.Mapping -> config.removePathMapping(packageName, target.index)
                    }
                    saveConfig(newConfig)
                }
                true
            }
        )
    }
}

// 删除目标类型
private sealed class DeleteTarget {
    data class Path(val index: Int) : DeleteTarget()
    data class Mapping(val index: Int) : DeleteTarget()
}

// 区块标题（可选信息按钮与添加按钮）
@Composable
private fun SectionHeader(title: String, onAdd: () -> Unit, onInfo: (() -> Unit)? = null) {
    Spacer(Modifier.height(RULE_CONFIG_HEADER_TOP_SPACING))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = RULE_CONFIG_HEADER_BOTTOM_SPACING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            modifier = Modifier.weight(1f)
        )
        onInfo?.let {
            IconButton(onClick = it) {
                Icon(
                    MiuixIcons.Info,
                    contentDescription = stringResource(R.string.rule_config_allowed_paths_rule_hint_desc),
                    tint = MiuixTheme.colorScheme.primary
                )
            }
        }
        IconButton(onClick = onAdd) {
            Icon(
                MiuixIcons.Add,
                contentDescription = stringResource(R.string.common_add),
                tint = MiuixTheme.colorScheme.primary
            )
        }
    }
}

// 空列表占位
@Composable
private fun EmptyPlaceholder(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(RULE_CONFIG_EMPTY_PLACEHOLDER_PADDING),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

private fun resolvePathValidationErrorResId(result: PathValidationResult): Int {
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

private enum class BrowseTarget {
    Path,
    MappingTargetPath,
    MappingCurrentPath,
}

private data class PathBrowseRoute(
    val target: BrowseTarget,
)

// 弹窗按钮行
@Composable
private fun DialogButtonRow(
    show: MutableState<Boolean>,
    confirmText: String = stringResource(R.string.common_ok),
    onConfirm: () -> Boolean
) {
    Row {
        TextButton(
            text = stringResource(R.string.common_cancel),
            onClick = { show.value = false },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(RULE_CONFIG_DIALOG_BUTTON_SPACING))
        TextButton(
            text = confirmText,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
                val shouldClose = onConfirm()
                if (shouldClose) {
                    show.value = false
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}
