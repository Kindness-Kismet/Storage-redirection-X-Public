package com.storage.redirect.x.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.storage.redirect.x.R

object BackupRestoreNotifier {

    private const val CHANNEL_ID = "backup_restore_status"
    private const val NOTIFICATION_ID_BACKUP = 30001
    private const val NOTIFICATION_ID_RESTORE = 30002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_backup_restore_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_backup_restore_desc)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyBackupResult(context: Context, success: Boolean, packageCount: Int) {
        ensureChannel(context)
        val title = if (success) {
            context.getString(R.string.notification_backup_success_title)
        } else {
            context.getString(R.string.notification_backup_failed_title)
        }
        val text = if (success) {
            context.getString(R.string.notification_backup_success_text, packageCount)
        } else {
            context.getString(R.string.notification_backup_failed_text, packageCount)
        }
        notify(context, NOTIFICATION_ID_BACKUP, title, text, success)
    }

    fun notifyRestoreResult(
        context: Context,
        success: Boolean,
        restoredPackageCount: Int,
    ) {
        ensureChannel(context)
        val title = if (success) {
            context.getString(R.string.notification_restore_success_title)
        } else {
            context.getString(R.string.notification_restore_failed_title)
        }
        val text = if (success) {
            context.getString(
                R.string.notification_restore_success_text,
                restoredPackageCount,
            )
        } else {
            context.getString(
                R.string.notification_restore_failed_text,
                restoredPackageCount,
            )
        }
        notify(context, NOTIFICATION_ID_RESTORE, title, text, success)
    }

    private fun notify(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        success: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.warn("Notification permission not granted, skip sending notification")
            return
        }

        val smallIcon = if (success) {
            android.R.drawable.stat_sys_upload_done
        } else {
            android.R.drawable.stat_notify_error
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
