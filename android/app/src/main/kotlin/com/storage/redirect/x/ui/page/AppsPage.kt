package com.storage.redirect.x.ui.page

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.AppInfo
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.provider.AppListProvider
import com.storage.redirect.x.data.repository.TemplateApplyMode
import com.storage.redirect.x.data.service.SystemUser
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip

private enum class SortMode {
    NAME,
    INSTALL_TIME,
}

private const val PREF_SHOW_SYSTEM_APPS = "apps_show_system"
private const val PREF_REDIRECTED_FIRST = "apps_redirected_first"
private const val PREF_SORT_MODE = "apps_sort_mode"

@Immutable
internal data class AppsPageData(
    val isLoading: Boolean,
    val filteredApps: ImmutableList<AppInfo>,
    val listState: LazyListState,
    val icons: ImmutableMap<String, Drawable>,
    val redirectedPackages: ImmutableList<String>,
    val selectedApps: ImmutableList<AppInfo>,
    val isBatchMode: Boolean,
    val searchQuery: String,
    val showSystemApps: Boolean,
    val redirectedFirst: Boolean,
    val isSortByName: Boolean,
    val systemUsers: ImmutableList<SystemUser>,
    val currentUserIndex: Int,
    val templates: ImmutableList<RedirectTemplate>,
    val selectedTemplate: RedirectTemplate?,
    val sharedPackages: ImmutableList<String>,
    val selectedBatchCount: Int,
    val bottomInnerPadding: Dp,
    val isRefreshing: Boolean,
    val iconGeneration: Int,
)

internal data class AppsPageDialogState(
    val showUserMenu: MutableState<Boolean>,
    val showFilterMenu: MutableState<Boolean>,
    val showTemplatePicker: MutableState<Boolean>,
    val showApplyModeDialog: MutableState<Boolean>,
    val showSharedUidConfirmDialog: MutableState<Boolean>,
    val showRestoreRulesDialog: MutableState<Boolean>,
)

internal data class AppsPageActions(
    val onSearchChange: (String) -> Unit,
    val onRequestIcon: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onUserSelected: (Int) -> Unit,
    val onSortByName: () -> Unit,
    val onSortByInstallTime: () -> Unit,
    val onToggleSystemApps: () -> Unit,
    val onToggleRedirectedFirst: () -> Unit,
    val onAppClick: (AppInfo) -> Unit,
    val onAppLongClick: (AppInfo) -> Unit,
    val onCancelBatch: () -> Unit,
    val onSelectAll: () -> Unit,
    val onInvertSelection: () -> Unit,
    val onOpenTemplates: () -> Unit,
    val onShowTemplatePicker: () -> Unit,
    val onShowRestoreRules: () -> Unit,
    val onConfirmRestoreRules: () -> Unit,
    val onTemplateSelected: (RedirectTemplate) -> Unit,
    val onApplyModeSelected: (TemplateApplyMode) -> Unit,
    val onConfirmSharedApply: () -> Unit,
    val onDismissTemplatePicker: () -> Unit,
    val onDismissApplyMode: () -> Unit,
    val onDismissSharedConfirm: () -> Unit,
)

@Composable
internal fun AppIcon(
    drawable: Drawable?,
    modifier: Modifier = Modifier,
    placeholderColor: Color,
) {
    if (drawable != null) {
        val bitmap =
            remember(drawable) {
                (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap()
                    ?: drawable.toBitmap(96, 96).asImageBitmap()
            }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.squircleClip(12.dp),
        )
    } else {
        Box(modifier = modifier.squircleBackground(placeholderColor, 12.dp))
    }
}

