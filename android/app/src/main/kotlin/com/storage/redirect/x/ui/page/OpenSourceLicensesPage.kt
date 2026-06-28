package com.storage.redirect.x.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.component.SrxTopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

// 开源组件信息
data class OpenSourceLibrary(
    val name: String,
    val license: String,
    val url: String
)

// Android 端依赖
private val androidLibraries = listOf(
    OpenSourceLibrary(
        name = "Jetpack Compose",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/compose"
    ),
    OpenSourceLibrary(
        name = "Compose Material Icons",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/compose"
    ),
    OpenSourceLibrary(
        name = "AndroidX Core KTX",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/androidx"
    ),
    OpenSourceLibrary(
        name = "AndroidX Activity Compose",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/activity"
    ),
    OpenSourceLibrary(
        name = "AndroidX Lifecycle",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/lifecycle"
    ),
    OpenSourceLibrary(
        name = "MiuiX",
        license = "Apache 2.0",
        url = "https://github.com/YuKongA/miuix-kotlin-multiplatform"
    ),
    OpenSourceLibrary(
        name = "Kotlin Coroutines",
        license = "Apache 2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines"
    ),
    OpenSourceLibrary(
        name = "Gson",
        license = "Apache 2.0",
        url = "https://github.com/google/gson"
    )
)

// Native (Zygisk) 端依赖
private val nativeLibraries = listOf(
    OpenSourceLibrary(
        name = "srx_hook",
        license = "MIT",
        url = "https://github.com/Kindness-Kismet/srx_hook"
    ),
    OpenSourceLibrary(
        name = "libc",
        license = "MIT / Apache 2.0",
        url = "https://github.com/rust-lang/libc"
    ),
    OpenSourceLibrary(
        name = "jni-sys",
        license = "MIT / Apache 2.0",
        url = "https://github.com/jni-rs/jni-sys"
    ),
    OpenSourceLibrary(
        name = "serde",
        license = "MIT / Apache 2.0",
        url = "https://github.com/serde-rs/serde"
    ),
    OpenSourceLibrary(
        name = "serde_json",
        license = "MIT / Apache 2.0",
        url = "https://github.com/serde-rs/json"
    ),
    OpenSourceLibrary(
        name = "once_cell",
        license = "MIT / Apache 2.0",
        url = "https://github.com/matklad/once_cell"
    ),
    OpenSourceLibrary(
        name = "log",
        license = "MIT / Apache 2.0",
        url = "https://github.com/rust-lang/log"
    )
)

// 页面间距：顶栏下留白，区块间距，卡片横向边距，底部收尾间距
private val LICENSES_PAGE_TOP_SPACING = 6.dp
private val LICENSES_PAGE_SECTION_SPACING = 12.dp
private val LICENSES_PAGE_HORIZONTAL_PADDING = 12.dp
private val LICENSES_PAGE_BOTTOM_SPACING = 12.dp

@Composable
fun OpenSourceLicensesPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current

    val onOpenUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        SrxTopAppBar(
            title = stringResource(R.string.settings_open_source_licenses),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.common_close))
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(LICENSES_PAGE_TOP_SPACING))

            SmallTitle(text = stringResource(R.string.settings_android_libraries))

            Card(modifier = Modifier.padding(horizontal = LICENSES_PAGE_HORIZONTAL_PADDING)) {
                androidLibraries.forEach { lib ->
                    ArrowPreference(
                        title = lib.name,
                        summary = lib.license,
                        onClick = { onOpenUrl(lib.url) }
                    )
                }
            }

            Spacer(Modifier.height(LICENSES_PAGE_SECTION_SPACING))

            SmallTitle(text = stringResource(R.string.settings_native_libraries))

            Card(modifier = Modifier.padding(horizontal = LICENSES_PAGE_HORIZONTAL_PADDING)) {
                nativeLibraries.forEach { lib ->
                    ArrowPreference(
                        title = lib.name,
                        summary = lib.license,
                        onClick = { onOpenUrl(lib.url) }
                    )
                }
            }

            Spacer(Modifier.height(LICENSES_PAGE_BOTTOM_SPACING))
        }
    }
}
