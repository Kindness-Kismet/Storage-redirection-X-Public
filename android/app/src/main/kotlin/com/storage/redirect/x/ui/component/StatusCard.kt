package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.theme.RunState
import com.storage.redirect.x.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class ModuleStatus {
    LOADING,
    INSTALLED,
    NOT_INSTALLED,
    ERROR,
}

// Same two-column status geometry as Mishka's StatusSection.
@Composable
fun HomeStatusCards(status: ModuleStatus, version: String, apps: String, operations: String) {
    val state =
        when (status) {
            ModuleStatus.INSTALLED -> RunState.Running
            ModuleStatus.LOADING -> RunState.Pending
            else -> RunState.Stopped
        }
    val title =
        stringResource(
            when (status) {
                ModuleStatus.INSTALLED -> R.string.home_module_installed
                ModuleStatus.LOADING -> R.string.common_loading
                ModuleStatus.NOT_INSTALLED -> R.string.home_module_not_installed
                ModuleStatus.ERROR -> R.string.common_error
            }
        )
    val statusIcon =
        if (status == ModuleStatus.INSTALLED) Icons.Rounded.CheckCircleOutline
        else Icons.Rounded.RemoveCircleOutline
    val tint = StatusColors.runState(state)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp)
            .height(IntrinsicSize.Min)
            .testTag("home_status"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            Modifier.weight(1f).fillMaxHeight().testTag("home_module_status"),
            colors = CardDefaults.defaultColors(color = StatusColors.runStateContainer(state)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().offset(38.dp, 45.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(statusIcon, null, Modifier.size(170.dp), tint = tint)
                }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = tint)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "v$version",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MetricCard(
                apps,
                stringResource(R.string.home_redirected_apps),
                Modifier.fillMaxWidth().weight(1f).testTag("home_redirected_apps"),
            )
            Spacer(Modifier.height(12.dp))
            MetricCard(
                operations,
                stringResource(R.string.home_redirect_count),
                Modifier.fillMaxWidth().weight(1f).testTag("home_redirect_count"),
            )
        }
    }
}

@Composable
fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier, insideMargin = PaddingValues(16.dp)) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(
            value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}
