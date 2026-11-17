package app.ikev2manager.vpn

import android.content.Intent
import android.net.Network
import app.ikev2manager.domain.FailureCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnPhase { IDLE, AWAITING_CONSENT, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

class VpnStateMachine {
    private val _phase = MutableStateFlow(VpnPhase.IDLE)
    val phase: StateFlow<VpnPhase> = _phase.asStateFlow()

    /** Returns false (and keeps the current phase) if the transition is illegal. */
    fun move(to: VpnPhase): Boolean {
        while (true) {
            val cur = _phase.value
            if (cur == to) return true
            if (!allowed(cur, to)) return false
            if (_phase.compareAndSet(cur, to)) return true
        }
    }

    fun reset() { _phase.value = VpnPhase.IDLE }

    companion object {
        private val table: Map<VpnPhase, Set<VpnPhase>> = mapOf(
            VpnPhase.IDLE to setOf(VpnPhase.AWAITING_CONSENT, VpnPhase.CONNECTING),
            VpnPhase.AWAITING_CONSENT to setOf(VpnPhase.CONNECTING, VpnPhase.FAILED, VpnPhase.IDLE),
            VpnPhase.CONNECTING to setOf(VpnPhase.CONNECTED, VpnPhase.FAILED, VpnPhase.DISCONNECTING, VpnPhase.IDLE),
            VpnPhase.CONNECTED to setOf(VpnPhase.DISCONNECTING, VpnPhase.FAILED, VpnPhase.IDLE),
            VpnPhase.DISCONNECTING to setOf(VpnPhase.IDLE, VpnPhase.FAILED),
            VpnPhase.FAILED to setOf(VpnPhase.IDLE, VpnPhase.AWAITING_CONSENT, VpnPhase.CONNECTING),
        )

        fun allowed(from: VpnPhase, to: VpnPhase): Boolean = to in table[from].orEmpty()
    }
}

data class VpnCredentials(val username: String, val password: String) {
    override fun toString() = "VpnCredentials(username=$username, password=***)"
}

data class ConnectRequest(val hostname: String, val credentials: VpnCredentials, val timeoutMs: Long)

sealed interface ConnectOutcome {
    data class Connected(val connectMs: Long, val network: Network?) : ConnectOutcome
    data class Failed(val category: FailureCategory, val detail: String) : ConnectOutcome
    data class HandedOff(val detail: String) : ConnectOutcome
}

data class EngineCapabilities(
    val inAppConnect: Boolean,
    val stateMonitoring: Boolean,
    val authMethods: List<String>,
    val notes: String,
)

interface VpnEngine {
    val id: String
    val displayName: String
    val capabilities: EngineCapabilities
    val phase: StateFlow<VpnPhase>
    suspend fun connect(req: ConnectRequest): ConnectOutcome
    suspend fun disconnect()
    fun tunnelNetwork(): Network?
    fun isTunnelUp(): Boolean
}

/** Asks the foreground Activity to show Android's own VPN consent dialog. */
fun interface VpnConsent {
    suspend fun request(intent: Intent): Boolean
}
