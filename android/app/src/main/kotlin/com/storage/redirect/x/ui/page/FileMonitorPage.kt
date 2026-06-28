package com.storage.redirect.x.ui.page

import android.graphics.drawable.Drawable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.FileMonitorEntry
import com.storage.redirect.x.data.model.MonitorActionType
import com.storage.redirect.x.data.model.MonitorResultState
import com.storage.redirect.x.data.model.MonitorWriterType
import com.storage.redirect.x.ui.component.SRX_TOP_BAR_TRAILING_ICON_END_PADDING
import com.storage.redirect.x.ui.component.SrxTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面间距：顶栏下留白，列表边距，卡片内边距，标签与正文间距
private val FILE_MONITOR_PAGE_TOP_SPACING = 6.dp
private val FILE_MONITOR_LIST_HORIZONTAL_PADDING = 16.dp
private val FILE_MONITOR_LIST_VERTICAL_PADDING = 12.dp
private val FILE_MONITOR_LIST_ITEM_SPACING = 12.dp
private val FILE_MONITOR_CARD_CONTENT_PADDING = 12.dp
private val FILE_MONITOR_ROW_SPACING = 8.dp
private val FILE_MONITOR_BADGE_CORNER_RADIUS = 4.dp
private val FILE_MONITOR_BADGE_HORIZONTAL_PADDING = 6.dp
private val FILE_MONITOR_BADGE_VERTICAL_PADDING = 2.dp
private val FILE_MONITOR_HEADER_META_SPACING = 6.dp
private val FILE_MONITOR_SOURCE_TOP_SPACING = 6.dp
private val FILE_MONITOR_PATH_TOP_SPACING = 4.dp

@Composable
fun FileMonitorPage(
    monitorViewModel: FileMonitorViewModel = viewModel(),
    bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    val uiState by monitorViewModel.uiState.collectAsState()

    // 每次进入页面时自动刷新
    LaunchedEffect(Unit) {
        monitorViewModel.refresh()
    }

    // 页面离开时重置搜索状态
    DisposableEffect(Unit) {
        onDispose {
            monitorViewModel.resetFilters()
        }
    }

    // 仅缓存应用名和图标，避免重复查询 PackageManager
    val appNames = remember { mutableStateMapOf<String, String>() }
    val appIcons = remember { mutableStateMapOf<String, Drawable?>() }

    val entries = uiState.entries
    val searchQuery = uiState.searchQuery
    val isLoading = uiState.isLoading

    LaunchedEffect(entries) {
        val unresolvedPackages = entries
            .map { it.subjectPackage }
            .filter { it.isNotBlank() && it != "-" }
            .distinct()
            .filter { !appNames.containsKey(it) || !appIcons.containsKey(it) }

        if (unresolvedPackages.isEmpty()) {
            return@LaunchedEffect
        }

        val pm = context.packageManager
        val resolved = withContext(Dispatchers.IO) {
            unresolvedPackages.associateWith { packageName ->
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val appName = appInfo.loadLabel(pm).toString()
                    appName to appInfo.loadIcon(pm)
                } catch (_: Exception) {
                    packageName to null
                }
            }
        }

        resolved.forEach { (packageName, appMeta) ->
            appNames[packageName] = appMeta.first
            appIcons[packageName] = appMeta.second
        }
    }

    // 搜索支持：包名、应用名、路径
    val filteredEntries = remember(entries, searchQuery, appNames.toMap()) {
        if (searchQuery.isBlank()) {
            entries
        } else {
            entries.filter { entry ->
                val subjectPkg = entry.subjectPackage
                val appName = appNames[subjectPkg] ?: ""
                entry.subjectPackage.contains(searchQuery, ignoreCase = true) ||
                    entry.packageName.contains(searchQuery, ignoreCase = true) ||
                    entry.path.contains(searchQuery, ignoreCase = true) ||
                    appName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("page_monitor"),
    ) {
        SrxTopAppBar(
            title = stringResource(R.string.file_monitor_title),
            actions = {
                IconButton(
                    onClick = { monitorViewModel.refresh() },
                    modifier = Modifier.padding(end = SRX_TOP_BAR_TRAILING_ICON_END_PADDING)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.file_monitor_refresh))
                }
            }
        )

        Spacer(Modifier.height(FILE_MONITOR_PAGE_TOP_SPACING))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.file_monitor_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            SearchBar(
                inputField = {
                    InputField(
                        query = searchQuery,
                        onQueryChange = { monitorViewModel.updateSearchQuery(it) },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = stringResource(R.string.file_monitor_search_hint)
                    )
                },
                onExpandedChange = {},
                modifier = Modifier.fillMaxWidth(),
            ) {}

            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.file_monitor_empty),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = FILE_MONITOR_LIST_HORIZONTAL_PADDING),
                    contentPadding = PaddingValues(
                        top = FILE_MONITOR_LIST_VERTICAL_PADDING,
                        bottom = FILE_MONITOR_LIST_VERTICAL_PADDING + bottomInnerPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(FILE_MONITOR_LIST_ITEM_SPACING)
                ) {
                    items(filteredEntries) { entry ->
                        val subjectPackage = entry.subjectPackage
                        MonitorEntryCard(
                            entry = entry,
                            appName = appNames[subjectPackage] ?: subjectPackage,
                            appIcon = appIcons[subjectPackage]
                        )
                    }
                }
            }
        }
    }
}

