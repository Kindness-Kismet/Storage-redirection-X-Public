package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.data.model.RealPathRuleMode
import com.storage.redirect.x.ui.component.PillButton
import com.storage.redirect.x.ui.component.STATUS_READONLY_COLOR
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class BrowserColors(
    val surface: Color,
    val text: Color,
    val summary: Color,
    val primary: Color,
    val selected: Color,
    val error: Color,
    val errorSelected: Color,
)

@Composable
internal fun browserColors(): BrowserColors = MiuixTheme.colorScheme.let {
    BrowserColors(
        it.surfaceContainer, it.onSurface, it.onSurfaceVariantSummary, it.primary,
        it.tertiaryContainer, it.error, it.errorContainer,
    )
}

// 排除模式换成错误色，只读模式换成黄色
@Composable
internal fun PathBrowserState.accentColor(): Color = when (ruleMode) {
    RealPathRuleMode.Exclude -> browserColors().error
    RealPathRuleMode.ReadOnly -> STATUS_READONLY_COLOR
    RealPathRuleMode.Allow -> browserColors().primary
}

@Composable
internal fun PathBrowserState.selectedRowColor(): Color = when (ruleMode) {
    RealPathRuleMode.Exclude -> browserColors().errorSelected
    RealPathRuleMode.ReadOnly -> STATUS_READONLY_COLOR.copy(alpha = READONLY_SELECTED_ALPHA)
    RealPathRuleMode.Allow -> browserColors().selected
}

private const val READONLY_SELECTED_ALPHA = 0.16f

@Composable
internal fun BrowserText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = browserColors().text,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text, modifier = modifier, color = color, fontSize = fontSize,
        fontWeight = fontWeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun BrowserButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    PillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
    )
}

@Composable
internal fun BrowserCheckbox(
    selection: DirectorySelection,
    description: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), Alignment.Center) {
        Checkbox(
            state = when (selection) {
                DirectorySelection.Selected, DirectorySelection.Covered -> ToggleableState.On
                DirectorySelection.Partial -> ToggleableState.Indeterminate
                DirectorySelection.None -> ToggleableState.Off
            },
            onClick = onClick,
            modifier = modifier.semantics { contentDescription = description },
            enabled = selection != DirectorySelection.Covered,
            colors = CheckboxDefaults.checkboxColors(checkedBackgroundColor = color),
        )
    }
}
