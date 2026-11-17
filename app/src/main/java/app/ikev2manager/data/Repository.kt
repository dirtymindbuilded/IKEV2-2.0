package app.ikev2manager.data

import app.ikev2manager.domain.Metrics
import app.ikev2manager.domain.ParsedServer
import app.ikev2manager.domain.PrelimInput
import app.ikev2manager.domain.Ranking
import app.ikev2manager.domain.RankingConfig
import app.ikev2manager.domain.Scores
import app.ikev2manager.domain.VpnInput
import app.ikev2manager.network.Stage1Result
import app.ikev2manager.network.UdpStatus
import app.ikev2manager.vpn.VpnTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

data class ServerRow(
    val server: ServerEntity,
    val stage1: Stage1Entity?,
    val vpn: VpnTestEntity?,
    val scores: Scores,
)

fun Stage1Entity.toPrelim() = PrelimInput(
    dnsOk, pingAvg, lossPct,
    udp500 == UdpStatus.RESPONDED.name || udp4500 == UdpStatus.RESPONDED.name,
)

fun VpnTestEntity.toVpn() = VpnInput(success, connectMs, tunnelAvg, lossPct, stability)

fun Stage1Entity.reachable(): Boolean =
    dnsOk && (pingAvg != null || udp500 == UdpStatus.RESPONDED.name ||
        udp4500 == UdpStatus.RESPONDED.name || tcp443 == true)

fun ServerRow.metrics() = Metrics(
    overall = scores.overall,
    vpnSuccess = vpn?.success == true,
    tunnelAvgMs = vpn?.takeIf { it.success }?.tunnelAvg,
    connectMs = vpn?.takeIf { it.success }?.connectMs,
    lossPct = vpn?.takeIf { it.success }?.lossPct ?: stage1?.lossPct,
    stability = vpn?.takeIf { it.success }?.stability,
)

class Repository(private val db: AppDatabase) {
    fun rows(ranking: Flow<RankingConfig>): Flow<List<ServerRow>> =
        combine(db.servers().observeAll(), db.scans().observeLatest(), db.vpnTests().observeLatest(), ranking) { s, a, b, cfg ->
            val scanBy = a.associateBy { it.serverId }
            val vpnBy = b.associateBy { it.serverId }
            s.map { srv ->
                val sc = scanBy[srv.id]
                val vp = vpnBy[srv.id]
                ServerRow(srv, sc, vp, Ranking.scores(sc?.toPrelim(), vp?.toVpn(), cfg))
            }
        }.flowOn(Dispatchers.Default)

    fun scanHistory(id: Long) = db.scans().history(id)
    fun vpnHistory(id: Long) = db.vpnTests().history(id)

    suspend fun existingHostnames(): Set<String> = db.servers().hostnames().toSet()

    suspend fun importParsed(list: List<ParsedServer>): Int {
        var inserted = 0
        for (chunk in list.chunked(500)) {
            val ids = db.servers().insertAll(chunk.map { ServerEntity(hostname = it.hostname, name = it.name, label = it.label) })
            inserted += ids.count { it != -1L }
        }
        return inserted
    }

    suspend fun update(s: ServerEntity) = db.servers().update(s)

    suspend fun delete(ids: Collection<Long>) {
        for (chunk in ids.toList().chunked(500)) db.servers().deleteByIds(chunk)
    }

    suspend fun saveStage1(r: Stage1Result) {
        val sum = r.pingSummary
        db.scans().insert(
            Stage1Entity(
                serverId = r.serverId,
                timestamp = System.currentTimeMillis(),
                dnsOk = r.dns.ok,
                dnsMs = r.dns.ms,
                ipv4 = r.dns.ipv4.joinToString(","),
                ipv6 = r.dns.ipv6.joinToString(","),
                pingSupported = r.ping?.supported ?: false,
                pingSent = r.ping?.sent ?: 0,
                pingAvg = sum?.avg,
                pingMin = sum?.min,
                pingMax = sum?.max,
                lossPct = sum?.lossPct,
                udp500 = r.udp500?.name,
                udp4500 = r.udp4500?.name,
                tcp443 = r.tcp443,
                error = r.dns.error ?: r.ping?.error,
            ),
        )
    }

    suspend fun saveVpn(r: VpnTestResult) {
        db.vpnTests().insert(
            VpnTestEntity(
                serverId = r.serverId,
                timestamp = System.currentTimeMillis(),
                success = r.success,
                failure = r.failure?.name,
                detail = r.detail,
                connectMs = r.connectMs,
                tunnelAvg = r.summary?.avg,
                tunnelMin = r.summary?.min,
                tunnelMax = r.summary?.max,
                lossPct = r.summary?.lossPct,
                stability = r.stability,
            ),
        )
    }

    suspend fun pruneOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        db.scans().prune(cutoff)
        db.vpnTests().prune(cutoff)
    }
}
