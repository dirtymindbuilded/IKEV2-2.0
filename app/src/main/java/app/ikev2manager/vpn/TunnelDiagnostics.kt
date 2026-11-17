package app.ikev2manager.vpn

import android.net.Network
import app.ikev2manager.domain.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

data class TunnelResult(
    val internetOk: Boolean,
    val httpOk: Boolean,
    val summary: Stats.Summary,
    val stability: Double,
)

interface TunnelProbe {
    suspend fun run(network: Network?, samples: Int, timeoutMs: Int): TunnelResult
}

/**
 * Measures through the VPN network only (sockets are created from that Network).
 * Third-party endpoints contacted: Cloudflare 1.1.1.1:443, Google 8.8.8.8:443 and gstatic generate_204.
 * No credentials or identifiers are sent.
 */
class TunnelDiagnostics(
    private val targets: List<Pair<String, Int>> = listOf("1.1.1.1" to 443, "8.8.8.8" to 443),
    private val httpUrl: String = "https://connectivitycheck.gstatic.com/generate_204",
) : TunnelProbe {

    override suspend fun run(network: Network?, samples: Int, timeoutMs: Int): TunnelResult =
        withContext(Dispatchers.IO) {
            if (network == null) {
                val s = Stats.summarize(emptyList(), samples)
                return@withContext TunnelResult(false, false, s, 0.0)
            }
            val ms = ArrayList<Double>()
            repeat(samples) { i ->
                ensureActive()
                val (host, port) = targets[i % targets.size]
                tcpConnectMs(network, host, port, timeoutMs)?.let { ms.add(it) }
                delay(100)
            }
            val http = httpCheck(network, timeoutMs)
            val summary = Stats.summarize(ms, samples)
            TunnelResult(ms.isNotEmpty(), http, summary, Stats.stability(summary))
        }

    private fun tcpConnectMs(network: Network, host: String, port: Int, timeoutMs: Int): Double? = try {
        network.socketFactory.createSocket().use { s ->
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - t0) / 1_000_000.0
        }
    } catch (e: IOException) {
        null
    }

    private fun httpCheck(network: Network, timeoutMs: Int): Boolean = try {
        val c = network.openConnection(URL(httpUrl)) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = false
        c.useCaches = false
        try {
            c.responseCode == 204
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        false
    }
}
