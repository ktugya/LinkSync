package com.linksync.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections

class Discovery(
    private val ctx: Context,
    private val udpPort: Int = 47822
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiveJob: Job? = null
    private var announceJob: Job? = null

    fun start(scope: CoroutineScope) {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("linksync")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "multicast lock: ${e.message}")
        }

        socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
            bind(InetSocketAddress(udpPort))
        }
        running = true
        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
        announceJob = scope.launch(Dispatchers.IO) { announceLoop() }
        Log.i(Runtime.TAG, "discovery started udp/$udpPort")
    }

    private suspend fun receiveLoop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break
                val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                val obj = JsonParser.parseString(text).asJsonObject
                val id = obj.get("id")?.asString ?: continue
                if (id == Store.id) continue
                val name = obj.get("name")?.asString ?: id
                val platform = obj.get("platform")?.asString ?: "android"
                val port = obj.get("port")?.asInt ?: 47821
                val ip = packet.address?.hostAddress ?: continue
                Store.touchPeer(id, name, platform, ip, port)
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) {
                    Log.w(Runtime.TAG, "udp receive: ${e.message}")
                    delay(500)
                }
            }
        }
    }

    private suspend fun announceLoop() {
        while (running) {
            try {
                val payload = Runtime.gson.toJson(
                    mapOf(
                        "id" to Store.id,
                        "name" to Store.name,
                        "platform" to "android",
                        "port" to Runtime.port,
                        "v" to 1
                    )
                ).toByteArray(StandardCharsets.UTF_8)

                send(payload, InetAddress.getByName("255.255.255.255"))
                for (address in directedBroadcasts()) {
                    try { send(payload, address) } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                Log.w(Runtime.TAG, "udp announce: ${e.message}")
            }
            delay(3000)
        }
    }

    private fun send(payload: ByteArray, address: InetAddress) {
        val packet = DatagramPacket(payload, payload.size, address, udpPort)
        socket?.send(packet)
    }

    private fun directedBroadcasts(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia: InterfaceAddress in ni.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (b.address.size == 4) result.add(b)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun stop() {
        running = false
        try { receiveJob?.cancel() } catch (_: Exception) { }
        try { announceJob?.cancel() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) { }
        multicastLock = null
    }
}
