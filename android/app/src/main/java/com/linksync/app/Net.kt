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
        try {
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                override fun contentLength(): Long = if (size > 0) size else -1L
                override fun writeTo(sink: BufferedSink) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            total += read
                            Store.updateTransfer(transferId, total)
                        }
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
                    Store.finishTransfer(transferId, null, "HTTP ${response.code}")
                } else {
                    Store.finishTransfer(transferId, parsePath(text), null)
                }
            }
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "send file: ${e.message}")
            Store.finishTransfer(transferId, null, e.message ?: "发送失败")
        }
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
