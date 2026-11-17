package app.ikev2manager.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Ikev2VpnProfile
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnManager
import android.net.VpnProfileState
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.ikev2manager.domain.ErrorClassifier
import app.ikev2manager.domain.FailureCategory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Primary engine: Android platform VPN (VpnManager + Ikev2VpnProfile), EAP-MSCHAPv2 only.
 * Server certificates are validated against the system trust store (no custom CA, no validation bypass).
 * Only one platform VPN can be active; connect() is serialised by a mutex.
 */
class NativeIkev2Engine(context: Context, private val consent: VpnConsent) : VpnEngine {
    private val appContext = context.applicationContext
    private val vpnManager: VpnManager? = appContext.getSystemService(VpnManager::class.java)
    private val cm: ConnectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val sm = VpnStateMachine()
    private val mutex = Mutex()

    @Volatile private var lastEvent: FailureCategory? = null
    @Volatile private var lastEventDetail: String? = null
    @Volatile private var network: Network? = null
    @Volatile private var receiverRegistered = false

    override val id = "native-ikev2"
    override val displayName = "Android native IKEv2/IPsec"
    override val capabilities = EngineCapabilities(
        inAppConnect = true,
        stateMonitoring = true,
        authMethods = listOf("EAP-MSCHAPv2 (username/password)"),
        notes = "Uses the Android platform VPN. One tunnel at a time; Android shows its own VPN consent dialog. " +
            "Servers needing PSK, certificates, or legacy algorithms are not supported by the platform API.",
    )
    override val phase = sm.phase

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent ?: return
            val category = intent.categories?.firstOrNull()
            val code = if (intent.hasExtra(EXTRA_ERROR_CODE)) intent.getIntExtra(EXTRA_ERROR_CODE, -1) else null
            lastEvent = ErrorClassifier.classifyEvent(category, code)
            lastEventDetail = "event=$category code=$code class=${intent.getIntExtra(EXTRA_ERROR_CLASS, -1)}"
        }
    }

    private fun ensureReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_VPN_MANAGER_EVENT).apply {
            addCategory(ErrorClassifier.CATEGORY_IKE_ERROR)
            addCategory(ErrorClassifier.CATEGORY_NETWORK_ERROR)
            addCategory(ErrorClassifier.CATEGORY_DEACTIVATED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun profileState(vm: VpnManager): Int? = vm.provisionedVpnProfileState?.state

    override suspend fun connect(req: ConnectRequest): ConnectOutcome = mutex.withLock {
        val vm = vpnManager ?: return@withLock ConnectOutcome.Failed(FailureCategory.UNSUPPORTED, "VpnManager unavailable")
        ensureReceiver()
        sm.reset()
        lastEvent = null
        lastEventDetail = null
        network = null

        val profile = try {
            Ikev2VpnProfile.Builder(req.hostname, req.credentials.username)
                .setAuthUsernamePassword(req.credentials.username, req.credentials.password, null)
                .setBypassable(false)
                .setRequiresInternetValidation(true)
                .build()
        } catch (e: Exception) {
            sm.move(VpnPhase.CONNECTING); sm.move(VpnPhase.FAILED)
            return@withLock ConnectOutcome.Failed(FailureCategory.UNKNOWN, "Invalid VPN profile: ${e.message}")
        }

        sm.move(VpnPhase.AWAITING_CONSENT)
        val consentIntent = try {
            vm.provisionVpnProfile(profile)
        } catch (e: Exception) {
            sm.move(VpnPhase.FAILED)
            return@withLock ConnectOutcome.Failed(FailureCategory.UNKNOWN, "provisionVpnProfile failed: ${e.message}")
        }
        if (consentIntent != null && !consent.request(consentIntent)) {
            sm.move(VpnPhase.FAILED)
            return@withLock ConnectOutcome.Failed(FailureCategory.USER_CANCELLED, "VPN permission was not granted")
        }

        sm.move(VpnPhase.CONNECTING)
        val t0 = SystemClock.elapsedRealtime()
        try {
            vm.startProvisionedVpnProfile()
        } catch (e: Exception) {
            sm.move(VpnPhase.FAILED)
            return@withLock ConnectOutcome.Failed(FailureCategory.UNKNOWN, "startProvisionedVpnProfile failed: ${e.message}")
        }

        val outcome = withTimeoutOrNull(req.timeoutMs) { awaitOutcome(vm, t0) }
        if (outcome != null) return@withLock outcome
        sm.move(VpnPhase.FAILED)
        val cat = lastEvent ?: FailureCategory.TIMEOUT
        ConnectOutcome.Failed(cat, "No established tunnel within ${req.timeoutMs / 1000}s. ${lastEventDetail.orEmpty()}".trim())
    }

    private suspend fun awaitOutcome(vm: VpnManager, t0: Long): ConnectOutcome {
        while (true) {
            when (profileState(vm)) {
                VpnProfileState.STATE_CONNECTED -> {
                    val net = awaitVpnNetwork()
                    network = net
                    sm.move(VpnPhase.CONNECTED)
                    return ConnectOutcome.Connected(SystemClock.elapsedRealtime() - t0, net)
                }
                VpnProfileState.STATE_FAILED -> {
                    delay(300) // let the error broadcast arrive
                    sm.move(VpnPhase.FAILED)
                    return ConnectOutcome.Failed(lastEvent ?: FailureCategory.UNKNOWN, "VPN entered failed state. ${lastEventDetail.orEmpty()}".trim())
                }
            }
            val ev = lastEvent
            if (ErrorClassifier.isTerminal(ev)) {
                sm.move(VpnPhase.FAILED)
                return ConnectOutcome.Failed(ev!!, lastEventDetail ?: ErrorClassifier.explain(ev))
            }
            delay(200)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitVpnNetwork(): Network? {
        repeat(25) {
            val n = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (n != null) return n
            delay(200)
        }
        return null
    }

    override suspend fun disconnect() {
        withContext(NonCancellable) {
            val vm = vpnManager ?: return@withContext
            sm.move(VpnPhase.DISCONNECTING)
            try {
                vm.stopProvisionedVpnProfile()
            } catch (_: Exception) {
            }
            withTimeoutOrNull(6000) {
                while (true) {
                    val s = profileState(vm)
                    if (s != VpnProfileState.STATE_CONNECTED && s != VpnProfileState.STATE_CONNECTING) break
                    delay(150)
                }
            }
            network = null
            sm.reset()
        }
    }

    override fun tunnelNetwork(): Network? = network

    override fun isTunnelUp(): Boolean =
        vpnManager?.let { profileState(it) == VpnProfileState.STATE_CONNECTED } ?: false

    private companion object {
        // Literal values of the VpnManager constants, kept as strings to avoid hard-linking unverified symbols.
        const val ACTION_VPN_MANAGER_EVENT = "android.net.action.VPN_MANAGER_EVENT"
        const val EXTRA_ERROR_CLASS = "android.net.extra.ERROR_CLASS"
        const val EXTRA_ERROR_CODE = "android.net.extra.ERROR_CODE"
    }
}
