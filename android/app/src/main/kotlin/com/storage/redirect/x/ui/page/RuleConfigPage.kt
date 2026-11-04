package com.storage.redirect.x.ui.page

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.icon.AppIcons
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.model.StoragePathMapping
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.PathBrowserRepository
import com.storage.redirect.x.data.repository.SharedUidRepository
import com.storage.redirect.x.data.repository.TemplateApplyMode
import com.storage.redirect.x.data.repository.TemplateRepository
import com.storage.redirect.x.ui.component.PAGE_CONTENT_TOP_PADDING
import com.storage.redirect.x.ui.component.PAGE_GROUP_GAP
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PAGE_ITEM_GAP
import com.storage.redirect.x.ui.component.STATUS_WARNING_COLOR
import com.storage.redirect.x.ui.component.SrxBackButton
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.navigation.LocalPageReady
import com.storage.redirect.x.ui.navigation.PageStackHost
import com.storage.redirect.x.ui.navigation.rememberPageStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import com.storage.redirect.x.ui.component.SrxIconButton as IconButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面间距：列表横向边距，区块间距，行内边距，弹窗字段间距与提示卡片间距
private val RULE_CONFIG_ITEM_SPACING = 4.dp
private val RULE_CONFIG_CARD_CONTENT_PADDING = 16.dp
private val RULE_CONFIG_APP_ICON_SIZE = 40.dp
private val RULE_CONFIG_APP_ICON_END_PADDING = 12.dp
private const val RULE_CONFIG_WARNING_CARD_ALPHA = 0.12f
private val RULE_CONFIG_DIALOG_FIELD_SPACING = 8.dp
private val RULE_CONFIG_DIALOG_BOTTOM_SPACING = 16.dp
private val RULE_CONFIG_DIALOG_BUTTON_SPACING = 12.dp

