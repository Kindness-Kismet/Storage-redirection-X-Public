package com.storage.redirect.x.ui.page.cloudrules

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.CloudRulePreferences
import com.storage.redirect.x.ui.icon.AppIcons
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CloudRuleCard(
    state: CloudRuleState,
    packageName: String,
    modifier: Modifier = Modifier,
) {
    val isEnabled by CloudRulePreferences.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(state, isEnabled) { if (isEnabled) state.load() }
    val snapshot = state.result?.snapshot
    val rule = snapshot?.catalog?.rulesByPackage?.get(packageName)
    val hasError = isEnabled && snapshot == null && state.result?.error != null && !state.isLoading
    val summary = when {
        !isEnabled -> R.string.cloud_rules_match_all
        rule != null -> when (rule.recommendation) {
            "recommended" -> R.string.cloud_rules_recommended
            "unnecessary" -> R.string.cloud_rules_unnecessary
            "not_recommended" -> R.string.cloud_rules_not_recommended
            "cause_problems" -> R.string.cloud_rules_cause_problems
            "depends_on_situations" -> R.string.cloud_rules_depends
            else -> R.string.cloud_rules_manual
        }
        hasError -> R.string.cloud_rules_unavailable
        state.isLoading -> R.string.cloud_rules_loading
        else -> R.string.cloud_rules_not_found
    }
    val description = rule?.descriptionFor(LocalConfiguration.current.locales[0].toLanguageTag()).orEmpty().trim()
    val hasAdvice = isEnabled && rule != null
    val title = if (hasAdvice) description.substringBefore('\n').ifBlank { stringResource(summary) }
    else stringResource(R.string.cloud_rules_global)
    val body = if (hasAdvice) description.substringAfter('\n', "").trim().ifEmpty { null }
    else stringResource(summary)
    val palette = cloudCardPalette(!isEnabled || rule?.recommendation == "recommended")
    Card(
        modifier = modifier.fillMaxWidth().testTag("cloud_rules_card"),
        colors = CardDefaults.defaultColors(color = palette.container),
    ) {
        BasicComponent(
            title = title,
            summary = body,
            startAction = {
                Icon(
                    if (hasAdvice && rule.recommendation == "recommended") AppIcons.CheckCircle else AppIcons.Cloud,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
            endActions = if (!isEnabled || hasError) {
                {
                    TextButton(
                        text = stringResource(if (isEnabled) R.string.cloud_rules_retry else R.string.cloud_rules_enable),
                        onClick = { if (!isEnabled) CloudRulePreferences.setEnabled(true) else state.refresh() },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.testTag("cloud_rules_enable"),
                    )
                }
            } else null,
        )
    }
}

private data class CloudCardPalette(val container: Color, val accent: Color)

@Composable
private fun cloudCardPalette(isRecommended: Boolean): CloudCardPalette {
    val colors = MiuixTheme.colorScheme
    if (!isRecommended) return CloudCardPalette(colors.surfaceContainer, colors.onSurfaceVariantActions)
    if (MiuixTheme.isDynamicColor) return CloudCardPalette(colors.tertiaryContainer, colors.primary)
    val isDark = colors.surfaceContainer.luminance() < 0.5f
    // 普通主题采用薄荷绿建议色；动态取色沿用用户主题。
    return if (isDark) CloudCardPalette(Color(0xFF142E28), Color(0xFF74D8BA))
    else CloudCardPalette(Color(0xFFECF7F2), Color(0xFF00856B))
}
