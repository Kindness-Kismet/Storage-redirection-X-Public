package com.storage.redirect.x.ui.page

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.component.CardItem
import com.storage.redirect.x.ui.component.groupedCardItems
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

// 开源组件信息
data class OpenSourceLibrary(
    val name: String,
    val license: String,
    val url: String,
)

// Android 端依赖
private val androidLibraries =
    listOf(
        OpenSourceLibrary("Mishka UI components", "GPL 3.0", "https://github.com/YuKongA/Mishka"),
        OpenSourceLibrary(
            name = "Jetpack Compose",
            license = "Apache 2.0",
            url = "https://developer.android.com/jetpack/compose",
        ),
        OpenSourceLibrary(
            name = "Compose Material Icons",
            license = "Apache 2.0",
            url = "https://developer.android.com/jetpack/compose",
        ),
        OpenSourceLibrary(
            name = "AndroidX Core KTX",
            license = "Apache 2.0",
            url = "https://developer.android.com/jetpack/androidx",
        ),
        OpenSourceLibrary(
            name = "AndroidX Activity Compose",
            license = "Apache 2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/activity",
        ),
        OpenSourceLibrary(
            name = "AndroidX Lifecycle",
            license = "Apache 2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
        ),
        OpenSourceLibrary(
            name = "MiuiX",
            license = "Apache 2.0",
            url = "https://github.com/compose-miuix-ui/miuix",
        ),
        OpenSourceLibrary(
            name = "Kotlin Coroutines",
            license = "Apache 2.0",
            url = "https://github.com/Kotlin/kotlinx.coroutines",
        ),
        OpenSourceLibrary(
            name = "Gson",
            license = "Apache 2.0",
            url = "https://github.com/google/gson",
        ),
    )

// Native (Zygisk) 端依赖
private val nativeLibraries =
    listOf(
        OpenSourceLibrary(
            name = "srx_hook",
            license = "MIT",
            url = "https://github.com/Kindness-Kismet/srx_hook",
        ),
        OpenSourceLibrary(
            name = "libc",
            license = "MIT / Apache 2.0",
            url = "https://github.com/rust-lang/libc",
        ),
        OpenSourceLibrary(
            name = "jni-sys",
            license = "MIT / Apache 2.0",
            url = "https://github.com/jni-rs/jni-sys",
        ),
        OpenSourceLibrary(
            name = "serde",
            license = "MIT / Apache 2.0",
            url = "https://github.com/serde-rs/serde",
        ),
        OpenSourceLibrary(
            name = "serde_json",
            license = "MIT / Apache 2.0",
            url = "https://github.com/serde-rs/json",
        ),
        OpenSourceLibrary(
            name = "once_cell",
            license = "MIT / Apache 2.0",
            url = "https://github.com/matklad/once_cell",
        ),
        OpenSourceLibrary(
            name = "log",
            license = "MIT / Apache 2.0",
            url = "https://github.com/rust-lang/log",
        ),
    )

@Composable
fun OpenSourceLicensesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    com.storage.redirect.x.ui.component.ScreenScaffold(
        title = androidx.compose.ui.res.stringResource(R.string.settings_open_source_licenses),
        onBack = onBack,
    ) {
        item { SmallTitle("Android") }
        groupedCardItems(
            "android",
            androidLibraries.map { library ->
                CardItem(library.name) {
                    ArrowPreference(
                        title = library.name,
                        summary = library.license,
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(library.url))
                            )
                        },
                    )
                }
            },
        )
        item { SmallTitle("Native") }
        groupedCardItems(
            "native",
            nativeLibraries.map { library ->
                CardItem(library.name) {
                    ArrowPreference(
                        title = library.name,
                        summary = library.license,
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(library.url))
                            )
                        },
                    )
                }
            },
        )
    }
}
