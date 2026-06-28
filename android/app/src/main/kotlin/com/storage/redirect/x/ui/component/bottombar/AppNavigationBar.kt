package com.storage.redirect.x.ui.component.bottombar

import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.NavigationBar as MaterialNavigationBar
import androidx.compose.material3.NavigationBarItem as MaterialNavigationBarItem
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.storage.redirect.x.ui.theme.LocalUiMode
import com.storage.redirect.x.ui.theme.UiMode
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem

// 固定底栏按 uiMode 分发：Material 用 Material3 NavigationBar，Miuix 用 Miuix NavigationBar
// 菜单展开时由 enabled 决定是否拦截点击，两种风格行为一致
@Composable
fun AppNavigationBar(
    labels: List<String>,
    icons: List<ImageVector>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Material -> MaterialNavigationBar {
            icons.forEachIndexed { index, icon ->
                MaterialNavigationBarItem(
                    selected = selectedIndex == index,
                    onClick = { if (enabled) onSelect(index) },
                    icon = { MaterialIcon(icon, contentDescription = labels[index]) },
                    label = { MaterialText(labels[index]) },
                )
            }
        }

        UiMode.Miuix -> MiuixNavigationBar {
            icons.forEachIndexed { index, icon ->
                MiuixNavigationBarItem(
                    selected = selectedIndex == index,
                    onClick = { if (enabled) onSelect(index) },
                    icon = icon,
                    label = labels[index],
                )
            }
        }
    }
}
