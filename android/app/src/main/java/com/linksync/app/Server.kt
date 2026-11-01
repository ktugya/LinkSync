package com.linksync.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.nio.charset.StandardCharsets

class LinkServer(port: Int, private val ctx: Context) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            Log.e(Runtime.TAG, "http ${session.uri}: ${e.message}", e)
            json(mapOf("ok" to false, "error" to (e.message ?: "error")), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" || uri == "/index.html" -> asset("index.html")
            uri == "/app.js" -> asset("app.js")
            uri == "/style.css" -> asset("style.css")
            uri == "/api/health" -> text("ok")
            uri == "/api/info" -> json(
                mapOf(
                    "id" to Store.id,
                    "name" to Store.name,
                    "platform" to "android",
                    "port" to Runtime.port,
                    "version" to "1.0"
                )
            )
            uri == "/api/state" -> raw(Store.stateJson())
            uri == "/api/clipboard" && session.method == Method.POST -> clipboardPush(session)
            uri == "/api/text" && session.method == Method.POST -> textPush(session)
            uri == "/api/text/send" && session.method == Method.POST -> textSend(session)
            uri == "/api/settings" && session.method == Method.POST -> settings(session)
            uri == "/api/peers/add" && session.method == Method.POST -> peerAdd(session)
            uri == "/api/local/copy" && session.method == Method.POST -> localCopy(session)
            uri == "/api/local/clipboard" && session.method == Method.POST ->
                json(mapOf("ok" to false, "text" to ""))
            uri == "/api/transfers/clear" && session.method == Method.POST -> {
                Store.clearFinished(); json(mapOf("ok" to true))
            }
            uri == "/api/upload" && session.method == Method.POST -> upload(session)
            uri == "/api/send" && session.method == Method.POST ->
                json(mapOf("ok" to false, "error" to "use AndroidBridge"), Response.Status.NOT_IMPLEMENTED)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "not found")
        }
    }

    private fun asset(path: String): Response {
        val stream = ctx.assets.open(path)
        val mime = when {
            path.endsWith(".js") -> "application/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".html") -> "text/html; charset=utf-8"
            else -> "application/octet-stream"
        }
        return newChunkedResponse(Response.Status.OK, mime, stream)
    }

    private fun raw(json: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)

    private fun json(value: Any, status: Response.Status = Response.Status.OK): Response {
        val body = when (value) {
            is String -> value
            else -> Runtime.gson.toJson(value)
        }
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body)
    }

    private fun text(value: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", value)

    private fun clipboardPush(session: IHTTPSession): Response {
        val obj = JsonParser.parseString(bodyString(session)).asJsonObject
        val text = obj.get("text")?.asString ?: ""
        if (text.isEmpty()) return json(mapOf("ok" to false, "error" to "empty"), Response.Status.BAD_REQUEST)
        val from = obj.get("origin")?.asString ?: ""
        val fromName = obj.get("originName")?.asString ?: ""
        Store.applyRemoteClipboard(ctx, text, from, fromName)
        return json(mapOf("ok" to true))
    }

    private fun textPush(session: IHTTPSession): Response {
        val obj = JsonParser.parseString(bodyString(session)).asJsonObject
        val text = obj.get("text")?.asString ?: ""
        if (text.isEmpty()) return json(mapOf("ok" to false, "error" to "empty"), Response.Status.BAD_REQUEST)
        val id = obj.get("id")?.asString ?: java.util.UUID.randomUUID().toString().replace("-", "")
        if (!Store.hasMessage(id)) {
            val msg = MessageEntry(
                id = id,
                text = text,
                from = obj.get("from")?.asString ?: "",
                fromName = obj.get("fromName")?.asString ?: "",
                ts = obj.get("ts")?.asLong ?: Store.now()
            )
            Store.addMessage(msg)
            Notifications.text(ctx, msg)
        }
        return json(mapOf("ok" to true))
    }

    private fun textSend(session: IHTTPSession): Response {
        val obj = JsonParser.parseString(bodyString(session)).asJsonObject
        val text = obj.get("text")?.asString ?: ""
        if (text.isEmpty()) return json(mapOf("ok" to false, "error" to "empty"), Response.Status.BAD_REQUEST)
        val targets = mutableListOf<String>()
        obj.getAsJsonArray("to")?.forEach { element ->
            runCatching { element.asString }.getOrNull()?.let { targets.add(it) }
        }
        Runtime.service?.sendText(targets, text)
        return json(mapOf("ok" to true))
    }

    private fun settings(session: IHTTPSession): Response {
        val obj = JsonParser.parseString(bodyString(session)).asJsonObject
        Store.updateSettings(obj)
        return json(mapOf("ok" to true))
    }

    private fun peerAdd(session: IHTTPSession): Response {
        val obj = JsonParser.parseString(bodyString(session)).asJsonObject
        val addr = obj.get("addr")?.asString?.trim().orEmpty()
        if (addr.isEmpty()) return json(mapOf("ok" to false, "error" to "empty"), Response.Status.BAD_REQUEST)
        val idx = addr.lastIndexOf(':')
        val host = if (idx > 0) addr.substring(0, idx) else addr
        val port = if (idx > 0) addr.substring(idx + 1).toIntOrNull() ?: 47821 else 47821
        val info = Net.fetchInfo(host, port)
            ?: return json(mapOf("ok" to false, "error" to "无法连接"), Response.Status.SERVICE_UNAVAILABLE)
        Store.touchPeer(info.id, info.name, info.platform, host, if (info.port > 0) info.port else port)
        return json(mapOf("ok" to true))
    }

    private fun localCopy(session: IHTTPSession): Response {
        val obj = JsonParser.parseString(bodyString(session)).asJsonObject
        val text = obj.get("text")?.asString ?: ""
        if (text.isNotEmpty()) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LinkSync", text))
        }
        return json(mapOf("ok" to true))
    }

    private fun upload(session: IHTTPSession): Response {
        val rawName = session.headers["x-file-name"] ?: "file"
        val name = sanitize(Uri.decode(rawName))
        val fromId = session.headers["x-from-id"] ?: ""
        val fromName = sanitize(Uri.decode(session.headers["x-from-name"] ?: "device"))
        val size = session.headers["x-file-size"]?.toLongOrNull() ?: -1L

        val peerName = fromName.ifBlank { "device" }
        val transfer = Store.addTransfer("in", fromId, peerName, name, size.coerceAtLeast(0))
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LinkSync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri: Uri? = resolver.insert(collection, values)
        if (uri == null) {
            Store.finishTransfer(transfer.id, null, "无法创建文件")
            return json(mapOf("ok" to false, "error" to "insert failed"), Response.Status.INTERNAL_ERROR)
        }

        val wake = acquireWakeLock("receive")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                val input = session.inputStream
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                    Store.updateTransfer(transfer.id, total)
                }
                output.flush()
            } ?: throw IllegalStateException("open output failed")

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            val path = "Download/LinkSync/$name"
            Store.finishTransfer(transfer.id, path, null)
            Notifications.file(ctx, name, peerName)
            Runtime.service?.updateNotification()
            return json(mapOf("ok" to true, "path" to path))
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "upload: ${e.message}")
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            Store.finishTransfer(transfer.id, null, e.message ?: "接收失败")
            return json(mapOf("ok" to false, "error" to (e.message ?: "error")), Response.Status.INTERNAL_ERROR)
        } finally {
            releaseWakeLock(wake)
        }
    }

    private fun acquireWakeLock(tag: String): PowerManager.WakeLock? = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
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

    private fun bodyString(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: -1
        val input = session.inputStream
        if (length in 0..4 * 1024 * 1024) {
            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(data, offset, length - offset)
                if (read <= 0) break
                offset += read
            }
            return String(data, 0, offset, StandardCharsets.UTF_8)
        }
        return String(input.readBytes(), StandardCharsets.UTF_8)
    }

    private fun sanitize(name: String): String {
        var s = name.substringAfterLast('/').substringAfterLast('\\')
        s = s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") s = "file"
        if (s.length > 180) {
            val ext = s.substringAfterLast('.', "")
            s = if (ext.isNotEmpty() && ext.length < 10) s.take(180 - ext.length - 1) + "." + ext else s.take(180)
        }
        return s
    }
}
