package com.storage.redirect.x.ui.page

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.*
import com.storage.redirect.x.ui.component.*
import com.storage.redirect.x.ui.theme.StatusColors
import com.storage.redirect.x.ui.util.sheetHeightTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FileMonitorPage(
    monitorViewModel: FileMonitorViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val state by monitorViewModel.uiState.collectAsStateWithLifecycle()
    val names = remember { mutableStateMapOf<String, String>() }
    val icons = remember { mutableStateMapOf<String, Drawable?>() }
    LaunchedEffect(state.entries) {
        val packages = state.entries.map { it.subjectPackage }.distinct().filter { it !in names }
        val metadata =
            withContext(Dispatchers.IO) {
                packages.associateWith { pkg ->
                    runCatching {
                            context.packageManager.getApplicationInfo(pkg, 0).let {
                                it.loadLabel(context.packageManager).toString() to
                                    it.loadIcon(context.packageManager)
                            }
                        }
                        .getOrDefault(pkg to null)
                }
            }
        metadata.forEach { (pkg, meta) ->
            names[pkg] = meta.first
            icons[pkg] = meta.second
        }
    }
    val filters =
        listOf(
            stringResource(R.string.monitor_all),
            stringResource(R.string.file_monitor_action_open),
            stringResource(R.string.file_monitor_action_create),
            stringResource(R.string.file_monitor_action_delete),
            stringResource(R.string.file_monitor_action_rename),
        )
    val selectFilter by rememberUpdatedState(monitorViewModel::updateOpTypeIndex)
    val filterEntry =
        remember(filters, state.opTypeIndex) {
            DropdownEntry(
                filters.mapIndexed { index, label ->
                    DropdownItem(
                        text = label,
                        selected = index == state.opTypeIndex,
                        onClick = { selectFilter(index) },
                    )
                }
            )
        }
    val filtered =
        remember(state.entries, state.searchQuery, state.opTypeIndex, names.toMap()) {
            state.entries.filter { entry ->
                (state.opTypeIndex == 0 ||
                    entry.actionType == MonitorActionType.entries[state.opTypeIndex - 1]) &&
                    (state.searchQuery.isBlank() ||
                        listOf(
                                entry.path,
                                entry.packageName,
                                entry.subjectPackage,
                                names[entry.subjectPackage].orEmpty(),
                            )
                            .any { it.contains(state.searchQuery, true) })
            }
        }
    SearchScreenScaffold(
        title = stringResource(R.string.nav_monitor),
        label = stringResource(R.string.file_monitor_search_hint),
        query = state.searchQuery,
        onQueryChange = monitorViewModel::updateSearchQuery,
        isResultEmpty = filtered.isEmpty(),
        bottomPadding = bottomInnerPadding,
        modifier = Modifier.testTag("page_monitor"),
        isRefreshing = state.isLoading,
        onRefresh = monitorViewModel::refresh,
        emptyResult = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.file_monitor_empty))
            }
        },
        actions = {
            ActionButton(
                MiuixIcons.Refresh,
                stringResource(R.string.file_monitor_refresh),
                enabled = !state.isLoading,
                onClick = monitorViewModel::refresh,
            )
            OverlayIconDropdownMenu(
                entry = filterEntry,
                modifier = Modifier.padding(horizontal = 4.dp).testTag("monitor_filter_menu"),
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                onExpandedChange = monitorViewModel::setOpTypeMenuVisible,
            ) {
                Icon(MiuixIcons.Tune, stringResource(R.string.apps_filter), Modifier.size(20.dp))
            }
        },
    ) {
        item { SmallTitle("${filters[state.opTypeIndex]}  ${filtered.size}") }
        if (state.isLoading && filtered.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (filtered.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.file_monitor_empty))
                }
            }
        }
        itemsIndexed(
            filtered,
            key = { index, entry -> "$index:${entry.timestamp}:${entry.path}" },
            contentType = { _, _ -> "monitor" },
        ) { _, entry ->
            MonitorEntryCard(
                entry,
                names[entry.subjectPackage] ?: entry.subjectPackage,
                icons[entry.subjectPackage],
            )
        }
    }
}

@Composable
private fun MonitorEntryCard(entry: FileMonitorEntry, appName: String, icon: Drawable?) {
    var expanded by rememberSaveable(entry.timestamp, entry.path) { mutableStateOf(false) }
    val action =
        stringResource(
            when (entry.actionType) {
                MonitorActionType.OPEN -> R.string.file_monitor_action_open
                MonitorActionType.CREATE -> R.string.file_monitor_action_create
                MonitorActionType.DELETE -> R.string.file_monitor_action_delete
                MonitorActionType.RENAME -> R.string.file_monitor_action_rename
            }
        )
    val source =
        stringResource(
            if (entry.isDelegatedBySystemWriter)
                when (entry.delegatedWriterType) {
                    MonitorWriterType.MEDIA -> R.string.file_monitor_source_media_action
                    MonitorWriterType.DOWNLOAD -> R.string.file_monitor_source_download_action
                    MonitorWriterType.MTP -> R.string.file_monitor_source_mtp_action
                    MonitorWriterType.SYSTEM -> R.string.file_monitor_source_system_writer_action
                    MonitorWriterType.NONE -> R.string.file_monitor_source_filesystem_action
                }
            else R.string.file_monitor_source_filesystem_action,
            action,
        )
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.fillMaxWidth().sheetHeightTransition().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, Modifier.size(32.dp), MiuixTheme.colorScheme.secondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    appName,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.timestamp.substringAfter(' ').take(8),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                ActionButton(
                    if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                    stringResource(
                        if (expanded) R.string.file_monitor_collapse
                        else R.string.file_monitor_expand
                    ),
                    onClick = { expanded = !expanded },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    source,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(
                    stringResource(
                        if (entry.isSuccess) R.string.file_monitor_result_success
                        else R.string.file_monitor_result_failed
                    ),
                    if (entry.isSuccess) StatusColors.healthy else StatusColors.danger,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                entry.displayPath,
                style = MiuixTheme.textStyles.body2,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
