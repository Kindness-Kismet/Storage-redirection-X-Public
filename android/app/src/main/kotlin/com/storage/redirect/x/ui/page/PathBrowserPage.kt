package com.storage.redirect.x.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.repository.PathBrowserRepository
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
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
            val items = if (packageName != null) {
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
                errorMessage = resolvePathBrowserError(
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

    Scaffold(
        bottomBar = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PATH_BROWSER_DIVIDER_HEIGHT)
                        .background(
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                                .copy(alpha = 0.2f)
                        )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = PATH_BROWSER_BOTTOM_BAR_START_PADDING,
                            end = PATH_BROWSER_BOTTOM_BAR_END_PADDING,
                            top = PATH_BROWSER_BOTTOM_BAR_VERTICAL_PADDING,
                            bottom = PATH_BROWSER_BOTTOM_BAR_VERTICAL_PADDING
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (currentPath.isEmpty()) {
                            onBack()
                        } else {
                            val parent = currentPath.substringBeforeLast('/', "")
                            currentPath = parent
                            loadDirectories(parent)
                        }
                    }) {
                        Icon(MiuixIcons.Back, contentDescription = null)
                    }
                    Text(
                        text = buildString {
                            append("/storage/emulated/")
                            append(userId)
                            if (packageName != null) {
                                append("/Android/data/")
                                append(packageName)
                                append("/sdcard")
                            }
                            if (currentPath.isNotEmpty()) {
                                append("/")
                                append(currentPath)
                            }
                        },
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = PATH_BROWSER_PATH_HORIZONTAL_PADDING),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = PATH_BROWSER_LOADING_TOP_SPACING)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (errorMessage.isNotEmpty()) {
                        item {
                            Text(
                                text = errorMessage,
                                color = MiuixTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.body2,
                                modifier = Modifier.padding(
                                    horizontal = PATH_BROWSER_ITEM_HORIZONTAL_PADDING,
                                    vertical = PATH_BROWSER_ITEM_TEXT_VERTICAL_PADDING
                                )
                            )
                        }
                    }

                    if (directories.isEmpty() && errorMessage.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.rule_config_browser_empty),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body2,
                                modifier = Modifier.padding(
                                    horizontal = PATH_BROWSER_ITEM_HORIZONTAL_PADDING,
                                    vertical = PATH_BROWSER_ITEM_TEXT_VERTICAL_PADDING
                                )
                            )
                        }
                    }

                    itemsIndexed(directories) { index, fullPath ->
                        val name = fullPath.substringAfterLast('/')
                        val isDisabled = fullPath.equals("Android/data", ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isDisabled) Modifier
                                    else Modifier.clickable { navigateTo(fullPath) }
                                )
                                .padding(
                                    horizontal = PATH_BROWSER_ITEM_HORIZONTAL_PADDING,
                                    vertical = PATH_BROWSER_ITEM_ROW_VERTICAL_PADDING
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                style = MiuixTheme.textStyles.body1,
                                color = if (isDisabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
                                else MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!isDisabled) {
                                Icon(MiuixIcons.ChevronForward, contentDescription = null)
                            }
                        }
                        if (index < directories.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PATH_BROWSER_ITEM_HORIZONTAL_PADDING)
                                    .height(PATH_BROWSER_DIVIDER_HEIGHT)
                                    .background(
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            .copy(alpha = 0.1f)
                                    )
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = PATH_BROWSER_FAB_MARGIN, bottom = PATH_BROWSER_FAB_MARGIN)
                    .clip(RoundedCornerShape(PATH_BROWSER_FAB_CORNER_RADIUS))
                    .background(MiuixTheme.colorScheme.primary)
                    .clickable { onPathSelect(currentPath) }
                    .padding(PATH_BROWSER_FAB_CONTENT_PADDING),
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
            }
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
