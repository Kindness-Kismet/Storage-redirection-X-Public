package com.storage.redirect.x.ui.page.cloudrules

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.CloudRule
import com.storage.redirect.x.data.repository.CloudRuleError
import com.storage.redirect.x.data.repository.CloudRuleResult
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun CloudRuleStatus(result: CloudRuleResult?, isLoading: Boolean) {
    if (isLoading) Text(stringResource(R.string.cloud_rules_loading))
    result?.snapshot?.let { snapshot ->
        Text(stringResource(
            R.string.cloud_rules_cached,
            snapshot.catalog.rulesByPackage.size,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(snapshot.fetchedAtMillis)),
        ))
    }
    result?.error?.let { error ->
        val message = when (error) {
            CloudRuleError.Network -> stringResource(R.string.cloud_rules_network_error)
            CloudRuleError.Http -> stringResource(R.string.cloud_rules_http_error, result.httpStatus ?: 0)
            CloudRuleError.InvalidData -> stringResource(R.string.cloud_rules_invalid_data)
            CloudRuleError.Storage -> stringResource(R.string.cloud_rules_storage_error)
        }
        Text(message)
        if (result.snapshot != null) Text(stringResource(R.string.cloud_rules_cache_retained))
    }
}

@Composable
internal fun CloudRuleAttribution(rule: CloudRule) {
    if (rule.upstreamFile.isNotEmpty()) Text(stringResource(R.string.cloud_rules_upstream_reference))
    if (rule.authors.isNotEmpty()) {
        Text(stringResource(R.string.cloud_rules_authors, rule.authors.joinToString(", ")))
    }
    if (rule.updatedAtSeconds > 0) {
        Text(stringResource(
            R.string.cloud_rules_source_date,
            DateFormat.getDateInstance().format(Date(rule.updatedAtSeconds * 1000)),
        ))
    }
    if (rule.isUpstreamVerified) Text(stringResource(R.string.cloud_rules_upstream_verified))
}

internal fun cloudRuleWarningText(code: String): Int = warningTextByCode.getValue(code)

private val warningTextByCode = mapOf(
    "direct_mapping" to R.string.cloud_rules_warning_direct_mapping,
    "filtered_export" to R.string.cloud_rules_warning_filtered_export,
    "invalid_export_path" to R.string.cloud_rules_warning_invalid_path,
    "android_export" to R.string.cloud_rules_warning_android_export,
    "unrecognized_structure" to R.string.cloud_rules_warning_structure,
    "conflicting_exports" to R.string.cloud_rules_warning_conflicts,
    "cross_app_access" to R.string.cloud_rules_warning_cross_app,
    "non_standard_files" to R.string.cloud_rules_warning_non_standard,
    "corrected_metadata" to R.string.cloud_rules_warning_metadata,
)
