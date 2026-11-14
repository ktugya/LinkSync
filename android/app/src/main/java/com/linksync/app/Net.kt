package com.linksync.app

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class PeerInfo(val id: String, val name: String, val platform: String, val port: Int)

object Net {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaTypeOrNull()

    private fun base(peer: Peer): String {
        val host = if (peer.ip.contains(':')) "[${peer.ip}]" else peer.ip
        return "http://$host:${peer.port}"
    }

    fun hash(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun pushClipboard(peer: Peer, text: String): Boolean {
        return try {
            val payload = Runtime.gson.toJson(
                mapOf(
                    "text" to text,
                    "hash" to hash(text),
                    "origin" to Store.id,
                    "originName" to Store.name,
                    "ts" to Store.now()
                )
            )
            val req = Request.Builder()
                .url("${base(peer)}/api/clipboard")
                .post(payload.toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "push clipboard: ${e.message}")
            false
        }
    }

    fun pushText(peer: Peer, msg: MessageEntry): Boolean {
        return try {
            val payload = Runtime.gson.toJson(
                mapOf(
                    "id" to msg.id,
                    "from" to msg.from,
                    "fromName" to msg.fromName,
                    "text" to msg.text,
                    "ts" to msg.ts
                )
            )
            val req = Request.Builder()
                .url("${base(peer)}/api/text")
                .post(payload.toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "push text: ${e.message}")
            false
        }
    }

    fun sendFile(ctx: Context, peer: Peer, uri: Uri, name: String, size: Long, transferId: String) {
        sendStream(peer, name, size, transferId) { ctx.contentResolver.openInputStream(uri) }
    }

    /** 发送缓存目录里的临时文件（浏览器端 /api/send 使用）。 */
    fun sendLocalFile(peer: Peer, file: File, name: String, size: Long, transferId: String) {
        sendStream(peer, name, size, transferId) { file.inputStream() }
    }

    private fun sendStream(
        peer: Peer,
        name: String,
        size: Long,
        transferId: String,
        open: () -> InputStream?
    ) {
        if (size <= 0) {
            Store.finishTransfer(transferId, null, "无法确定文件大小")
            return
        }
        try {
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

                // 必须给出确定长度：接收端（PC / Android）都不做分块解码。
                override fun contentLength(): Long = size

                override fun writeTo(sink: BufferedSink) {
                    val input = open() ?: throw IllegalStateException("无法读取文件")
                    input.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            total += read
                            Store.updateTransfer(transferId, total)
                        }
                        if (total != size) throw IllegalStateException("文件大小与声明不一致：$total/$size")
                    }
                }
            }

            val req = Request.Builder()
                .url("${base(peer)}/api/upload")
                .header("X-File-Name", Uri.encode(name))
                .header("X-File-Size", size.toString())
                .header("X-From-Id", Store.id)
                .header("X-From-Name", Uri.encode(Store.name))
                .post(body)
                .build()

            client.newCall(req).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Store.finishTransfer(transferId, null, describeError(response.code, text))
                } else {
                    Store.finishTransfer(transferId, parsePath(text), null)
                }
            }
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "send file: ${e.message}")
            Store.finishTransfer(transferId, null, e.message ?: "发送失败")
        }
    }

    /** 把服务端返回的 error 字段带出来，而不是只显示 "HTTP 500"。 */
    private fun describeError(code: Int, body: String): String {
        val detail = try {
            JsonParser.parseString(body).asJsonObject.get("error")?.asString
        } catch (_: Exception) {
            null
        }
        return if (detail.isNullOrBlank()) "HTTP $code" else "HTTP $code：$detail"
    }

    fun fetchInfo(ip: String, port: Int): PeerInfo? {
        return try {
            val host = if (ip.contains(':')) "[$ip]" else ip
            val req = Request.Builder().url("http://$host:$port/api/info").build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                Runtime.gson.fromJson(response.body?.string(), PeerInfo::class.java)
            }
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "fetch info: ${e.message}")
            null
        }
    }

    private fun parsePath(json: String): String {
        return try {
            JsonParser.parseString(json).asJsonObject.get("path")?.asString ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
