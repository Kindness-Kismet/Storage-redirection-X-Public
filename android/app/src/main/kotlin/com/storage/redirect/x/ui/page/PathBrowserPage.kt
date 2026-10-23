package com.storage.redirect.x.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.storage.redirect.x.data.repository.PathBrowserRepository
import com.storage.redirect.x.ui.component.CardItem
import com.storage.redirect.x.ui.component.SrxLazyColumn as LazyColumn
import com.storage.redirect.x.ui.component.SrxScaffold as Scaffold
import com.storage.redirect.x.ui.component.groupedCardItems
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面间距：底栏内边距，列表项边距，分隔线高度，加载占位与确认按钮边距
private val PATH_BROWSER_DIVIDER_HEIGHT = 0.5.dp
private val PATH_BROWSER_BOTTOM_BAR_START_PADDING = 4.dp
private val PATH_BROWSER_BOTTOM_BAR_END_PADDING = 12.dp
private val PATH_BROWSER_BOTTOM_BAR_VERTICAL_PADDING = 8.dp
private val PATH_BROWSER_PATH_HORIZONTAL_PADDING = 4.dp
private val PATH_BROWSER_LOADING_TOP_SPACING = 48.dp
private val PATH_BROWSER_ITEM_HORIZONTAL_PADDING = 16.dp
private val PATH_BROWSER_ITEM_TEXT_VERTICAL_PADDING = 12.dp
private val PATH_BROWSER_ITEM_ROW_VERTICAL_PADDING = 14.dp
private val PATH_BROWSER_FAB_MARGIN = 16.dp
private val PATH_BROWSER_FAB_CORNER_RADIUS = 16.dp
private val PATH_BROWSER_FAB_CONTENT_PADDING = 16.dp

@Composable
fun PathBrowserPage(
    title: String,
    browserRepository: PathBrowserRepository,
    userId: Int,
    packageName: String? = null,
    onBack: () -> Unit,
    onPathSelect: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("") }
    var directories by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }

    val loadFailedText = stringResource(R.string.rule_config_browser_load_failed)
    val emptyPathText = stringResource(R.string.rule_config_error_empty)
    val absolutePathText = stringResource(R.string.rule_config_error_absolute)
    val traversalPathText = stringResource(R.string.rule_config_error_traversal)
    val androidPrefixText = stringResource(R.string.rule_config_error_android_prefix)

    fun loadDirectories(path: String) {
        scope.launch {
            isLoading = true
            val items =
                if (packageName != null) {
                    browserRepository.listAppPrivateDirectories(userId, packageName, path)
                } else {
                    browserRepository.listRelativeDirectories(userId, path)
                }
            if (items == null) {
                errorMessage = loadFailedText
                directories = emptyList()
            } else {
                errorMessage = ""
                directories = items
            }
            isLoading = false
        }
    }

    fun navigateTo(path: String) {
        if (packageName != null) {
            val trimmed = path.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.contains("..")) {
                errorMessage = loadFailedText
                return
            }
            currentPath = trimmed
            loadDirectories(currentPath)
        } else {
            val validation = RedirectConfig.validateAllowedPath(path)
            if (validation !is PathValidationResult.Valid) {
                errorMessage =
                    resolvePathBrowserError(
                        result = validation,
                        emptyPathText = emptyPathText,
                        absolutePathText = absolutePathText,
                        traversalPathText = traversalPathText,
                        androidPrefixText = androidPrefixText,
                        fallbackText = loadFailedText,
                    )
                return
            }
            currentPath = validation.normalized
            loadDirectories(currentPath)
        }
    }

    LaunchedEffect(userId) {
        loadDirectories(currentPath)
    }

    BackHandler {
        if (currentPath.isEmpty()) {
            onBack()
        } else {
            val parent = currentPath.substringBeforeLast('/', "")
            currentPath = parent
            loadDirectories(parent)
        }
    }

    val navigateBack: () -> Unit = {
        if (currentPath.isEmpty()) onBack()
        else {
            currentPath = currentPath.substringBeforeLast('/', "")
            loadDirectories(currentPath)
        }
    }
    Scaffold(
        floatingActionButton = {
            if (!isLoading && currentPath.isNotEmpty()) {
                top.yukonga.miuix.kmp.basic.FloatingActionButton(
                    onClick = { onPathSelect(currentPath) },
                    modifier = Modifier.navigationBarsPadding().testTag("directory_confirm"),
                ) {
                    top.yukonga.miuix.kmp.basic.Icon(
                        MiuixIcons.Basic.Check,
                        stringResource(R.string.common_ok),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
        topBar = {
            com.storage.redirect.x.ui.component.SrxSmallTopAppBar(
                title = title,
                navigationIcon = { com.storage.redirect.x.ui.component.BackButton(navigateBack) },
            )
        },
        bottomBar = {
            Text(
                text =
                    "/storage/emulated/$userId" +
                        (if (packageName != null) "/Android/data/$packageName/sdcard" else "") +
                        (if (currentPath.isNotEmpty()) "/$currentPath" else ""),
                style = MiuixTheme.textStyles.body2,
                modifier =
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding =
                PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                )
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            if (isLoading)
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            else if (errorMessage.isNotEmpty() || directories.isEmpty())
                item {
                    Text(
                        text =
                            errorMessage.ifEmpty {
                                stringResource(R.string.rule_config_browser_empty)
                            },
                        color =
                            if (errorMessage.isEmpty())
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            else MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            groupedCardItems(
                "directories",
                directories.map { fullPath ->
                    val disabled = fullPath.equals("Android/data", ignoreCase = true)
                    CardItem(fullPath) {
                        top.yukonga.miuix.kmp.preference.ArrowPreference(
                            title = fullPath.substringAfterLast('/'),
                            enabled = !disabled,
                            onClick = { navigateTo(fullPath) },
                        )
                    }
                },
            )
        }
    }
}

private fun resolvePathBrowserError(
    result: PathValidationResult,
    emptyPathText: String,
    absolutePathText: String,
    traversalPathText: String,
    androidPrefixText: String,
    fallbackText: String,
): String {
    return when (result) {
        PathValidationResult.Empty -> emptyPathText
        PathValidationResult.Absolute -> absolutePathText
        PathValidationResult.Traversal -> traversalPathText
        PathValidationResult.AndroidDataPath -> androidPrefixText
        PathValidationResult.WildcardNotAllowed -> fallbackText
        PathValidationResult.ExclusionNotAllowed -> fallbackText
        is PathValidationResult.Valid -> fallbackText
    }
}
