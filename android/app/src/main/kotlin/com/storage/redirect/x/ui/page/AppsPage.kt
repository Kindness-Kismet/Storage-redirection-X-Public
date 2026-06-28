package com.storage.redirect.x.ui.page

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.AppInfo
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.SharedUidRepository
import com.storage.redirect.x.data.repository.TemplateApplyMode
import com.storage.redirect.x.data.repository.TemplateRepository
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.data.service.SystemUser
import com.storage.redirect.x.ui.theme.LocalUiMode
import com.storage.redirect.x.ui.theme.UiMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SortMode { NAME, INSTALL_TIME }

private const val PREF_SHOW_SYSTEM_APPS = "apps_show_system"
private const val PREF_REDIRECTED_FIRST = "apps_redirected_first"
private const val PREF_SORT_MODE = "apps_sort_mode"

internal data class AppsPageData(
    val isLoading: Boolean,
    val filteredApps: List<AppInfo>,
    val listState: LazyListState,
    val icons: Map<String, Drawable>,
    val redirectedPackages: List<String>,
    val selectedApps: List<AppInfo>,
    val isBatchMode: Boolean,
    val searchQuery: String,
    val showSystemApps: Boolean,
    val redirectedFirst: Boolean,
    val isSortByName: Boolean,
    val systemUsers: List<SystemUser>,
    val currentUserIndex: Int,
    val templates: List<RedirectTemplate>,
    val selectedTemplate: RedirectTemplate?,
    val sharedPackages: List<String>,
    val selectedBatchCount: Int,
    val bottomInnerPadding: Dp,
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
        val bitmap = remember(drawable) {
            drawable.toBitmap(72, 72).asImageBitmap()
        }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(placeholderColor),
        )
    }
}

