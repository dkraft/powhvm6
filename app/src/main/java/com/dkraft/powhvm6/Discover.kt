package com.dkraft.powhvm6

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class Found(
    val ip: String,
    val name: String,
    val model: String,
    val httpPort: Int
)

object Discover {
    private val MAGIC = "powhvm6-discover".toByteArray()

    private fun broadcastAddr(): InetAddress {
        return InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
    }

    /** Send one broadcast and wait for the first reply. */
    fun broadcast(timeoutMs: Long = 1500): Found? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs.toInt()
            socket.broadcast = true
            val target = InetSocketAddress(broadcastAddr(), 28776)
            socket.send(DatagramPacket(MAGIC, MAGIC.size, target))
            val reply = ByteArray(1024)
            val pkt = DatagramPacket(reply, reply.size)
            try {
                socket.receive(pkt)
            } catch (e: java.net.SocketTimeoutException) {
                return null
            }
            val ip = pkt.address.hostAddress ?: return null
            return parse(String(pkt.data, 0, pkt.length))?.copy(ip = ip)
        } catch (e: Exception) {
            return null
        } finally {
            socket?.close()
        }
    }

    /** Scan a whole /24 in parallel. [prefix] is the first three octets, e.g. "10.0.0". */
    fun scanSubnet(prefix: String, timeoutMs: Long = 2500): List<Found> {
        val base = prefix.trimEnd('.')
        val results = java.util.Collections.synchronizedList(mutableListOf<Found>())
        val pool = Executors.newFixedThreadPool(32)
        val latch = CountDownLatch(254)
        for (i in 1..254) {
            val ip = "$base.$i"
            pool.execute {
                val f = probe(ip, timeoutMs)
                if (f != null) results.add(f)
                latch.countDown()
            }
        }
        try {
            latch.await(8, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
        }
        pool.shutdown()
        return results
    }

    private fun probe(ip: String, timeoutMs: Long): Found? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs.toInt()
            val target = InetSocketAddress(InetAddress.getByName(ip), 28776)
            socket.send(DatagramPacket(MAGIC, MAGIC.size, target))
            val reply = ByteArray(1024)
            val pkt = DatagramPacket(reply, reply.size)
            socket.receive(pkt)
            return parse(String(pkt.data, 0, pkt.length))?.copy(ip = ip)
        } catch (e: Exception) {
            return null
        } finally {
            socket?.close()
        }
    }

    private fun parse(json: String): Found? {
        return try {
            val o = JSONObject(json)
            Found(
                ip = "",
                name = o.optString("name"),
                model = o.optString("model"),
                httpPort = o.optInt("http_port", HostAddr.DEFAULT_PORT)
            )
        } catch (e: Exception) {
            null
        }
    }
}
