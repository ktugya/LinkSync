package com.linksync.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class Peer(
    val id: String,
    var name: String,
    var platform: String,
    var ip: String,
    var port: Int,
    var lastSeen: Long
)

data class Transfer(
    val id: String,
    val peerId: String,
    val peerName: String,
    val direction: String,
    val name: String,
    val size: Long,
    var bytes: Long = 0,
    var status: String = "running",
    var path: String = "",
    var error: String = "",
    val ts: Long = System.currentTimeMillis()
)

data class ClipEntry(
    val id: String,
    val text: String,
    val from: String,
    val fromName: String,
    val ts: Long
)

data class MessageEntry(
    val id: String,
    val text: String,
    val from: String,
    val fromName: String,
    val ts: Long
)

object Runtime {
    const val TAG = "LinkSync"
    const val NOTIF_ID = 1001
    var port = 0
    var server: LinkServer? = null
    var service: SyncService? = null
    var lastAppliedText = ""
    var lastAppliedAt = 0L
    val gson = Gson()
}

object Store {
    private const val PREFS = "linksync"
    private const val ONLINE_MS = 15_000L
    private val lock = Any()
    private var ipsCache: List<String>? = null
    private var ipsCacheAt = 0L

    lateinit var prefs: SharedPreferences
    var id = ""
    var name = ""
    var autoClipboard = true
    var autoReceive = true
    var startOnBoot = true

