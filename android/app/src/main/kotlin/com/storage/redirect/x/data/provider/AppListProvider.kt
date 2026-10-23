package com.storage.redirect.x.data.provider

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import com.storage.redirect.x.data.model.AppInfo
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AppCatalogSource {
    suspend fun getInstalledApps(): ImmutableList<AppInfo>

    suspend fun getIcon(packageName: String): Drawable?

    suspend fun getInstallTimes(apps: ImmutableList<AppInfo>): ImmutableList<AppInfo>
}

/** Mishka's narrow PackageManager query, adapted to include offline storage-using apps. */
class AppListProvider(context: Context) : AppCatalogSource {
    private val context = context.applicationContext
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(2)
    @Volatile private var installed = emptyMap<String, ApplicationInfo>()

    override suspend fun getInstalledApps(): ImmutableList<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val applications =
                pm.getInstalledApplications(0).filter { it.packageName != context.packageName }
            installed = applications.associateBy { it.packageName }
            applications
                .map { app ->
                    AppInfo(
                        packageName = app.packageName,
                        appName = app.loadLabel(pm).toString(),
                        isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
                .sortedBy { it.appName.lowercase() }
                .toPersistentList()
        }

    override suspend fun getIcon(packageName: String): Drawable? =
        withContext(iconDispatcher) {
            val app = installed[packageName] ?: return@withContext null
            try {
                BitmapDrawable(
                    context.resources,
                    app.loadIcon(context.packageManager).toBitmap(96, 96),
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

    // firstInstallTime needs a separate Binder query, so only the explicit install-time sort
    // requests it.
    override suspend fun getInstallTimes(apps: ImmutableList<AppInfo>): ImmutableList<AppInfo> =
        withContext(Dispatchers.IO) {
            apps
                .map { app ->
                    val timestamp =
                        try {
                            @Suppress("DEPRECATION")
                            context.packageManager
                                .getPackageInfo(app.packageName, 0)
                                .firstInstallTime
                        } catch (_: PackageManager.NameNotFoundException) {
                            0L
                        }
                    app.copy(firstInstallTime = timestamp)
                }
                .toPersistentList()
        }
}