@Composable
fun RuleConfigPage(
    packageName: String,
    appName: String,
    userId: Int = 0,
    onBack: () -> Unit = {},
    onModifiedExit: () -> Unit = {},
) {
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
    // 保存锁覆盖完整 I/O，旧任务取消后新写入仍须等待旧写入结束。
    val saveMutex = remember { Mutex() }

    // 映射弹窗状态
    val showMappingDialog = remember { mutableStateOf(false) }
    var dialogMappingRequestPathInput by remember { mutableStateOf("") }
    var dialogMappingFinalPathInput by remember { mutableStateOf("") }
    var mappingDialogErrorResId by remember { mutableStateOf<Int?>(null) }

    // 删除确认弹窗
    val showDeleteDialog = remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    val showSaveTemplateDialog = remember { mutableStateOf(false) }
    var saveTemplateNameInput by remember { mutableStateOf("") }
    var saveTemplateNameError by remember { mutableStateOf(false) }

    // 模板选择、应用方式与共享 UID 确认状态
    var templates by remember { mutableStateOf<List<RedirectTemplate>>(emptyList()) }
    val showTemplatePicker = remember { mutableStateOf(false) }
    val showApplyModeDialog = remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<RedirectTemplate?>(null) }
    var pendingApplyMode by remember { mutableStateOf<TemplateApplyMode?>(null) }
    val showSharedUidApplyDialog = remember { mutableStateOf(false) }
    val onReady = LocalPageReady.current

    LaunchedEffect(Unit) {
        sharedUidRepo.load()
        sharedPackages = sharedUidRepo.getSharedPackages(packageName)
        config = configRepo.loadPackages(userId, listOf(packageName) + sharedPackages)
        isLoading = false
        onReady()
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
            saveMutex.withLock {
                configRepo.saveAppConfigs(finalConfig, affectedPackages)
            }
        }
    }

    fun clearTemplateSelection() {
        showTemplatePicker.value = false
        showApplyModeDialog.value = false
        showSharedUidApplyDialog.value = false
        templates = emptyList()
        selectedTemplate = null
        pendingApplyMode = null
    }

    fun applyTemplateToCurrent(template: RedirectTemplate, mode: TemplateApplyMode) {
        val result = templateRepo.applyTemplate(config, packageName, sharedPackages, template, mode)
        clearTemplateSelection()
        val previousConfig = config
        val previousHasModified = hasModified
        config = result.config
        hasModified = true
        pendingSaveJob?.cancel()
        pendingSaveJob = scope.launch {
            val isSaved = saveMutex.withLock {
                configRepo.saveAppConfigs(result.config, result.affectedPackages)
            }
            if (!isSaved) {
                config = previousConfig
                hasModified = previousHasModified
            }
            Toast.makeText(
                context,
                if (isSaved) R.string.apps_template_apply_success else R.string.rule_config_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun requestApplyTemplate(template: RedirectTemplate, mode: TemplateApplyMode) {
        if (sharedPackages.isNotEmpty()) {
            pendingApplyMode = mode
            showSharedUidApplyDialog.value = true
            return
        }
        applyTemplateToCurrent(template, mode)
    }

    val appConfig = config.getAppConfig(packageName)
    val allowedPaths = appConfig?.allowedRealPaths ?: emptyList()
    val pathMappings = appConfig?.pathMappings ?: emptyList()
    val isRedirected = config.redirectApps.contains(packageName)

    val browseStack = rememberPageStack<BrowseTarget>()
    // 选择器打开期间对话框先隐藏，退出转场播完后再按原状态显示，避免弹窗窗口与转场抢帧
    val isBrowsing = !browseStack.isEmpty

    // 返回键、预测返回与顶栏返回都会让页面移出，统一在此通知上层刷新
    val latestHasModified by rememberUpdatedState(hasModified)
    val latestOnModifiedExit by rememberUpdatedState(onModifiedExit)
    DisposableEffect(Unit) {
        onDispose { if (latestHasModified) latestOnModifiedExit() }
    }

    val decodedAppName = try {
        java.net.URLDecoder.decode(appName, "UTF-8")
    } catch (_: Exception) {
        appName
    }

    // 应用图标在 IO 线程读取；目标应用未装在主用户时没有图标
    val appIcon by produceState<Drawable?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    PageStackHost(
        stack = browseStack,
        page = { target, onBrowserBack ->
            PathBrowserPage(
                browserRepository = pathBrowserRepo,
                userId = userId,
                packageName = if (target == BrowseTarget.MappingCurrentPath) packageName else null,
                multiSelect = target == BrowseTarget.Path,
                allowExclude = target == BrowseTarget.Path,
                onBack = onBrowserBack,
                // 允许路径只走多选，单选结果只来自映射路径
                onPathSelect = { selected ->
                    if (target == BrowseTarget.MappingCurrentPath) {
                        dialogMappingRequestPathInput = selected
                    } else {
                        dialogMappingFinalPathInput = selected
                    }
                    mappingDialogErrorResId = null
                    showMappingDialog.value = true
                    onBrowserBack()
                },
                onPathsSelect = { paths ->
                    val validPaths = paths.mapNotNull { raw ->
                        val validation = RedirectConfig.validateRealPathRule(raw)
                        if (validation is PathValidationResult.Valid) validation.normalized else null
                    }
                    if (validPaths.isEmpty()) {
                        Toast.makeText(context, R.string.rule_config_browser_invalid_path, Toast.LENGTH_SHORT).show()
                    } else {
                        var nextConfig = config
                        for (path in validPaths) {
                            nextConfig = nextConfig.addAllowedRealPath(packageName, path)
                        }
                        saveConfig(nextConfig)
                        if (validPaths.size < paths.size) {
                            Toast.makeText(context, R.string.rule_config_paths_partially_invalid, Toast.LENGTH_SHORT).show()
                        }
                    }
                    onBrowserBack()
                },
            )
        },
    ) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        SrxScaffold(
            topBar = {
                SrxTopAppBar(
                    title = decodedAppName,
                    subtitle = packageName,
                    navigationIcon = { SrxBackButton(onClick = onBack) },
                    actions = {
                        IconButton(
                            onClick = {
                                clearTemplateSelection()
                                templates = templateRepo.loadTemplates()
                                showTemplatePicker.value = true
                            },
                            enabled = !isLoading,
                            modifier = Modifier.testTag("rule_config_apply_template"),
                        ) {
                            Icon(
                                AppIcons.Paste,
                                contentDescription = stringResource(R.string.apps_apply_template),
                            )
                        }
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
                            modifier = Modifier.testTag("rule_config_save_as_template"),
                        ) {
                            Icon(
                                AppIcons.Save,
                                contentDescription = stringResource(R.string.apps_save_as_template),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
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
                return@SrxScaffold
            }

            Box(modifier = Modifier.fillMaxSize()) {
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
                    // 重定向开关，带应用图标
                    item {
                        Spacer(Modifier.height(PAGE_CONTENT_TOP_PADDING))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                title = stringResource(R.string.rule_config_enable_redirect),
                                summary = if (isRedirected) stringResource(R.string.rule_config_redirect_enabled)
                                else stringResource(R.string.rule_config_redirect_disabled),
                                checked = isRedirected,
                                startAction = {
                                    AppIcon(
                                        drawable = appIcon,
                                        modifier = Modifier
                                            .padding(end = RULE_CONFIG_APP_ICON_END_PADDING)
                                            .size(RULE_CONFIG_APP_ICON_SIZE),
                                        placeholderColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    )
                                },
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
                            Spacer(Modifier.height(PAGE_ITEM_GAP))
                            SharedUidCard(sharedPackages)
                        }
                    }

                    // 允许访问路径：添加入口固定在卡片底部
                    item {
                        RuleSectionTitle(
                            title = stringResource(
                                R.string.rule_config_section_count,
                                stringResource(R.string.rule_config_allowed_paths),
                                allowedPaths.size,
                            ),
                        )
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            if (allowedPaths.isEmpty()) {
                                RuleEmptyRow(stringResource(R.string.rule_config_no_paths))
                            }
                            allowedPaths.forEachIndexed { index, path ->
                                AllowedPathRow(
                                    path = path,
                                    onDelete = {
                                        deleteTarget = DeleteTarget.Path(index)
                                        showDeleteDialog.value = true
                                    },
                                )
                            }
                            CardActionRow {
                                CardActionButton(
                                    icon = AppIcons.Add,
                                    text = stringResource(R.string.rule_config_add_path),
                                    onClick = { browseStack.push(BrowseTarget.Path) },
                                    modifier = Modifier.testTag("rule_config_add_path"),
                                )
                            }
                        }
                    }

                    // 路径映射
                    item {
                        RuleSectionTitle(
                            title = stringResource(
                                R.string.rule_config_section_count,
                                stringResource(R.string.rule_config_path_mappings),
                                pathMappings.size,
                            ),
                        )
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            if (pathMappings.isEmpty()) {
                                RuleEmptyRow(stringResource(R.string.rule_config_no_mappings))
                            }
                            pathMappings.forEachIndexed { index, mapping ->
                                PathMappingRow(
                                    requestPath = mapping.requestPath,
                                    finalPath = mapping.finalPath,
                                    onDelete = {
                                        deleteTarget = DeleteTarget.Mapping(index)
                                        showDeleteDialog.value = true
                                    },
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
                                    modifier = Modifier.testTag("rule_config_add_mapping"),
                                )
                            }
                        }
                    }

                    // 提示信息
                    item {
                        Text(
                            text = stringResource(R.string.rule_config_restart_hint),
                            modifier = Modifier.padding(
                                start = RULE_CONFIG_CARD_CONTENT_PADDING,
                                end = RULE_CONFIG_CARD_CONTENT_PADDING,
                                top = PAGE_GROUP_GAP,
                                bottom = PAGE_GROUP_GAP,
                            ),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }

    // 映射添加弹窗
    WindowDialog(
        show = showMappingDialog.value && !isBrowsing,
        title = stringResource(R.string.rule_config_add_mapping),
        summary = stringResource(R.string.rule_config_mapping_hint),
        onDismissRequest = {
            mappingDialogErrorResId = null
            showMappingDialog.value = false
        }
    ) {
        RulePathField(
            value = dialogMappingRequestPathInput,
            onValueChange = {
                dialogMappingRequestPathInput = it
                mappingDialogErrorResId = null
            },
            label = stringResource(R.string.rule_config_mapping_request_path),
            onBrowse = { browseStack.push(BrowseTarget.MappingCurrentPath) },
        )
        MappingDirectionArrow()
        RulePathField(
            value = dialogMappingFinalPathInput,
            onValueChange = {
                dialogMappingFinalPathInput = it
                mappingDialogErrorResId = null
            },
            label = stringResource(R.string.rule_config_mapping_final_path),
            onBrowse = { browseStack.push(BrowseTarget.MappingTargetPath) },
        )
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
                val mappingRequestPathValidation =
                    RedirectConfig.validateMappingPath(dialogMappingRequestPathInput, packageName)
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
                    saveConfig(config.addPathMapping(packageName, mapping))
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

    WindowDialog(
        show = showTemplatePicker.value,
        title = stringResource(R.string.apps_apply_template),
        onDismissRequest = { clearTemplateSelection() },
    ) {
        TemplatePickerContent(
            templates = templates,
            testTag = "rule_config_template_picker",
            onPick = { template ->
                showTemplatePicker.value = false
                selectedTemplate = template
                showApplyModeDialog.value = true
            },
            onCancel = { clearTemplateSelection() },
        )
    }

    WindowDialog(
        show = showApplyModeDialog.value,
        title = stringResource(R.string.apps_template_apply_mode_title),
        summary = stringResource(
            R.string.apps_template_apply_mode_summary,
            selectedTemplate?.name ?: "",
            decodedAppName,
        ),
        onDismissRequest = { clearTemplateSelection() },
    ) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("rule_config_apply_mode_dialog"),
        ) {
            Row {
                TextButton(
                    text = stringResource(R.string.apps_template_apply_merge),
                    onClick = {
                        val template = selectedTemplate ?: return@TextButton
                        showApplyModeDialog.value = false
                        requestApplyTemplate(template, TemplateApplyMode.Merge)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("rule_config_apply_mode_merge"),
                )
                Spacer(Modifier.width(RULE_CONFIG_DIALOG_BUTTON_SPACING))
                TextButton(
                    text = stringResource(R.string.apps_template_apply_replace),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val template = selectedTemplate ?: return@TextButton
                        showApplyModeDialog.value = false
                        requestApplyTemplate(template, TemplateApplyMode.Replace)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("rule_config_apply_mode_replace"),
                )
            }
        }
    }

    WindowDialog(
        show = showSharedUidApplyDialog.value,
        title = stringResource(R.string.apps_template_shared_uid_confirm_title),
        summary = stringResource(R.string.apps_template_shared_uid_confirm_summary),
        onDismissRequest = { clearTemplateSelection() },
    ) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("rule_config_shared_uid_dialog"),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
            ) {
                items(sharedPackages) { pkg ->
                    Text(
                        text = pkg,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(RULE_CONFIG_DIALOG_BOTTOM_SPACING))
            Row {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { clearTemplateSelection() },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("rule_config_shared_uid_cancel"),
                )
                Spacer(Modifier.width(RULE_CONFIG_DIALOG_BUTTON_SPACING))
                TextButton(
                    text = stringResource(R.string.common_ok),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val template = selectedTemplate ?: return@TextButton
                        val mode = pendingApplyMode ?: return@TextButton
                        applyTemplateToCurrent(template, mode)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("rule_config_shared_uid_ok"),
                )
            }
        }
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

@Composable
private fun SharedUidCard(sharedPackages: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = STATUS_WARNING_COLOR.copy(alpha = RULE_CONFIG_WARNING_CARD_ALPHA)),
        insideMargin = PaddingValues(RULE_CONFIG_CARD_CONTENT_PADDING),
    ) {
        Text(
            text = stringResource(R.string.rule_config_shared_uid_title),
            style = MiuixTheme.textStyles.title4,
            color = STATUS_WARNING_COLOR,
        )
        Text(
            text = stringResource(R.string.rule_config_shared_uid_hint),
            modifier = Modifier.padding(vertical = RULE_CONFIG_ITEM_SPACING),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        for (pkg in sharedPackages) {
            Text(
                text = "· $pkg",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private enum class BrowseTarget {
    Path,
    MappingTargetPath,
    MappingCurrentPath,
}

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