    val peers = ConcurrentHashMap<String, Peer>()
    val transfers = CopyOnWriteArrayList<Transfer>()
    val clipboard = CopyOnWriteArrayList<ClipEntry>()
    val messages = CopyOnWriteArrayList<MessageEntry>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        id = prefs.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("id", it).apply()
        }
        name = prefs.getString("name", null) ?: defaultName().also {
            prefs.edit().putString("name", it).apply()
        }
        autoClipboard = prefs.getBoolean("autoClipboard", true)
        autoReceive = prefs.getBoolean("autoReceive", true)
        startOnBoot = prefs.getBoolean("startOnBoot", true)
    }

    private fun defaultName(): String {
        val model = Build.MODEL ?: "Android"
        return model.ifBlank { "Android" }.take(40)
    }

    fun now() = System.currentTimeMillis()

    fun isOnline(peer: Peer) = now() - peer.lastSeen < ONLINE_MS

    fun touchPeer(peerId: String, peerName: String, platform: String, ip: String, port: Int) {
        if (peerId.isBlank() || peerId == id) return
        val safeName = peerName.ifBlank { peerId }
        val existing = peers[peerId]
        if (existing == null) {
            peers[peerId] = Peer(peerId, safeName, platform.ifBlank { "android" }, ip, port, now())
        } else {
            existing.name = safeName
            existing.platform = platform.ifBlank { existing.platform }
            existing.ip = ip
            existing.port = port
            existing.lastSeen = now()
        }
    }

    fun addTransfer(direction: String, peerId: String, peerName: String, name: String, size: Long): Transfer {
        val t = Transfer(UUID.randomUUID().toString().replace("-", ""), peerId, peerName, direction, name, size)
        transfers.add(0, t)
        while (transfers.size > 80) transfers.removeAt(transfers.size - 1)
        return t
    }

    fun updateTransfer(transferId: String, bytes: Long) {
        transfers.firstOrNull { it.id == transferId }?.bytes = bytes
    }

    fun finishTransfer(transferId: String, path: String?, error: String?) {
        val t = transfers.firstOrNull { it.id == transferId } ?: return
        t.status = if (error == null) "done" else "failed"
        t.path = path ?: ""
        t.error = error ?: ""
        if (error == null && t.size > 0) t.bytes = t.size
    }

    fun clearFinished() {
        transfers.removeAll { it.status != "running" }
    }

    fun addClipboard(text: String, from: String, fromName: String) {
        synchronized(lock) {
            if (clipboard.isNotEmpty() && clipboard[0].text == text) return
            clipboard.add(0, ClipEntry(UUID.randomUUID().toString().replace("-", ""), text, from, fromName, now()))
            while (clipboard.size > 50) clipboard.removeAt(clipboard.size - 1)
        }
    }

    fun isRecentlyApplied(text: String): Boolean = synchronized(lock) {
        text == Runtime.lastAppliedText && now() - Runtime.lastAppliedAt < 5000
    }

    fun markApplied(text: String, from: String, fromName: String) {
        synchronized(lock) {
            Runtime.lastAppliedText = text
            Runtime.lastAppliedAt = now()
        }
        addClipboard(text, from, fromName)
    }

    fun applyRemoteClipboard(ctx: Context, text: String, from: String, fromName: String) {
        if (text.isEmpty() || isRecentlyApplied(text)) return
        markApplied(text, from, fromName)
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LinkSync", text))
        } catch (_: Exception) {
        }
    }

    fun addMessage(msg: MessageEntry) {
        synchronized(lock) {
            if (messages.any { it.id == msg.id }) return
            messages.add(0, msg)
            while (messages.size > 50) messages.removeAt(messages.size - 1)
        }
    }

    fun hasMessage(messageId: String): Boolean = synchronized(lock) {
        messages.any { it.id == messageId }
    }

    fun updateSettings(obj: JsonObject) {
        val editor = prefs.edit()
        obj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }?.let {
            name = it.take(40); editor.putString("name", name)
        }
        obj.get("autoClipboard")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
            autoClipboard = it; editor.putBoolean("autoClipboard", it)
        }
        obj.get("autoReceive")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
            autoReceive = it; editor.putBoolean("autoReceive", it)
        }
        obj.get("startOnBoot")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
            startOnBoot = it; editor.putBoolean("startOnBoot", it)
        }
        editor.apply()
        Runtime.service?.updateNotification()
    }

    fun stateJson(): String {
        val peerList = peers.values
            .sortedWith(compareByDescending<Peer> { isOnline(it) }.thenBy { it.name })
            .map { p ->
                mapOf(
                    "id" to p.id,
                    "name" to p.name,
                    "platform" to p.platform,
                    "ip" to p.ip,
                    "port" to p.port,
                    "online" to isOnline(p),
                    "lastSeen" to p.lastSeen
                )
            }

        val transferList = transfers.take(50).map { t ->
            mapOf(
                "id" to t.id,
                "peerId" to t.peerId,
                "peerName" to t.peerName,
                "direction" to t.direction,
                "name" to t.name,
                "size" to t.size,
                "bytes" to t.bytes,
                "status" to t.status,
                "path" to t.path,
                "error" to t.error,
                "ts" to t.ts
            )
        }

        val clipList = clipboard.take(50).map { c ->
            mapOf("id" to c.id, "text" to c.text, "from" to c.from, "fromName" to c.fromName, "ts" to c.ts)
        }

        val messageList = messages.take(50).map { m ->
            mapOf("id" to m.id, "text" to m.text, "from" to m.from, "fromName" to m.fromName, "ts" to m.ts)
        }

        val payload = mapOf(
            "self" to mapOf(
                "id" to id,
                "name" to name,
                "platform" to "android",
                "port" to Runtime.port,
                "ips" to localIps()
            ),
            "peers" to peerList,
            "transfers" to transferList,
            "clipboard" to clipList,
            "messages" to messageList,
            "settings" to mapOf(
                "name" to name,
                "autoClipboard" to autoClipboard,
                "autoReceive" to autoReceive,
                "downloadDir" to "Download/LinkSync",
                "startWithWindows" to false,
                "port" to Runtime.port
            )
        )
        return Runtime.gson.toJson(payload)
    }

    @Synchronized
    fun localIps(): List<String> {
        val current = now()
        ipsCache?.let { if (current - ipsCacheAt < 10_000) return it }
        val result = mutableListOf<String>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val address = ia.address
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        address.hostAddress?.let { result.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        val list = result.distinct()
        ipsCache = list
        ipsCacheAt = current
        return list
    }
}
