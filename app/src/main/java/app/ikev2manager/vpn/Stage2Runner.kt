package app.ikev2manager.vpn

import app.ikev2manager.domain.FailureCategory
import app.ikev2manager.domain.Stats
import app.ikev2manager.domain.Target
import app.ikev2manager.network.NetworkStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class Stage2Config(
    val timeoutMs: Long = 20_000,
    val samples: Int = 8,
    val retries: Int = 1,
    val settleMs: Long = 500,
)

data class VpnTestResult(
    val serverId: Long,
    val success: Boolean,
    val failure: FailureCategory?,
    val detail: String,
    val connectMs: Long?,
    val summary: Stats.Summary?,
    val stability: Double?,
)

/** Strictly sequential real-tunnel tests: one platform VPN at a time, disconnect before every next server. */
class Stage2Runner(
    private val engine: VpnEngine,
    private val probe: TunnelProbe,
    private val net: NetworkStatus,
) {
    suspend fun run(
        targets: List<Target>,
        creds: VpnCredentials,
        cfg: Stage2Config,
        onStart: (index: Int, target: Target) -> Unit = { _, _ -> },
        onResult: suspend (VpnTestResult) -> Unit,
    ) {
        engine.disconnect()
        try {
            for ((i, t) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                onStart(i, t)
                val r = testOne(t, creds, cfg)
                onResult(r)
                if (r.failure == FailureCategory.USER_CANCELLED) break
                if (r.failure == FailureCategory.NETWORK_UNAVAILABLE && !net.isOnline()) break
            }
        } finally {
            withContext(NonCancellable) { engine.disconnect() }
        }
    }

    private suspend fun testOne(t: Target, creds: VpnCredentials, cfg: Stage2Config): VpnTestResult {
        var last: VpnTestResult? = null
        for (attempt in 0..cfg.retries) {
            val r = attemptOnce(t, creds, cfg)
            last = r
            if (r.success || !retryable(r.failure)) return r
            delay(cfg.settleMs)
        }
        return last!!
    }

    private fun retryable(c: FailureCategory?) =
        c == FailureCategory.TIMEOUT || c == FailureCategory.NETWORK_UNAVAILABLE

    private fun fail(t: Target, c: FailureCategory, detail: String, connectMs: Long? = null) =
        VpnTestResult(t.id, false, c, detail, connectMs, null, null)

    private suspend fun attemptOnce(t: Target, creds: VpnCredentials, cfg: Stage2Config): VpnTestResult {
        if (!net.isOnline()) return fail(t, FailureCategory.NETWORK_UNAVAILABLE, "No underlying network")
        engine.disconnect()
        if (cfg.settleMs > 0) delay(cfg.settleMs)
        val outcome = engine.connect(ConnectRequest(t.hostname, creds, cfg.timeoutMs))
        return try {
            when (outcome) {
                is ConnectOutcome.Failed -> fail(t, outcome.category, outcome.detail)
                is ConnectOutcome.HandedOff -> fail(t, FailureCategory.UNSUPPORTED, outcome.detail)
                is ConnectOutcome.Connected -> {
                    val d = probe.run(outcome.network ?: engine.tunnelNetwork(), cfg.samples, 2500)
                    if (!d.internetOk) {
                        fail(t, FailureCategory.NO_INTERNET, "Tunnel up but no traffic passed", outcome.connectMs)
                    } else {
                        VpnTestResult(t.id, true, null, "OK", outcome.connectMs, d.summary, d.stability)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { engine.disconnect() }
        }
    }
}
