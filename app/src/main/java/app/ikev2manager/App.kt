package app.ikev2manager

import android.app.Application
import android.content.Context
import android.content.Intent
import app.ikev2manager.data.AndroidKeystoreCipher
import app.ikev2manager.data.AppDatabase
import app.ikev2manager.data.CredentialStore
import app.ikev2manager.data.Repository
import app.ikev2manager.data.SettingsRepository
import app.ikev2manager.network.AndroidNetworkStatus
import app.ikev2manager.network.DnsProbe
import app.ikev2manager.network.PingProbe
import app.ikev2manager.network.Stage1Scanner
import app.ikev2manager.network.TcpProbe
import app.ikev2manager.network.UdpProbe
import app.ikev2manager.vpn.NativeIkev2Engine
import app.ikev2manager.vpn.StrongSwanHandoffEngine
import app.ikev2manager.vpn.TunnelDiagnostics
import app.ikev2manager.vpn.VpnConsent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Bridges the engine (no Activity) to the Activity result API that shows Android's VPN consent dialog. */
class ConsentBridge : VpnConsent {
    private val _requests = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val requests: SharedFlow<Intent> = _requests.asSharedFlow()

    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    override suspend fun request(intent: Intent): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = d
        _requests.emit(intent)
        return withTimeoutOrNull(60_000) { d.await() } ?: false
    }

    fun deliver(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }
}

class AppContainer(val context: Context) {
    val db = AppDatabase.build(context)
    val repo = Repository(db)
    val settings = SettingsRepository(db.settings())
    val credentials = CredentialStore(File(context.filesDir, "credentials.bin"), AndroidKeystoreCipher())
    val consent = ConsentBridge()
    val network = AndroidNetworkStatus(context)
    val native = NativeIkev2Engine(context, consent)
    val strongSwan = StrongSwanHandoffEngine(context)
    val tunnel = TunnelDiagnostics()
    val scanner = Stage1Scanner(DnsProbe(), PingProbe(), UdpProbe(), TcpProbe())
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
