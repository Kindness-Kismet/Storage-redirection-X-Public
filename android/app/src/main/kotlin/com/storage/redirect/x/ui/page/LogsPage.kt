package com.storage.redirect.x.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.icon.AppIcons
import com.storage.redirect.x.data.repository.LogRepository
import com.storage.redirect.x.ui.component.PAGE_CONTENT_TOP_PADDING
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PAGE_ITEM_GAP
import com.storage.redirect.x.ui.component.PageMessageState
import com.storage.redirect.x.ui.component.PillButton
import com.storage.redirect.x.ui.component.SrxBackButton
import com.storage.redirect.x.ui.component.SrxScaffold
import com.storage.redirect.x.ui.component.SrxTopAppBar
import com.storage.redirect.x.ui.navigation.LocalPageReady
import com.storage.redirect.x.ui.util.topBarSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import com.storage.redirect.x.ui.component.SrxIconButton as IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val LOG_LINE_COUNT = 500
// 日志卡片：圆角与 miuix Card 一致，另含内边距与类型标记尺寸
private val LOGS_CARD_CORNER_RADIUS = 16.dp
private val LOGS_CARD_CONTENT_HORIZONTAL_PADDING = 12.dp
private val LOGS_CARD_CONTENT_VERTICAL_PADDING = 10.dp
private val LOGS_TYPE_INDICATOR_WIDTH = 4.dp
private val LOGS_TYPE_INDICATOR_HEIGHT = 14.dp
private val LOGS_TYPE_INDICATOR_CORNER_RADIUS = 2.dp
private val LOGS_TYPE_TEXT_SPACING = 8.dp

@Composable
fun LogsPage(onBack: () -> Unit, bottomInnerPadding: Dp = 0.dp) {
    val logRepo = remember { LogRepository() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    var isLoading by remember { mutableStateOf(true) }
    var hasLoadError by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf(emptyList<String>()) }
    var filterQuery by remember { mutableStateOf("") }
    val onReady = LocalPageReady.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                        stream.write(logLines.joinToString("\n").toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            val msgRes = if (success) R.string.logs_exported else R.string.logs_export_failed
            Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
        }
    }

    fun loadLogs() {
        scope.launch {
            isLoading = true
            val content = logRepo.load(LOG_LINE_COUNT)
            hasLoadError = content == null
            if (content != null) logLines = content.lines().filter { it.isNotBlank() }.reversed()
            isLoading = false
            onReady()
        }
    }

    LaunchedEffect(Unit) { loadLogs() }

    // 过滤后的日志
    val filteredLines = remember(logLines, filterQuery) {
        if (filterQuery.isBlank()) logLines
        else logLines.filter { it.contains(filterQuery, ignoreCase = true) }
    }

    // 有日志时过滤栏随顶栏固定，列表从两者下方滑过
    val isFilterVisible = !isLoading && logLines.isNotEmpty()
    SrxScaffold(
        topBar = {
            Column(Modifier.topBarSurface()) {
                SrxTopAppBar(
                    title = stringResource(R.string.logs_title),
                    applyBlur = false,
                    navigationIcon = { SrxBackButton(onClick = onBack) },
                    actions = {
                        IconButton(
                            onClick = {
                                val fileName = "srx_logs_${System.currentTimeMillis()}.log"
                                exportLauncher.launch(fileName)
                            },
                        ) {
                            Icon(AppIcons.Export, contentDescription = stringResource(R.string.logs_export))
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (logRepo.clear()) {
                                        loadLogs()
                                    }
                                }
                            },
                            enabled = !isLoading,
                        ) {
                            Icon(AppIcons.Delete, contentDescription = stringResource(R.string.logs_clear))
                        }
                        IconButton(onClick = { loadLogs() }) {
                            Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.logs_refresh))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (isFilterVisible) {
                    Spacer(Modifier.height(PAGE_CONTENT_TOP_PADDING))
                    // 关键字过滤搜索栏
                    SearchBar(
                        inputField = {
                            InputField(
                                query = filterQuery,
                                onQueryChange = { filterQuery = it },
                                onSearch = {},
                                expanded = false,
                                onExpandedChange = {},
                                label = stringResource(R.string.logs_filter_keyword)
                            )
                        },
                        onExpandedChange = {},
                        modifier = Modifier.fillMaxWidth(),
                    ) {}
                    Spacer(Modifier.height(10.dp))
                }
            }
        },
    ) { innerPadding ->
        val topPadding = innerPadding.calculateTopPadding()
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (hasLoadError || filteredLines.isEmpty()) {
            val hasFilter = filterQuery.isNotBlank()
            PageMessageState(
                icon = when {
                    hasLoadError -> AppIcons.Warning
                    hasFilter -> AppIcons.Search
                    else -> AppIcons.Document
                },
                title = stringResource(when {
                    hasLoadError -> R.string.logs_load_failed
                    hasFilter -> R.string.logs_no_match
                    else -> R.string.logs_empty
                }),
                message = stringResource(when {
                    hasLoadError -> R.string.logs_load_failed_summary
                    hasFilter -> R.string.logs_no_match_summary
                    else -> R.string.empty_logs_summary
                }),
                contentPadding = PaddingValues(top = topPadding, bottom = bottomInnerPadding),
                action = if (hasLoadError || hasFilter) {
                    {
                        PillButton(
                            text = stringResource(if (hasLoadError) R.string.common_retry else R.string.browser_clear_search),
                            onClick = { if (hasLoadError) loadLogs() else filterQuery = "" },
                        )
                    }
                } else null,
            )
        } else {
            // 日志列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = PAGE_HORIZONTAL_PADDING,
                    top = topPadding + PAGE_CONTENT_TOP_PADDING,
                    end = PAGE_HORIZONTAL_PADDING,
                    bottom = PAGE_CONTENT_TOP_PADDING + bottomInnerPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(PAGE_ITEM_GAP)
            ) {
                items(filteredLines) { line ->
                    LogLineCard(
                        content = line,
                        onCopy = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("log_line", line))
                            Toast.makeText(context, R.string.logs_copied_line, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

// 日志行卡片（点击复制单行）
@Composable
private fun LogLineCard(content: String, onCopy: () -> Unit) {
    val typeColor = getTypeColor(content)
    val colorScheme = MiuixTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LOGS_CARD_CORNER_RADIUS))
            .clickable(onClick = onCopy),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = LOGS_CARD_CONTENT_HORIZONTAL_PADDING,
                        vertical = LOGS_CARD_CONTENT_VERTICAL_PADDING
                    ),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .width(LOGS_TYPE_INDICATOR_WIDTH)
                        .height(LOGS_TYPE_INDICATOR_HEIGHT)
                        .clip(RoundedCornerShape(LOGS_TYPE_INDICATOR_CORNER_RADIUS))
                        .background(typeColor)
                )
                Spacer(Modifier.width(LOGS_TYPE_TEXT_SPACING))
                Text(
                    text = content,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun getTypeColor(line: String): Color {
    val colorScheme = MiuixTheme.colorScheme
    val upper = line.uppercase()
    return when {
        upper.contains("[ERROR]") || upper.contains("ERROR:") -> colorScheme.error
        upper.contains("[WARN]") || upper.contains("WARNING") -> Color(0xFFFF9800)
        upper.contains("[INFO]") || upper.contains("INFO:") -> colorScheme.primary
        upper.contains("[DEBUG]") || upper.contains("DEBUG:") -> colorScheme.secondary
        else -> colorScheme.onSurfaceVariantSummary
    }
}
