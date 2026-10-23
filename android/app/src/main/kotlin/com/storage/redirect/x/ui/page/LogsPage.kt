package com.storage.redirect.x.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.repository.LogRepository
import com.storage.redirect.x.ui.component.*
import com.storage.redirect.x.ui.theme.StatusColors
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LogsPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val repo = remember { LogRepository() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var lines by remember { mutableStateOf(persistentListOf<String>()) }
    var query by remember { mutableStateOf("") }
    fun refresh() {
        scope.launch {
            loading = true
            lines = repo.load(500).orEmpty().lines().filter { it.isNotBlank() }.toPersistentList()
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            uri ->
            if (uri != null)
                scope.launch {
                    val success =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                requireNotNull(context.contentResolver.openOutputStream(uri, "w"))
                                    .use { it.write(lines.joinToString("\n").toByteArray()) }
                            }
                                .isSuccess
                        }
                    Toast.makeText(
                            context,
                            if (success) R.string.logs_exported else R.string.logs_export_failed,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
        }
    val filtered =
        remember(query, lines) { lines.withIndex().filter { it.value.contains(query, true) } }
    SearchScreenScaffold(
        title = stringResource(R.string.logs_title),
        label = stringResource(R.string.logs_enter_keyword),
        query = query,
        onQueryChange = { query = it },
        isResultEmpty = filtered.isEmpty(),
        bottomPadding = 0.dp,
        navigationIcon = { BackButton(onBack) },
        isRefreshing = loading,
        onRefresh = ::refresh,
        emptyResult = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.logs_empty))
            }
        },
        actions = {
            ActionButton(
                MiuixIcons.Refresh,
                stringResource(R.string.apps_refresh),
                enabled = !loading,
                onClick = ::refresh,
            )
            ActionButton(
                MiuixIcons.UploadCloud,
                stringResource(R.string.logs_export),
                enabled = lines.isNotEmpty(),
                onClick = { export.launch("storage_redirect_x_logs.txt") },
            )
        },
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        if (filtered.isEmpty())
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator()
                    else Text(stringResource(R.string.logs_empty))
                }
            }
        itemsIndexed(filtered, key = { _, line -> line.index }, contentType = { _, _ -> "log" }) {
            _,
            line ->
            val color =
                when {
                    line.value.contains("error", true) -> StatusColors.danger
                    line.value.contains("warn", true) -> StatusColors.warning
                    else -> MiuixTheme.colorScheme.primary
                }
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                onClick = {
                    (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(
                        ClipData.newPlainText("log", line.value)
                    )
                    Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
                },
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    StatusBadge("#${line.index + 1}", color)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        line.value,
                        style = MiuixTheme.textStyles.body2,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
