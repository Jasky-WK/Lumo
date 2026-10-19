package com.qingjizhang.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.qingjizhang.app.MainActivity
import com.qingjizhang.app.R

/**
 * v2.9.0：自动备份的「旧备份提醒」。
 *
 * 只在一种情况下发通知：自动备份已经有 3 份，下一次生成新备份前，
 * 提醒用户去「数据备份与恢复」页删掉最旧的一份。**不自动删任何文件**。
 * 纯本地通知，不联网。
 */
object AutoBackupReminder {

    private const val CHANNEL_ID = "auto_backup"
    private const val CHANNEL_NAME = "自动备份提醒"
    private const val NOTIFY_ID = 4203
    private const val REQUEST_CODE = 4203

    /**
     * @param count 现有自动备份份数
     * @param oldestName 最旧那份的文件名（通知里直接告诉用户删哪个）
     */
    fun notifyOldBackup(context: Context, count: Int, oldestName: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "backup")
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, REQUEST_CODE, tap, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("自动备份已有 $count 份")
            .setContentText("最旧的「$oldestName」可以删掉了，点此去清理")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "自动备份最多保留 3 份。已经生成了新的备份，最旧的「$oldestName」可以删掉了。\n" +
                        "点这里打开「数据备份与恢复」手动删除；不删也不会丢数据，只是会多占一些空间。"
                )
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFY_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每月自动备份后的旧文件清理提醒，不需要联网"
            }
        )
    }
}