@Composable
fun AppsPage(
    onAppClick: (String, String, Int) -> Unit = { _, _, _ -> },
    configVersion: Int = 0,
    bottomInnerPadding: Dp = 0.dp,
    onMenuStateChange: (Boolean) -> Unit = {},
    onTemplatesClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val configRepo = remember { ConfigRepository() }
    val sharedUidRepo = remember { SharedUidRepository() }
    val templateRepo = remember { TemplateRepository() }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    val apps = remember { mutableStateListOf<AppInfo>() }
    val icons = remember { mutableStateMapOf<String, Drawable>() }
    var config by remember { mutableStateOf(RedirectConfig()) }
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
    var searchQuery by remember { mutableStateOf("") }
    val isBatchSelectionMode = selectedAppsForBatch.isNotEmpty()

    var showSystemApps by remember {
        mutableStateOf(AppPreferences.getBoolean(PREF_SHOW_SYSTEM_APPS, false))
    }
    var redirectedFirst by remember {
        mutableStateOf(AppPreferences.getBoolean(PREF_REDIRECTED_FIRST, true))
    }
    var sortMode by remember {
        val saved = AppPreferences.getString(PREF_SORT_MODE, SortMode.NAME.name)
        mutableStateOf(runCatching { SortMode.valueOf(saved) }.getOrDefault(SortMode.NAME))
    }

    var systemUsers by remember { mutableStateOf(listOf(SystemUser(0, "Owner"))) }
    var currentUserIndex by remember { mutableIntStateOf(0) }
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
                showRestoreRulesDialog.value,
        )
    }

    suspend fun loadApps() {
        isLoading = true
        systemUsers = RootService.getSystemUsers()
        sharedUidRepo.load()
        templates = templateRepo.loadTemplates()
        config = configRepo.load(systemUsers[currentUserIndex].id)

        val pm = context.packageManager
        val selfPackage = context.packageName
        val (appList, installed) = withContext(Dispatchers.IO) {
            val rawList = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != selfPackage }
            val mapped = rawList.map { info ->
                val installTime = try {
                    pm.getPackageInfo(info.packageName, 0).firstInstallTime
                } catch (_: Exception) {
                    0L
                }
                AppInfo(
                    packageName = info.packageName,
                    appName = info.loadLabel(pm).toString(),
                    isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    firstInstallTime = installTime,
                )
            }.sortedBy { it.appName.lowercase() }
            mapped to rawList
        }

        apps.clear()
        apps.addAll(appList)
        isLoading = false

        for (batch in installed.chunked(20)) {
            val batchIcons = withContext(Dispatchers.IO) {
                batch.associate { it.packageName to it.loadIcon(pm) }
            }
            icons.putAll(batchIcons)
        }
    }

    suspend fun reloadAppsAndBackToFirstRow() {
        loadApps()
        appListState.scrollToItem(0)
    }

    LaunchedEffect(Unit) {
        loadApps()
    }

    LaunchedEffect(configVersion) {
        if (configVersion > 0) {
            config = configRepo.load(systemUsers[currentUserIndex].id)
            templates = templateRepo.loadTemplates()
        }
    }

    fun reloadConfig(userIndex: Int) {
        scope.launch {
            currentUserIndex = userIndex
            config = configRepo.load(systemUsers[userIndex].id)
        }
    }

    fun saveConfigForTemplate(newConfig: RedirectConfig, affectedPackages: Set<String>, onSaved: (Boolean) -> Unit) {
        val previousConfig = config
        config = newConfig
        pendingTemplateSaveJob?.cancel()
        pendingTemplateSaveJob = scope.launch {
            val saved = configRepo.saveAppConfigs(newConfig, affectedPackages)
            if (!saved) {
                config = previousConfig
            }
            onSaved(saved)
        }
    }

    fun requestTemplateApply(template: RedirectTemplate, mode: TemplateApplyMode) {
        val targetApps = selectedAppsForBatch.toList()
        if (targetApps.isEmpty()) return

        selectedTemplateForApply = template
        pendingApplyMode = mode
        val sharedPackages = targetApps
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
            val result = templateRepo.applyTemplate(nextConfig, targetApp.packageName, emptyList(), template, mode)
            nextConfig = result.config
            affectedPackages.addAll(result.affectedPackages)
        }
        selectedAppsForBatch.clear()
        selectedTemplateForApply = null
        pendingApplyMode = null
        saveConfigForTemplate(nextConfig, affectedPackages) { saved ->
            Toast.makeText(
                context,
                if (saved) R.string.apps_template_apply_success else R.string.rule_config_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
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
                if (saved) R.string.apps_restore_rules_success else R.string.rule_config_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
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
            val result = templateRepo.applyTemplate(nextConfig, targetApp.packageName, sharedPackages, template, mode)
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
                if (saved) R.string.apps_template_apply_success else R.string.rule_config_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val filteredApps = remember(
        apps.toList(),
        searchQuery,
        showSystemApps,
        redirectedFirst,
        sortMode,
        config.redirectApps,
    ) {
        var list = apps.toList()
        if (!showSystemApps) {
            list = list.filterNot { it.isSystemApp }
        }
        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
        list = when (sortMode) {
            SortMode.NAME -> list.sortedBy { it.appName.lowercase() }
            SortMode.INSTALL_TIME -> list.sortedByDescending { it.firstInstallTime }
        }
        if (redirectedFirst) {
            val (redirected, others) = list.partition { config.redirectApps.contains(it.packageName) }
            redirected + others
        } else {
            list
        }
    }

    val data = AppsPageData(
        isLoading = isLoading,
        filteredApps = filteredApps,
        listState = appListState,
        icons = icons,
        redirectedPackages = config.redirectApps,
        selectedApps = selectedAppsForBatch,
        isBatchMode = isBatchSelectionMode,
        searchQuery = searchQuery,
        showSystemApps = showSystemApps,
        redirectedFirst = redirectedFirst,
        isSortByName = sortMode == SortMode.NAME,
        systemUsers = systemUsers,
        currentUserIndex = currentUserIndex,
        templates = templates,
        selectedTemplate = selectedTemplateForApply,
        sharedPackages = sharedPackagesForSelectedApp,
        selectedBatchCount = selectedAppsForBatch.size,
        bottomInnerPadding = bottomInnerPadding,
    )
    val dialogs = AppsPageDialogState(
        showUserMenu = showUserMenu,
        showFilterMenu = showFilterMenu,
        showTemplatePicker = showTemplatePicker,
        showApplyModeDialog = showApplyModeDialog,
        showSharedUidConfirmDialog = showSharedUidConfirmDialog,
        showRestoreRulesDialog = showRestoreRulesDialog,
    )
    val actions = AppsPageActions(
        onSearchChange = { searchQuery = it },
        onRefresh = {
            searchQuery = ""
            scope.launch { reloadAppsAndBackToFirstRow() }
        },
        onUserSelected = { index ->
            showUserMenu.value = false
            if (index != currentUserIndex) reloadConfig(index)
        },
        onSortByName = {
            showFilterMenu.value = false
            if (sortMode != SortMode.NAME) {
                sortMode = SortMode.NAME
                AppPreferences.put(PREF_SORT_MODE, SortMode.NAME.name)
                scope.launch { reloadAppsAndBackToFirstRow() }
            }
        },
        onSortByInstallTime = {
            showFilterMenu.value = false
            if (sortMode != SortMode.INSTALL_TIME) {
                sortMode = SortMode.INSTALL_TIME
                AppPreferences.put(PREF_SORT_MODE, SortMode.INSTALL_TIME.name)
                scope.launch { reloadAppsAndBackToFirstRow() }
            }
        },
        onToggleSystemApps = {
            showSystemApps = !showSystemApps
            AppPreferences.put(PREF_SHOW_SYSTEM_APPS, showSystemApps)
            showFilterMenu.value = false
        },
        onToggleRedirectedFirst = {
            redirectedFirst = !redirectedFirst
            AppPreferences.put(PREF_REDIRECTED_FIRST, redirectedFirst)
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
            selectedAppsForBatch.clear()
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

    when (LocalUiMode.current) {
        UiMode.Material -> AppsPageMaterial(data = data, dialogs = dialogs, actions = actions)
        UiMode.Miuix -> AppsPageMiuix(data = data, dialogs = dialogs, actions = actions)
    }
}
