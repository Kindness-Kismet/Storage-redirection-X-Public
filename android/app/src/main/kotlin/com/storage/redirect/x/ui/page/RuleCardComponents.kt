package com.storage.redirect.x.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RealPathRuleMode
import com.storage.redirect.x.ui.component.PAGE_GROUP_GAP
import com.storage.redirect.x.ui.component.PAGE_ITEM_GAP
import com.storage.redirect.x.ui.component.STATUS_READONLY_COLOR
import com.storage.redirect.x.ui.component.StatusTag
import com.storage.redirect.x.ui.icon.AppIcons
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import com.storage.redirect.x.ui.component.SrxIconButton as IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 规则配置页与模板编辑页共用：区块标题 + 规则卡片 + 卡片底部操作行
private val RULE_CARD_CONTENT_PADDING = 16.dp
private val RULE_CARD_ACTION_ROW_HEIGHT = 60.dp

// 区块标题，可带右侧说明按钮
@Composable
internal fun RuleSectionTitle(title: String, onInfo: (() -> Unit)? = null) {
    Spacer(Modifier.height(PAGE_GROUP_GAP))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallTitle(
            text = title,
            modifier = Modifier.weight(1f),
            insideMargin = PaddingValues(horizontal = RULE_CARD_CONTENT_PADDING, vertical = 8.dp),
        )
        onInfo?.let {
            Icon(
                AppIcons.Info,
                contentDescription = stringResource(R.string.rule_config_allowed_paths_rule_hint_desc),
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(CircleShape)
                    .clickable(onClick = it)
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
    }
}

// 允许访问路径的规则说明，由标题右侧 ⓘ 展开
@Composable
internal fun AllowedPathHintCard(isVisible: Boolean) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = PAGE_ITEM_GAP)) {
            Text(
                text = stringResource(R.string.rule_config_allowed_paths_rule_hint),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(RULE_CARD_CONTENT_PADDING),
            )
        }
    }
}

// 排除与只读规则去掉前缀，分别改用红色与黄色标签标出
@Composable
internal fun AllowedPathRow(path: String, onClick: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val mode = RealPathRuleMode.of(path)
    RuleRow(onClick = onClick, onDelete = onDelete, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = path.trim().removePrefix(mode.prefix),
                modifier = Modifier.weight(1f, fill = false),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when (mode) {
                RealPathRuleMode.Exclude -> StatusTag(
                    label = stringResource(R.string.rule_config_tag_excluded),
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 6.dp),
                )
                RealPathRuleMode.ReadOnly -> StatusTag(
                    label = stringResource(R.string.rule_config_tag_readonly),
                    color = STATUS_READONLY_COLOR,
                    modifier = Modifier.padding(start = 6.dp),
                )
                RealPathRuleMode.Allow -> Unit
            }
        }
    }
}

@Composable
internal fun PathMappingRow(
    requestPath: String,
    finalPath: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RuleRow(onClick = onClick, onDelete = onDelete, modifier = modifier) {
        Text(
            text = requestPath,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "→ $finalPath",
            modifier = Modifier.padding(top = 2.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// 卡片内的规则行：点按编辑，右侧删除
@Composable
private fun RuleRow(
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = RULE_CARD_CONTENT_PADDING, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            content()
        }
        IconButton(onClick = onDelete) {
            Icon(
                AppIcons.Delete,
                contentDescription = stringResource(R.string.common_delete),
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
internal fun RuleEmptyRow(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
    )
}

// 卡片底部的操作行，顶部以分隔线与内容隔开
@Composable
internal fun CardActionRow(content: @Composable RowScope.() -> Unit) {
    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULE_CARD_ACTION_ROW_HEIGHT)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun RowScope.CardActionButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    Row(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = primary)
        Text(
            text = text,
            modifier = Modifier.padding(start = 6.dp),
            color = primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal fun resolvePathValidationErrorResId(result: PathValidationResult): Int {
    return when (result) {
        PathValidationResult.Empty -> R.string.rule_config_error_empty
        PathValidationResult.Absolute -> R.string.rule_config_error_absolute
        PathValidationResult.Traversal -> R.string.rule_config_error_traversal
        PathValidationResult.AndroidDataPath -> R.string.rule_config_error_android_prefix
        PathValidationResult.AndroidAlwaysAllowed -> R.string.rule_config_error_android_always_allowed
        PathValidationResult.WildcardNotAllowed -> R.string.rule_config_error_mapping_wildcard
        PathValidationResult.ExclusionNotAllowed -> R.string.rule_config_error_mapping_exclusion
        is PathValidationResult.Valid -> R.string.rule_config_error_empty
    }
}
