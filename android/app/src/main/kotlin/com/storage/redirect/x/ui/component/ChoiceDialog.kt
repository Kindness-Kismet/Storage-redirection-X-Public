package com.storage.redirect.x.ui.component

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.storage.redirect.x.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.popup.WindowDropdownDialog

@Composable
fun ChoiceDialog(
    show: Boolean,
    title: String,
    items: List<String>,
    onDismiss: () -> Unit,
    selectedIndex: Int = -1,
    onSelect: (Int) -> Unit,
) {
    val select by rememberUpdatedState(onSelect)
    val entry =
        remember(items, selectedIndex) {
            DropdownEntry(
                items.mapIndexed { i, name ->
                    DropdownItem(
                        text = name,
                        selected = i == selectedIndex,
                        onClick = { select(i) },
                    )
                }
            )
        }
    WindowDropdownDialog(
        entry = entry,
        title = title,
        dialogButtonString = stringResource(R.string.common_cancel),
        show = show,
        onDismiss = onDismiss,
        onDismissFinished = {},
        dropdownColors = DropdownDefaults.dialogDropdownColors(),
    )
}