@Composable
fun AppsPage(
    onAppClick: (String, String, Int) -> Unit = { _, _, _ -> },
    configVersion: Int = 0,
    bottomInnerPadding: Dp = 0.dp,
    onMenuStateChange: (Boolean) -> Unit = {},
    onTemplatesClick: () -> Unit = {},
    appsViewModel: AppsViewModel = rememberAppsViewModel(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val catalog by appsViewModel.uiState.collectAsStateWithLifecycle()
    val filteredApps by appsViewModel.filteredApps.collectAsStateWithLifecycle()
    val configRepo = appsViewModel.configRepo
    val sharedUidRepo = appsViewModel.sharedUidRepo
    val templateRepo = appsViewModel.templateRepo
    val scope = rememberCoroutineScope()
    val isLoading = catalog.isLoading
    val icons = catalog.icons
    val searchQuery = catalog.query
    val showSystemApps = catalog.showSystemApps
    val redirectedFirst = catalog.redirectedFirst
    val sortMode = if (catalog.sortByName) SortMode.NAME else SortMode.INSTALL_TIME
    val systemUsers = catalog.users
    val currentUserIndex = catalog.userIndex
    var config by remember {
        mutableStateOf(
            appsViewModel.cachedConfig(systemUsers[currentUserIndex].id) ?: RedirectConfig()
        )
    }
    var templates by remember { mutableStateOf(templateRepo.loadTemplates()) }
    val selectedAppsForBatch = remember { mutableStateListOf<AppInfo>() }
    var selectedTemplateForApply by remember { mutableStateOf<RedirectTemplate?>(null) }
    var pendingApplyMode by remember { mutableStateOf<TemplateApplyMode?>(null) }
    val showTemplatePicker = remember { mutableStateOf(false) }
    val showApplyModeDialog = remember { mutableStateOf(false) }
    val showSharedUidConfirmDialog = remember { mutableStateOf(false) }
    val showRestoreRulesDialog = remember { mutableStateOf(false) }
    var sharedPackagesForSelectedApp by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingTemplateSaveJob by remember { mutableStateOf<Job?>(null) }
    val isBatchSelectionMode = selectedAppsForBatch.isNotEmpty()

    val showUserMenu = remember { mutableStateOf(false) }
    val showFilterMenu = remember { mutableStateOf(false) }
    val appListState = rememberLazyListState()

    BackHandler(enabled = isBatchSelectionMode) {
        selectedAppsForBatch.clear()
    }

    LaunchedEffect(
        showUserMenu.value,
        showFilterMenu.value,
        showTemplatePicker.value,
        showApplyModeDialog.value,
        showSharedUidConfirmDialog.value,
        showRestoreRulesDialog.value,
    ) {
        onMenuStateChange(
            showUserMenu.value ||
                showFilterMenu.value ||
                showTemplatePicker.value ||
                showApplyModeDialog.value ||
                showSharedUidConfirmDialog.value ||
                showRestoreRulesDialog.value
        )
    }

    LaunchedEffect(appsViewModel) {
        appsViewModel.setQuery("")
        appsViewModel.ensureUsersLoaded()
    }
    LaunchedEffect(configVersion, systemUsers, currentUserIndex) {
        val userId = systemUsers[currentUserIndex].id
        config = appsViewModel.configFor(userId, configVersion)
        templates = templateRepo.loadTemplates()
    }

    fun reloadConfig(userIndex: Int) {
        selectedAppsForBatch.clear()
        appsViewModel.selectUser(userIndex)
    }

    fun saveConfigForTemplate(
        newConfig: RedirectConfig,
        affectedPackages: Set<String>,
        onSaved: (Boolean) -> Unit,
    ) {
        val previousConfig = config
        config = newConfig
        appsViewModel.cacheConfig(systemUsers[currentUserIndex].id, configVersion, newConfig)
        pendingTemplateSaveJob?.cancel()
        pendingTemplateSaveJob = scope.launch {
            val saved = configRepo.saveAppConfigs(newConfig, affectedPackages)
            if (!saved) {
                config = previousConfig
                appsViewModel.cacheConfig(
                    systemUsers[currentUserIndex].id,
                    configVersion,
                    previousConfig,
                )
            }
            onSaved(saved)
        }
    }

    fun requestTemplateApply(template: RedirectTemplate, mode: TemplateApplyMode) {
        val targetApps = selectedAppsForBatch.toList()
        if (targetApps.isEmpty()) return

        selectedTemplateForApply = template
        pendingApplyMode = mode
        val sharedPackages =
            targetApps
                .flatMap { sharedUidRepo.getSharedPackages(it.packageName) }
                .distinct()
                .filterNot { packageName -> targetApps.any { it.packageName == packageName } }
        sharedPackagesForSelectedApp = sharedPackages
        if (sharedPackages.isNotEmpty()) {
            showSharedUidConfirmDialog.value = true
            return
        }

        var nextConfig = config
        val affectedPackages = linkedSetOf<String>()
        for (targetApp in targetApps) {
            val result =
                templateRepo.applyTemplate(
                    nextConfig,
                    targetApp.packageName,
                    emptyList(),
                    template,
                    mode,
                )
            nextConfig = result.config
            affectedPackages.addAll(result.affectedPackages)
        }
        selectedAppsForBatch.clear()
        selectedTemplateForApply = null
        pendingApplyMode = null
        saveConfigForTemplate(nextConfig, affectedPackages) { saved ->
            Toast.makeText(
                    context,
                    if (saved) R.string.apps_template_apply_success
                    else R.string.rule_config_save_failed,
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
    }

    fun confirmRestoreRulesForBatch() {
        val targets = selectedAppsForBatch.toList()
        if (targets.isEmpty()) {
            showRestoreRulesDialog.value = false
            return
        }
        var nextConfig = config
        val affectedPackages = linkedSetOf<String>()
        for (app in targets) {
            val current = nextConfig.getAppConfig(app.packageName) ?: continue
            if (current.allowedRealPaths.isEmpty() && current.pathMappings.isEmpty()) continue
            val cleared = current.copy(allowedRealPaths = emptyList(), pathMappings = emptyList())
            nextConfig = nextConfig.updateAppConfig(cleared)
            affectedPackages.add(app.packageName)
        }
        showRestoreRulesDialog.value = false
        selectedAppsForBatch.clear()
        if (affectedPackages.isEmpty()) {
            Toast.makeText(context, R.string.apps_restore_rules_success, Toast.LENGTH_SHORT).show()
            return
        }
        saveConfigForTemplate(nextConfig, affectedPackages) { saved ->
            Toast.makeText(
                    context,
                    if (saved) R.string.apps_restore_rules_success
                    else R.string.rule_config_save_failed,
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
    }

    fun confirmSharedUidApply() {
        val targetApps = selectedAppsForBatch.toList()
        if (targetApps.isEmpty()) return
        val template = selectedTemplateForApply ?: return
        val mode = pendingApplyMode ?: return
        var nextConfig = config
        val affectedPackages = linkedSetOf<String>()
        for (targetApp in targetApps) {
            val sharedPackages = sharedUidRepo.getSharedPackages(targetApp.packageName)
            val result =
                templateRepo.applyTemplate(
                    nextConfig,
                    targetApp.packageName,
                    sharedPackages,
                    template,
                    mode,
                )
            nextConfig = result.config
            affectedPackages.addAll(result.affectedPackages)
        }
        showSharedUidConfirmDialog.value = false
        selectedAppsForBatch.clear()
        selectedTemplateForApply = null
        pendingApplyMode = null
        saveConfigForTemplate(nextConfig, affectedPackages) { saved ->
            Toast.makeText(
                    context,
                    if (saved) R.string.apps_template_apply_success
                    else R.string.rule_config_save_failed,
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
    }

    val data =
        AppsPageData(
            isLoading = isLoading,
            filteredApps = filteredApps.toImmutableList(),
            listState = appListState,
            icons = icons,
            redirectedPackages = config.redirectApps.toImmutableList(),
            selectedApps = selectedAppsForBatch.toImmutableList(),
            isBatchMode = isBatchSelectionMode,
            searchQuery = searchQuery,
            showSystemApps = showSystemApps,
            redirectedFirst = redirectedFirst,
            isSortByName = sortMode == SortMode.NAME,
            systemUsers = systemUsers.toImmutableList(),
            currentUserIndex = currentUserIndex,
            templates = templates.toImmutableList(),
            selectedTemplate = selectedTemplateForApply,
            sharedPackages = sharedPackagesForSelectedApp.toImmutableList(),
            selectedBatchCount = selectedAppsForBatch.size,
            bottomInnerPadding = bottomInnerPadding,
            isRefreshing = catalog.isRefreshing,
            iconGeneration = catalog.iconGeneration,
        )
    val dialogs =
        AppsPageDialogState(
            showUserMenu = showUserMenu,
            showFilterMenu = showFilterMenu,
            showTemplatePicker = showTemplatePicker,
            showApplyModeDialog = showApplyModeDialog,
            showSharedUidConfirmDialog = showSharedUidConfirmDialog,
            showRestoreRulesDialog = showRestoreRulesDialog,
        )
    val actions =
        AppsPageActions(
            onSearchChange = appsViewModel::setQuery,
            onRequestIcon = appsViewModel::requestIcon,
            onRefresh = {
                appsViewModel.refreshApps()
                appsViewModel.ensureUsersLoaded(force = true)
                scope.launch {
                    config =
                        appsViewModel.configFor(
                            systemUsers[currentUserIndex].id,
                            configVersion,
                            force = true,
                        )
                    templates = templateRepo.loadTemplates()
                }
            },
            onUserSelected = { index ->
                showUserMenu.value = false
                if (index != currentUserIndex) reloadConfig(index)
            },
            onSortByName = {
                appsViewModel.setSortByName(true)
                showFilterMenu.value = false
            },
            onSortByInstallTime = {
                appsViewModel.setSortByName(false)
                showFilterMenu.value = false
            },
            onToggleSystemApps = {
                appsViewModel.setShowSystemApps(!showSystemApps)
                showFilterMenu.value = false
            },
            onToggleRedirectedFirst = {
                appsViewModel.setRedirectedFirst(!redirectedFirst)
                showFilterMenu.value = false
            },
            onAppClick = { app ->
                focusManager.clearFocus()
                if (isBatchSelectionMode) {
                    if (selectedAppsForBatch.any { it.packageName == app.packageName }) {
                        selectedAppsForBatch.removeAll { it.packageName == app.packageName }
                    } else {
                        selectedAppsForBatch.add(app)
                    }
                } else {
                    onAppClick(app.packageName, app.appName, systemUsers[currentUserIndex].id)
                }
            },
            onAppLongClick = { app ->
                focusManager.clearFocus()
                if (selectedAppsForBatch.none { it.packageName == app.packageName }) {
                    selectedAppsForBatch.add(app)
                }
            },
            onCancelBatch = { selectedAppsForBatch.clear() },
            onSelectAll = {
                for (app in filteredApps) {
                    if (selectedAppsForBatch.none { it.packageName == app.packageName }) {
                        selectedAppsForBatch.add(app)
                    }
                }
            },
            onInvertSelection = {
                val selectedKeys = selectedAppsForBatch.map { it.packageName }.toHashSet()
                val newSelection = filteredApps.filterNot { it.packageName in selectedKeys }
                val visible = filteredApps.map { it.packageName }.toHashSet()
                selectedAppsForBatch.removeAll { it.packageName in visible }
                selectedAppsForBatch.addAll(newSelection)
            },
            onOpenTemplates = onTemplatesClick,
            onShowTemplatePicker = {
                templates = templateRepo.loadTemplates()
                showTemplatePicker.value = true
            },
            onShowRestoreRules = { showRestoreRulesDialog.value = true },
            onConfirmRestoreRules = { confirmRestoreRulesForBatch() },
            onTemplateSelected = { template ->
                selectedTemplateForApply = template
                showTemplatePicker.value = false
                showApplyModeDialog.value = true
            },
            onApplyModeSelected = { mode ->
                selectedTemplateForApply?.let { template ->
                    showApplyModeDialog.value = false
                    requestTemplateApply(template, mode)
                }
            },
            onConfirmSharedApply = { confirmSharedUidApply() },
            onDismissTemplatePicker = { showTemplatePicker.value = false },
            onDismissApplyMode = {
                showApplyModeDialog.value = false
                selectedTemplateForApply = null
                pendingApplyMode = null
            },
            onDismissSharedConfirm = {
                showSharedUidConfirmDialog.value = false
                selectedTemplateForApply = null
                pendingApplyMode = null
            },
        )

    AppsPageMiuix(data = data, dialogs = dialogs, actions = actions)
}

@Composable
private fun rememberAppsViewModel(): AppsViewModel {
    val context = LocalContext.current
    return viewModel(viewModelStoreOwner = AppsViewModelOwner) {
        AppsViewModel(AppListProvider(context.applicationContext))
    }
}

// Equivalent to Mishka's application-scoped ViewModel: keep the catalog until process death.
private object AppsViewModelOwner : androidx.lifecycle.ViewModelStoreOwner {
    override val viewModelStore = androidx.lifecycle.ViewModelStore()
}
