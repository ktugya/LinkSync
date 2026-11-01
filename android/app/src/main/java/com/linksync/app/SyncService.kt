package com.linksync.app

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class SyncService : Service() {

    companion object {
        const val ACTION_SEND_FILES = "com.linksync.app.SEND_FILES"
        const val ACTION_SEND_CLIPBOARD = "com.linksync.app.SEND_CLIPBOARD"
        const val ACTION_STOP = "com.linksync.app.STOP"
        const val EXTRA_PEER = "peer"
        const val EXTRA_URIS = "uris"
        const val EXTRA_TEXT = "text"
    }

    private data class PendingText(val peerId: String, val msg: MessageEntry)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discovery: Discovery? = null
    private var server: LinkServer? = null
    private val pending = ConcurrentLinkedQueue<PendingText>()
    private var lastPeerCount = -1

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifications.createChannels(this)
        try {
            startForeground(
                Runtime.NOTIF_ID,
                Notifications.service(this, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            Log.e(Runtime.TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return
        }
        Runtime.service = this
        startServer()
        try {
            discovery = Discovery(this).also { it.start(scope) }
        } catch (e: Exception) {
            Log.e(Runtime.TAG, "discovery failed: ${e.message}")
        }
        scope.launch { retryLoop() }
        scope.launch { pingLoop() }
        scope.launch {
            while (isActive) {
                updateNotification()
                delay(5000)
            }
        }
        Log.i(Runtime.TAG, "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_FILES -> {
                val peer = intent.getStringExtra(EXTRA_PEER).orEmpty()
                val uris = urisFrom(intent)
                if (peer.isNotEmpty() && uris != null && uris.isNotEmpty()) {
                    sendFiles(peer, uris)
                }
            }
            ACTION_SEND_CLIPBOARD -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrEmpty()) broadcastClipboard(text)
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        updateNotification()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun urisFrom(intent: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        else
            intent.getParcelableArrayListExtra(EXTRA_URIS)

    private fun startServer() {
        for (port in 47821..47830) {
            try {
                val s = LinkServer(port, this)
                s.start(5000, false)
                server = s
                Runtime.server = s
                Runtime.port = port
                Log.i(Runtime.TAG, "http server on 0.0.0.0:$port")
                updateNotification()
                return
            } catch (e: Exception) {
                Log.w(Runtime.TAG, "port $port: ${e.message}")
            }
        }
        Runtime.port = 0
        Log.e(Runtime.TAG, "no free port for server")
    }

    fun broadcastClipboard(text: String) {
        if (text.isEmpty()) return
        Store.addClipboard(text, Store.id, Store.name)
        val peers = Store.peers.values.filter { Store.isOnline(it) }
        for (peer in peers) {
            scope.launch {
                try { Net.pushClipboard(peer, text) } catch (_: Exception) { }
            }
        }
    }

    fun sendText(targets: List<String>, text: String) {
        if (text.isBlank()) return
        val msg = MessageEntry(
            UUID.randomUUID().toString().replace("-", ""),
            text,
            Store.id,
            Store.name,
            Store.now()
        )
        Store.addMessage(msg)
        for (id in targets.filter { it.isNotBlank() }.distinct()) {
            val peer = Store.peers[id] ?: continue
            scope.launch {
                val ok = if (Store.isOnline(peer)) {
                    try { Net.pushText(peer, msg) } catch (_: Exception) { false }
                } else false
                if (!ok) pending.add(PendingText(id, msg))
            }
        }
    }

    private suspend fun pingLoop() {
        while (scope.isActive) {
            try {
                val now = Store.now()
                for (peer in Store.peers.values) {
                    if (now - peer.lastSeen < 8000) continue
                    scope.launch {
                        val info = Net.fetchInfo(peer.ip, peer.port)
                        if (info != null) {
                            Store.touchPeer(info.id, info.name, info.platform, peer.ip, peer.port)
                        }
                    }
                }
            } catch (_: Exception) { }
            delay(6000)
        }
    }

    private suspend fun retryLoop() {
        while (scope.isActive) {
            try {
                val batch = mutableListOf<PendingText>()
                while (true) {
                    val item = pending.poll() ?: break
                    batch.add(item)
                }
                for (item in batch) {
                    val peer = Store.peers[item.peerId]
                    val ok = if (peer != null && Store.isOnline(peer)) {
                        try { Net.pushText(peer, item.msg) } catch (_: Exception) { false }
                    } else false
                    if (!ok) pending.add(item)
                }
            } catch (e: Exception) {
                Log.w(Runtime.TAG, "text retry: ${e.message}")
            }
            delay(5000)
        }
    }

    fun sendFiles(peerId: String, uris: List<Uri>) {
        val peer = Store.peers[peerId] ?: return
        scope.launch {
            val wake = acquireWakeLock("send")
            try {
                for (uri in uris) {
                    val (name, size) = queryNameSize(uri)
                    val transfer = Store.addTransfer("out", peer.id, peer.name, name, size)
                    Net.sendFile(applicationContext, peer, uri, name, size, transfer.id)
                    updateNotification()
                }
            } finally {
                releaseWakeLock(wake)
            }
        }
    }

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: name
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "query uri: ${e.message}")
        }
        return name to size
    }

    fun updateNotification() {
        val count = Store.peers.values.count { Store.isOnline(it) }
        if (count == lastPeerCount) return
        lastPeerCount = count
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(Runtime.NOTIF_ID, Notifications.service(this, count))
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "update notification: ${e.message}")
        }
    }

    private fun acquireWakeLock(tag: String): PowerManager.WakeLock? = try {
        val pm = getSystemService(PowerManager::class.java)
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkSync:$tag").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    } catch (_: Exception) {
        null
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        try { if (lock?.isHeld == true) lock.release() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        Log.i(Runtime.TAG, "service destroyed")
        try { discovery?.stop() } catch (_: Exception) { }
        try { server?.stop() } catch (_: Exception) { }
        try { scope.cancel() } catch (_: Exception) { }
        Runtime.service = null
        Runtime.server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
