package app.ikev2manager.data

import app.ikev2manager.domain.RankingConfig
import app.ikev2manager.network.Stage1Config
import app.ikev2manager.vpn.Stage2Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val concurrency: Int = 20,
    val dnsTimeoutMs: Int = 4000,
    val pingCount: Int = 4,
    val pingTimeoutSec: Int = 2,
    val udpProbes: Boolean = true,
    val tcpProbe: Boolean = true,
    val vpnTimeoutSec: Int = 20,
    val tunnelSamples: Int = 8,
    val retries: Int = 1,
    val ranking: RankingConfig = RankingConfig(),
) {
    fun stage1() = Stage1Config(concurrency, dnsTimeoutMs.toLong(), pingCount, pingTimeoutSec, 2000, udpProbes, tcpProbe)
    fun stage2() = Stage2Config(vpnTimeoutSec * 1000L, tunnelSamples, retries)

    fun toMap(): Map<String, String> = mapOf(
        "theme" to theme.name,
        "concurrency" to concurrency.toString(),
        "dnsTimeoutMs" to dnsTimeoutMs.toString(),
        "pingCount" to pingCount.toString(),
        "pingTimeoutSec" to pingTimeoutSec.toString(),
        "udpProbes" to udpProbes.toString(),
        "tcpProbe" to tcpProbe.toString(),
        "vpnTimeoutSec" to vpnTimeoutSec.toString(),
        "tunnelSamples" to tunnelSamples.toString(),
        "retries" to retries.toString(),
        "vpnShare" to ranking.vpnShare.toString(),
        "wConnect" to ranking.wConnect.toString(),
        "wLatency" to ranking.wLatency.toString(),
        "wLoss" to ranking.wLoss.toString(),
        "wStability" to ranking.wStability.toString(),
    )

    companion object {
        fun fromMap(m: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun i(k: String, def: Int, r: IntRange) = (m[k]?.toIntOrNull() ?: def).coerceIn(r)
            fun dbl(k: String, def: Double, lo: Double, hi: Double) = (m[k]?.toDoubleOrNull() ?: def).coerceIn(lo, hi)
            fun b(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            return AppSettings(
                theme = m["theme"]?.let { v -> runCatching { ThemeMode.valueOf(v) }.getOrNull() } ?: d.theme,
                concurrency = i("concurrency", d.concurrency, 1..50),
                dnsTimeoutMs = i("dnsTimeoutMs", d.dnsTimeoutMs, 500..30000),
                pingCount = i("pingCount", d.pingCount, 1..20),
                pingTimeoutSec = i("pingTimeoutSec", d.pingTimeoutSec, 1..10),
                udpProbes = b("udpProbes", d.udpProbes),
                tcpProbe = b("tcpProbe", d.tcpProbe),
                vpnTimeoutSec = i("vpnTimeoutSec", d.vpnTimeoutSec, 5..90),
                tunnelSamples = i("tunnelSamples", d.tunnelSamples, 3..30),
                retries = i("retries", d.retries, 0..3),
                ranking = RankingConfig(
                    vpnShare = dbl("vpnShare", d.ranking.vpnShare, 0.5, 1.0),
                    wConnect = dbl("wConnect", d.ranking.wConnect, 0.0, 1.0),
                    wLatency = dbl("wLatency", d.ranking.wLatency, 0.0, 1.0),
                    wLoss = dbl("wLoss", d.ranking.wLoss, 0.0, 1.0),
                    wStability = dbl("wStability", d.ranking.wStability, 0.0, 1.0),
                ),
            )
        }
    }
}

class SettingsRepository(private val dao: SettingsDao) {
    val flow: Flow<AppSettings> = dao.observeAll().map { list ->
        AppSettings.fromMap(list.associate { it.skey to it.svalue })
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val cur = AppSettings.fromMap(dao.getAll().associate { it.skey to it.svalue })
        val next = transform(cur)
        dao.putAll(next.toMap().map { SettingEntity(it.key, it.value) })
    }
}