// 文件监视条目卡片
@Composable
private fun MonitorEntryCard(
    entry: FileMonitorEntry,
    appName: String,
    appIcon: Drawable?
) {
    val colorScheme = MiuixTheme.colorScheme
    val actionLabel = when (entry.actionType) {
        MonitorActionType.OPEN -> stringResource(R.string.file_monitor_action_open)
        MonitorActionType.CREATE -> stringResource(R.string.file_monitor_action_create)
        MonitorActionType.DELETE -> stringResource(R.string.file_monitor_action_delete)
        MonitorActionType.RENAME -> stringResource(R.string.file_monitor_action_rename)
    }
    val sourceLabel = if (entry.isDelegatedBySystemWriter) {
        when (entry.delegatedWriterType) {
            MonitorWriterType.MEDIA -> stringResource(R.string.file_monitor_source_media_action, actionLabel)
            MonitorWriterType.DOWNLOAD -> stringResource(R.string.file_monitor_source_download_action, actionLabel)
            MonitorWriterType.MTP -> stringResource(R.string.file_monitor_source_mtp_action, actionLabel)
            MonitorWriterType.SYSTEM -> stringResource(R.string.file_monitor_source_system_writer_action, actionLabel)
            MonitorWriterType.NONE -> stringResource(R.string.file_monitor_source_filesystem_action, actionLabel)
        }
    } else {
        stringResource(R.string.file_monitor_source_filesystem_action, actionLabel)
    }
    val resultInfo = when (entry.resultState) {
        MonitorResultState.SUCCESS -> ResultInfo(
            label = stringResource(R.string.file_monitor_result_success),
            containerColor = colorScheme.primaryContainer,
            textColor = colorScheme.onPrimaryContainer
        )

        MonitorResultState.FAILED -> ResultInfo(
            label = stringResource(R.string.file_monitor_result_failed),
            containerColor = colorScheme.errorContainer,
            textColor = colorScheme.onErrorContainer
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        var isExpanded by rememberSaveable(entry.timestamp, entry.path) { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FILE_MONITOR_CARD_CONTENT_PADDING),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                MonitorAppIcon(drawable = appIcon, modifier = Modifier.size(24.dp))

                Spacer(Modifier.width(FILE_MONITOR_ROW_SPACING))

                Text(
                    text = appName,
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(FILE_MONITOR_ROW_SPACING))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(FILE_MONITOR_BADGE_CORNER_RADIUS))
                            .background(resultInfo.containerColor)
                            .padding(
                                horizontal = FILE_MONITOR_BADGE_HORIZONTAL_PADDING,
                                vertical = FILE_MONITOR_BADGE_VERTICAL_PADDING
                            )
                    ) {
                        Text(
                            text = resultInfo.label,
                            fontWeight = FontWeight.W600,
                            color = resultInfo.textColor,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(Modifier.width(FILE_MONITOR_HEADER_META_SPACING))

                    Text(
                        text = formatTimestamp(entry.timestamp),
                        fontSize = 10.sp
                    )

                    Spacer(Modifier.width(FILE_MONITOR_HEADER_META_SPACING))

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = stringResource(
                                if (isExpanded) R.string.file_monitor_collapse else R.string.file_monitor_expand
                            ),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(FILE_MONITOR_SOURCE_TOP_SPACING))

            Text(
                text = sourceLabel,
                style = MiuixTheme.textStyles.body2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(FILE_MONITOR_PATH_TOP_SPACING))

            Text(
                text = entry.displayPath,
                style = MiuixTheme.textStyles.body2,
                fontFamily = FontFamily.Monospace,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis
            )
        }
    }
}

private data class ResultInfo(
    val label: String,
    val containerColor: Color,
    val textColor: Color
)

@Composable
private fun MonitorAppIcon(drawable: Drawable?, modifier: Modifier = Modifier) {
    if (drawable != null) {
        val bitmap = remember(drawable) {
            drawable.toBitmap(48, 48).asImageBitmap()
        }
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        )
    }
}

// 提取 HH:mm 时间部分
private fun formatTimestamp(timestamp: String): String {
    val parts = timestamp.split(" ")
    if (parts.size < 2) return timestamp
    val time = parts[1]
    return if (time.length >= 5) time.substring(0, 5) else time
}
