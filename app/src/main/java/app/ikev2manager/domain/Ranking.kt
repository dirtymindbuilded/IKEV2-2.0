package app.ikev2manager.domain

data class RankingConfig(
    /** Share of the overall score that comes from the real VPN test (rest = preliminary network quality). */
    val vpnShare: Double = 0.85,
    val wConnect: Double = 0.20,
    val wLatency: Double = 0.30,
    val wLoss: Double = 0.25,
    val wStability: Double = 0.25,
    /** Servers without a real VPN test can never score above this, however good their ping is. */
    val untestedCap: Double = 40.0,
)

data class PrelimInput(val dnsOk: Boolean, val avgMs: Double?, val lossPct: Double?, val udpResponded: Boolean)

data class VpnInput(
    val success: Boolean,
    val connectMs: Long?,
    val tunnelAvgMs: Double?,
    val lossPct: Double?,
    val stability: Double?,
)

data class Scores(val prelim: Double?, val vpn: Double?, val overall: Double)

enum class SortMode(val label: String) {
    BEST_OVERALL("Best overall"),
    LOWEST_TUNNEL_LATENCY("Lowest tunnel latency"),
    LOWEST_CONNECT_TIME("Lowest connect time"),
    LOWEST_LOSS("Lowest packet loss"),
    HIGHEST_STABILITY("Highest stability"),
    SUCCESSFUL_ONLY("Successful only"),
}

data class Metrics(
    val overall: Double,
    val vpnSuccess: Boolean,
    val tunnelAvgMs: Double?,
    val connectMs: Long?,
    val lossPct: Double?,
    val stability: Double?,
)

object Ranking {
    /** 100 at `best` or better, 0 at `worst` or worse, linear in between. */
    fun linear(v: Double, best: Double, worst: Double): Double =
        (100.0 * (worst - v) / (worst - best)).coerceIn(0.0, 100.0)

    fun prelim(p: PrelimInput?): Double? {
        if (p == null) return null
        if (!p.dnsOk) return 0.0
        // ICMP missing/blocked is not penalised: neutral base score.
        val base = if (p.avgMs != null && p.lossPct != null) {
            0.6 * linear(p.avgMs, 20.0, 400.0) + 0.4 * linear(p.lossPct, 0.0, 50.0)
        } else 50.0
        return (base + if (p.udpResponded) 10.0 else 0.0).coerceIn(0.0, 100.0)
    }

    fun vpn(v: VpnInput?, cfg: RankingConfig = RankingConfig()): Double? {
        if (v == null) return null
        if (!v.success) return 0.0
        val sum = (cfg.wConnect + cfg.wLatency + cfg.wLoss + cfg.wStability).takeIf { it > 0.0 } ?: 1.0
        val c = v.connectMs?.let { linear(it.toDouble(), 1500.0, 15000.0) } ?: 0.0
        val l = v.tunnelAvgMs?.let { linear(it, 30.0, 500.0) } ?: 0.0
        val p = v.lossPct?.let { linear(it, 0.0, 20.0) } ?: 0.0
        val s = v.stability ?: 0.0
        return (cfg.wConnect * c + cfg.wLatency * l + cfg.wLoss * p + cfg.wStability * s) / sum
    }

    fun scores(p: PrelimInput?, v: VpnInput?, cfg: RankingConfig = RankingConfig()): Scores {
        val pr = prelim(p)
        val vp = vpn(v, cfg)
        val overall = when {
            vp != null -> (1.0 - cfg.vpnShare) * (pr ?: 0.0) + cfg.vpnShare * vp
            pr != null -> pr * cfg.untestedCap / 100.0
            else -> 0.0
        }
        return Scores(pr, vp, overall)
    }
}

fun <T> sortRows(rows: List<T>, mode: SortMode, metrics: (T) -> Metrics): List<T> {
    fun asc(sel: (Metrics) -> Double?): Comparator<T> =
        compareBy<T, Double?>(nullsLast()) { sel(metrics(it)) }
    return when (mode) {
        SortMode.BEST_OVERALL -> rows.sortedByDescending { metrics(it).overall }
        SortMode.LOWEST_TUNNEL_LATENCY -> rows.sortedWith(asc { it.tunnelAvgMs })
        SortMode.LOWEST_CONNECT_TIME -> rows.sortedWith(asc { it.connectMs?.toDouble() })
        SortMode.LOWEST_LOSS -> rows.sortedWith(asc { it.lossPct })
        SortMode.HIGHEST_STABILITY -> rows.sortedByDescending { metrics(it).stability ?: -1.0 }
        SortMode.SUCCESSFUL_ONLY -> rows.filter { metrics(it).vpnSuccess }.sortedByDescending { metrics(it).overall }
    }
}
