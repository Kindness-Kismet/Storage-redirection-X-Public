package com.storage.redirect.x.ui.page.cloudrules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.repository.CLOUD_RULE_REPOSITORY
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun CloudRuleEntry(state: CloudRuleState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.cloud_rules_title),
            summary = stringResource(R.string.cloud_rules_entry_summary),
            onClick = state::open,
            modifier = Modifier.testTag("cloud_rules_open"),
        )
    }
}

@Composable
fun CloudRuleDialog(
    state: CloudRuleState,
    packageName: String,
    onApply: (RedirectTemplate) -> Unit,
) {
    val snapshot = state.result?.snapshot
    val rule = snapshot?.catalog?.rulesByPackage?.get(packageName)
    var selectedPaths by remember(rule) { mutableStateOf(emptySet<String>()) }
    var selectedMappings by remember(rule) { mutableStateOf(emptySet<String>()) }
    var isBasicSelected by remember(rule) { mutableStateOf(false) }
    var acceptsMappingChange by remember(rule) { mutableStateOf(false) }
    val hasSelection = selectedPaths.isNotEmpty() || selectedMappings.isNotEmpty()
    val needsMappingConsent = rule?.warnings?.contains("direct_mapping") == true && hasSelection
    val canApply = !state.isLoading && rule != null &&
        (hasSelection || isBasicSelected) && (!needsMappingConsent || acceptsMappingChange)
    val uriHandler = LocalUriHandler.current
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()

    WindowDialog(
        show = state.isVisible,
        title = stringResource(R.string.cloud_rules_title),
        summary = packageName,
        onDismissRequest = state::close,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .heightIn(max = 400.dp).testTag("cloud_rules_content"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    CloudRuleStatus(state.result, state.isLoading)
                    if (state.isLoading) CircularProgressIndicator()
                }
                if (snapshot != null && rule == null) {
                    item { Text(stringResource(R.string.cloud_rules_not_found)) }
                }
                if (rule != null) {
                    item { Text(rule.descriptionFor(languageTag)) }
                    item { CloudRuleAttribution(rule) }
                    items(rule.warnings.sorted(), key = { "warning:$it" }) { code ->
                        Text(stringResource(cloudRuleWarningText(code)))
                    }
                    if (rule.canEnableBasicRedirect) {
                        item {
                            CheckboxPreference(
                                title = stringResource(R.string.cloud_rules_basic),
                                summary = stringResource(R.string.cloud_rules_basic_summary),
                                checked = isBasicSelected,
                                onCheckedChange = { isBasicSelected = it },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                    items(rule.allowedRealPaths, key = { "path:$it" }) { path ->
                        CheckboxPreference(
                            title = path,
                            summary = stringResource(R.string.rule_config_allowed_paths),
                            checked = path in selectedPaths,
                            onCheckedChange = { checked ->
                                selectedPaths = if (checked) selectedPaths + path else selectedPaths - path
                            },
                            enabled = !state.isLoading,
                        )
                    }
                    items(rule.pathMappings, key = { "mapping:${it.requestPath}" }) { mapping ->
                        CheckboxPreference(
                            title = "${mapping.requestPath} → ${mapping.finalPath}",
                            checked = mapping.requestPath in selectedMappings,
                            onCheckedChange = { checked ->
                                selectedMappings = if (checked) selectedMappings + mapping.requestPath
                                else selectedMappings - mapping.requestPath
                            },
                            enabled = !state.isLoading,
                        )
                    }
                    if (needsMappingConsent) {
                        item {
                            CheckboxPreference(
                                title = stringResource(R.string.cloud_rules_mapping_consent),
                                checked = acceptsMappingChange,
                                onCheckedChange = { acceptsMappingChange = it },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                    if (!rule.canEnableBasicRedirect && rule.allowedRealPaths.isEmpty() && rule.pathMappings.isEmpty()) {
                        item { Text(stringResource(R.string.cloud_rules_advice_only)) }
                    }
                    item { Text(stringResource(R.string.cloud_rules_apply_hint)) }
                    item {
                        TextButton(
                            text = stringResource(R.string.cloud_rules_view_rule),
                            onClick = { uriHandler.openUri("$CLOUD_RULE_REPOSITORY/blob/master/rules/$packageName.json") },
                        )
                    }
                }
            }
            TextButton(
                text = stringResource(R.string.cloud_rules_refresh),
                onClick = state::refresh,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth().testTag("cloud_rules_refresh"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = state::close,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.cloud_rules_apply),
                    enabled = canApply,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f).testTag("cloud_rules_apply"),
                    onClick = {
                        val selected = rule ?: return@TextButton
                        state.close()
                        onApply(selected.toTemplate(selectedPaths, selectedMappings))
                    },
                )
            }
        }
    }
}
