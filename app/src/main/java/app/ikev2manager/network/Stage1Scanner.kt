package app.ikev2manager.network

import app.ikev2manager.domain.Stats
import app.ikev2manager.domain.Target
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class Stage1Config(
    val concurrency: Int = 20,
    val dnsTimeoutMs: Long = 4000,
    val pingCount: Int = 4,
    val pingTimeoutSec: Int = 2,
    val probeTimeoutMs: Int = 2000,
    val udpProbes: Boolean = true,
    val tcpProbe: Boolean = true,
)

data class Stage1Result(
    val serverId: Long,
    val dns: DnsResult,
    val ping: PingResult?,
    val udp500: UdpStatus?,
    val udp4500: UdpStatus?,
    val tcp443: Boolean?,
) {
    val pingSummary: Stats.Summary?
        get() = ping?.takeIf { it.supported }?.let { Stats.summarize(it.samples, it.sent) }
}

class Stage1Scanner(
    private val dns: DnsProbe,
    private val ping: PingProbe,
    private val udp: UdpProbe,
    private val tcp: TcpProbe,
) {
    fun scan(targets: List<Target>, cfg: Stage1Config): Flow<Stage1Result> = channelFlow {
        val gate = Semaphore(cfg.concurrency.coerceAtLeast(1))
        for (t in targets) {
            launch { gate.withPermit { send(scanOne(t, cfg)) } }
        }
    }

    private suspend fun scanOne(t: Target, cfg: Stage1Config): Stage1Result = coroutineScope {
        val d = dns.resolve(t.hostname, cfg.dnsTimeoutMs)
        if (!d.ok) return@coroutineScope Stage1Result(t.id, d, null, null, null, null)
        val addr = d.ipv4.firstOrNull() ?: d.ipv6.first()
        val u500: Deferred<UdpStatus?> = async { if (cfg.udpProbes) udp.probe(addr, 500, cfg.probeTimeoutMs) else null }
        val u4500: Deferred<UdpStatus?> = async { if (cfg.udpProbes) udp.probe(addr, 4500, cfg.probeTimeoutMs) else null }
        val tcp443: Deferred<Boolean?> = async { if (cfg.tcpProbe) tcp.open(addr, 443, cfg.probeTimeoutMs) else null }
        val p = ping.ping(addr, cfg.pingCount, cfg.pingTimeoutSec)
        Stage1Result(t.id, d, p, u500.await(), u4500.await(), tcp443.await())
    }
}
