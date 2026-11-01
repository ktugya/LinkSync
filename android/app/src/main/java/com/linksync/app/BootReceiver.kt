package com.linksync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Store.init(context)
        if (!Store.startOnBoot) return
        try {
            val i = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            Log.i(Runtime.TAG, "service started from boot")
        } catch (e: Exception) {
            Log.e(Runtime.TAG, "boot start failed: ${e.message}")
        }
    }
}
