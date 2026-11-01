package com.linksync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

object Notifications {
    const val CH_SYNC = "sync"
    const val CH_MSG = "messages"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_SYNC, "后台服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持与电脑/其他设备的连接"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, "消息与文件", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "接收到的文本、文件与传输结果"
            }
        )
    }

    fun service(ctx: Context, peers: Int): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val send = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).putExtra("sendClipboard", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CH_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("LinkSync 正在运行")
            .setContentText(if (peers > 0) "已连接 $peers 台设备" else "等待其他设备…")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(android.R.drawable.ic_menu_share, "发送剪贴板", send).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun text(ctx: Context, msg: MessageEntry) {
        val id = msg.id.hashCode()
        val copy = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, CopyReceiver::class.java)
                .putExtra("text", msg.text)
                .putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            ctx, id + 1000, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CH_MSG)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("来自 ${msg.fromName.ifBlank { "设备" }} 的文本")
            .setContentText(msg.text)
            .setStyle(Notification.BigTextStyle().bigText(msg.text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_save, "复制", copy).build())
            .setAutoCancel(true)
            .build()
        notify(ctx, id, n)
    }

    fun file(ctx: Context, name: String, from: String) {
        val id = ("file:" + name + System.currentTimeMillis()).hashCode()
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("已接收文件")
            .setContentText("$name · 来自 $from")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(ctx, id, n)
    }

    private fun notify(ctx: Context, id: Int, notification: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "notify: ${e.message}")
        }
    }
}
