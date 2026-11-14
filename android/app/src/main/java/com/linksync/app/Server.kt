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
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

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
        val uri = session.uri ?: "/"
        return when {
            uri.startsWith("/api/") -> api(session, uri)
            session.method == Method.GET || session.method == Method.HEAD -> staticFile(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAIN, "not found")
        }
    }

    private fun api(session: IHTTPSession, uri: String): Response = when {
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
        uri == "/api/send" && session.method == Method.POST -> sendToPeer(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAIN, "not found")
    }

    /**
     * 静态资源：直接映射 assets 目录，新增前端文件不需要再改路由（旧实现丢了 qrcode.min.js）。
     */
    private fun staticFile(uri: String): Response {
        var name = if (uri == "/" || uri.isEmpty()) "index.html" else uri.removePrefix("/")
        if (name.isEmpty() || name.contains("..") || name.contains('\\') || name.startsWith("/"))
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAIN, "forbidden")
        return try {
            val stream = ctx.assets.open(name)
            newChunkedResponse(Response.Status.OK, mimeOf(name), stream).apply {
                addHeader("Cache-Control", "no-store")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAIN, "not found")
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
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

    /**
     * 接收文件。必须按 Content-Length 精确读取：
     * NanoHTTPD 的 serve() 不会自动消费请求体，读到 EOF 会一直阻塞在 keep-alive 连接上。
     */
    private fun upload(session: IHTTPSession): Response {
        val rawName = session.headers["x-file-name"] ?: "file"
        val name = sanitize(Uri.decode(rawName))
        val fromId = session.headers["x-from-id"] ?: ""
        val fromName = sanitize(Uri.decode(session.headers["x-from-name"] ?: "device"))
        val declared = session.headers["x-file-size"]?.toLongOrNull() ?: -1L
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L

        if (length < 0)
            return json(mapOf("ok" to false, "error" to "缺少 Content-Length，不支持分块上传"),
                Response.Status.LENGTH_REQUIRED)
        if (length == 0L)
            return json(mapOf("ok" to false, "error" to "空文件"), Response.Status.BAD_REQUEST)
        if (declared > 0 && declared != length)
            return json(mapOf("ok" to false, "error" to "X-File-Size($declared) 与 Content-Length($length) 不一致"),
                Response.Status.BAD_REQUEST)

        val size = if (declared > 0) declared else length
        val peerName = fromName.ifBlank { "device" }
        val transfer = Store.addTransfer("in", fromId, peerName, name, size)
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
                val copied = copyExactly(session.inputStream, output, length, transfer.id)
                output.flush()
                if (copied != length)
                    throw IllegalStateException("传输不完整：$copied/$length 字节")
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
            return json(mapOf("ok" to false, "error" to (e.message ?: "error")), Response.Status.BAD_REQUEST)
        } finally {
            releaseWakeLock(wake)
        }
    }

    /**
     * 浏览器端（例如用 PC 浏览器打开手机地址）发起发送：先把请求体落到缓存文件，再交给前台服务发送。
     */
    private fun sendToPeer(session: IHTTPSession): Response {
        val peerId = session.parameters["peer"]?.firstOrNull().orEmpty()
        if (peerId.isEmpty())
            return json(mapOf("ok" to false, "error" to "缺少 peer 参数"), Response.Status.BAD_REQUEST)
        if (!Store.peers.containsKey(peerId))
            return json(mapOf("ok" to false, "error" to "未知设备"), Response.Status.NOT_FOUND)

        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length <= 0)
            return json(mapOf("ok" to false, "error" to "缺少 Content-Length，不支持分块上传"),
                Response.Status.LENGTH_REQUIRED)
        val declared = session.headers["x-file-size"]?.toLongOrNull() ?: length
        if (declared > 0 && declared != length)
            return json(mapOf("ok" to false, "error" to "文件大小不一致"), Response.Status.BAD_REQUEST)

        val name = sanitize(Uri.decode(session.headers["x-file-name"] ?: "file"))
        val tmp = File(ctx.cacheDir, "out-${UUID.randomUUID()}.bin")
        try {
            tmp.outputStream().use { output ->
                val copied = copyExactly(session.inputStream, output, length, "")
                output.flush()
                if (copied != length) throw IllegalStateException("上传不完整：$copied/$length 字节")
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return json(mapOf("ok" to false, "error" to (e.message ?: "读取请求体失败")),
                Response.Status.BAD_REQUEST)
        }

        val service = Runtime.service
        if (service == null) {
            runCatching { tmp.delete() }
            return json(mapOf("ok" to false, "error" to "后台服务未运行"), Response.Status.SERVICE_UNAVAILABLE)
        }
        service.sendLocalFile(peerId, tmp, name, length)
        return json(mapOf("ok" to true))
    }

    /** 只读 length 个字节，避免在 keep-alive 连接上阻塞到超时。 */
    private fun copyExactly(input: InputStream, output: OutputStream, length: Long, transferId: String): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var reported = 0L
        while (total < length) {
            val want = minOf(buffer.size.toLong(), length - total).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
            if (transferId.isNotEmpty() && total - reported >= 512 * 1024) {
                reported = total
                Store.updateTransfer(transferId, total)
            }
        }
        if (transferId.isNotEmpty()) Store.updateTransfer(transferId, total)
        return total
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
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val input = session.inputStream
        if (length in 0..4L * 1024 * 1024) {
            val data = ByteArray(length.toInt())
            var offset = 0
            while (offset < length) {
                val read = input.read(data, offset, (length - offset).toInt())
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

    companion object {
        private const val MIME_PLAIN = "text/plain; charset=utf-8"
    }
}
