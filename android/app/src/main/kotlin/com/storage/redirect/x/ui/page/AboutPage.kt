package com.storage.redirect.x.ui.page

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.storage.redirect.x.BuildConfig
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.component.PAGE_GROUP_GAP
import com.storage.redirect.x.ui.component.PAGE_HORIZONTAL_PADDING
import com.storage.redirect.x.ui.component.PreferenceIcon
import com.storage.redirect.x.ui.component.SrxBackButton
import com.storage.redirect.x.ui.component.effect.FlowLightBackground
import com.storage.redirect.x.ui.icon.AppIcons
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val PROJECT_REPOSITORY_URL = "https://github.com/Kindness-Kismet/Storage-redirection-X-Public"

// 应用图标底板与图标尺寸，及其与顶栏的间距
private val ABOUT_ICON_CONTAINER_SIZE = 88.dp
private val ABOUT_ICON_SIZE = 74.dp
private val ABOUT_HERO_TOP_PADDING = 32.dp

@Composable
fun AboutPage(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    bottomInnerPadding: Dp = 0.dp,
) {
    val context = LocalContext.current

    FlowLightBackground(modifier = Modifier.fillMaxSize().testTag("page_about")) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 小标题固定顶栏，透明底透出流光背景
            SmallTopAppBar(
                title = stringResource(R.string.settings_about),
                color = Color.Transparent,
                navigationIcon = { SrxBackButton(onClick = onBack) },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState(), overscrollEffect = null)
                    .padding(horizontal = PAGE_HORIZONTAL_PADDING)
                    .padding(top = ABOUT_HERO_TOP_PADDING),
                verticalArrangement = Arrangement.spacedBy(PAGE_GROUP_GAP),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AboutHero()
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.about_project_repository),
                        summary = stringResource(R.string.about_project_repository_desc),
                        startAction = { PreferenceIcon(AppIcons.GitHub) },
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, PROJECT_REPOSITORY_URL.toUri()))
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_open_source_licenses),
                        summary = stringResource(R.string.settings_open_source_licenses_desc),
                        startAction = { PreferenceIcon(AppIcons.Code) },
                        modifier = Modifier.testTag("about_licenses_entry"),
                        onClick = onOpenLicenses,
                    )
                }
                Spacer(Modifier.height(bottomInnerPadding))
            }
        }
    }
}

// 应用图标、名称与版本号
@Composable
private fun AboutHero() {
    val context = LocalContext.current
    val appIcon = remember {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap().asImageBitmap()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PAGE_GROUP_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(ABOUT_ICON_CONTAINER_SIZE)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = appIcon,
                contentDescription = stringResource(R.string.home_title),
                modifier = Modifier.size(ABOUT_ICON_SIZE),
            )
        }
        Text(
            text = stringResource(R.string.home_title),
            modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
            color = MiuixTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
