package app.ikev2manager.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

data class DnsResult(
    val ok: Boolean,
    val ms: Long,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val error: String?,
)

class DnsProbe(private val lookup: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }) {
    suspend fun resolve(host: String, timeoutMs: Long): DnsResult {
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1_000_000
        return try {
            val addrs = withTimeout(timeoutMs) { runInterruptible(Dispatchers.IO) { lookup(host) } }
            DnsResult(
                true,
                elapsed(),
                addrs.filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() },
                addrs.filterIsInstance<Inet6Address>().map { it.hostAddress.orEmpty() },
                null,
            )
        } catch (e: TimeoutCancellationException) {
            DnsResult(false, elapsed(), emptyList(), emptyList(), "DNS timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DnsResult(false, elapsed(), emptyList(), emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }
}

data class PingResult(val supported: Boolean, val sent: Int, val samples: List<Double>, val error: String?)

/** ICMP via the system `ping` binary (apps cannot open raw ICMP sockets). Blocked ICMP is not an error. */
class PingProbe {
    suspend fun ping(ip: String, count: Int, perPacketTimeoutSec: Int): PingResult = withContext(Dispatchers.IO) {
        val bin = if (ip.contains(':')) "ping6" else "ping"
        val cmd = listOf(bin, "-c", count.toString(), "-W", perPacketTimeoutSec.toString(), ip)
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return@withContext PingResult(false, count, emptyList(), e.message)
        }
        try {
            val limitMs = (count * perPacketTimeoutSec + 3) * 1000L
            val out = withTimeout(limitMs) {
                runInterruptible {
                    val text = p.inputStream.bufferedReader().readText()
                    p.waitFor()
                    text
                }
            }
            val times = parseTimes(out)
            val ranOk = times.isNotEmpty() || out.contains("packets transmitted") || out.contains("ping statistics")
            if (ranOk) PingResult(true, count, times, null) else PingResult(false, count, emptyList(), out.take(120))
        } catch (e: TimeoutCancellationException) {
            PingResult(true, count, emptyList(), "ping timed out")
        } catch (e: IOException) {
            PingResult(false, count, emptyList(), e.message)
        } finally {
            p.destroy()
        }
    }

    companion object {
        private val timeRegex = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""")
        fun parseTimes(output: String): List<Double> =
            timeRegex.findAll(output).map { it.groupValues[1].toDouble() }.toList()
    }
}

enum class UdpStatus { RESPONDED, PORT_UNREACHABLE, NO_RESPONSE, ERROR }

/**
 * Sends one IKE_SA_INIT-shaped header to UDP 500/4500. This is a PRELIMINARY diagnostic only:
 * a reply shows something answers on that port, silence proves nothing, and neither proves IKEv2 works.
 */
class UdpProbe {
    suspend fun probe(ip: String, port: Int, timeoutMs: Int): UdpStatus = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetAddress.getByName(ip), port)
                val pkt = buildIkeHeader(port)
                s.send(DatagramPacket(pkt, pkt.size))
                val buf = ByteArray(1500)
                s.receive(DatagramPacket(buf, buf.size))
                UdpStatus.RESPONDED
            }
        } catch (e: PortUnreachableException) {
            UdpStatus.PORT_UNREACHABLE
        } catch (e: SocketTimeoutException) {
            UdpStatus.NO_RESPONSE
        } catch (e: IOException) {
            UdpStatus.ERROR
        }
    }

    companion object {
        fun buildIkeHeader(port: Int): ByteArray {
            val hdr = ByteArray(28)
            val spi = ByteArray(8).also { SecureRandom().nextBytes(it) }
            System.arraycopy(spi, 0, hdr, 0, 8)
            hdr[17] = 0x20 // IKE version 2.0
            hdr[18] = 34 // IKE_SA_INIT
            hdr[19] = 0x08 // initiator flag
            hdr[27] = 28 // length
            return if (port == 4500) ByteArray(4) + hdr else hdr // non-ESP marker on 4500
        }
    }
}

class TcpProbe {
    suspend fun open(ip: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use {
                it.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: IOException) {
            false
        }
    }
}
