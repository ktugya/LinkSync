package com.linksync.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        Store.init(context)
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LinkSync", text))
            Runtime.lastAppliedText = text
            Runtime.lastAppliedAt = Store.now()
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            val id = intent.getIntExtra("id", 0)
            if (id != 0) {
                context.getSystemService(android.app.NotificationManager::class.java)?.cancel(id)
            }
        } catch (_: Exception) {
        }
    }
}
