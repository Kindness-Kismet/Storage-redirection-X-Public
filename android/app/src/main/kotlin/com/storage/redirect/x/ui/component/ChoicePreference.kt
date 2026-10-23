package com.storage.redirect.x.ui.component

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
fun ChoicePreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    val select by rememberUpdatedState(onSelect)
    val entry =
        remember(items, selectedIndex) {
            DropdownEntry(
                items.mapIndexed { index, item ->
                    DropdownItem(
                        text = item,
                        selected = index == selectedIndex,
                        onClick = { select(index) },
                    )
                }
            )
        }
    OverlayDropdownPreference(
        title = title,
        entry = entry,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
    )
}
