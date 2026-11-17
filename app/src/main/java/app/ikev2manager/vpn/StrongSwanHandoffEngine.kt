package app.ikev2manager.vpn

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Network
import androidx.core.content.FileProvider
import app.ikev2manager.domain.FailureCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Fallback path: hand a .sswan profile to the official strongSwan Android app (org.strongswan.android).
 *
 * Why not embed strongSwan: its Android app and native libraries are GPLv2; linking them would force this
 * whole app to GPLv2 and requires bundling/maintaining NDK-built charon libraries. Not done in this release.
 *
 * What this engine does NOT do: connect in-app, monitor state, or run Stage 2 tests. The profile contains the
 * username only; strongSwan asks the user for the password. Untested on a device.
 */
class StrongSwanHandoffEngine(context: Context) : VpnEngine {
    private val appContext = context.applicationContext
    private val sm = VpnStateMachine()

    override val id = "strongswan-handoff"
    override val displayName = "strongSwan app (external hand-off)"
    override val capabilities = EngineCapabilities(
        inAppConnect = false,
        stateMonitoring = false,
        authMethods = listOf("IKEv2 EAP (password entered inside strongSwan)"),
        notes = "Opens the official strongSwan app with a generated profile. Connection state, timing and " +
            "tunnel diagnostics are not available for this path, so it cannot be used for Stage 2 tests.",
    )
    override val phase = sm.phase

    override suspend fun connect(req: ConnectRequest): ConnectOutcome = withContext(Dispatchers.IO) {
        try {
            val dir = File(appContext.cacheDir, "profiles").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, req.hostname.replace(Regex("[^A-Za-z0-9.-]"), "_") + ".sswan")
            file.writeText(buildProfileJson(req.hostname, req.credentials.username))
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.strongswan.profile")
                .setPackage("org.strongswan.android")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            ConnectOutcome.HandedOff("Profile sent to strongSwan. Enter the password there and connect manually.")
        } catch (e: ActivityNotFoundException) {
            ConnectOutcome.Failed(FailureCategory.UNSUPPORTED, "strongSwan app (org.strongswan.android) is not installed.")
        } catch (e: Exception) {
            ConnectOutcome.Failed(FailureCategory.UNKNOWN, "Hand-off failed: ${e.message}")
        }
    }

    override suspend fun disconnect() = Unit
    override fun tunnelNetwork(): Network? = null
    override fun isTunnelUp(): Boolean = false

    companion object {
        /** Password is deliberately never written to the profile. */
        fun buildProfileJson(hostname: String, username: String, uuid: String = UUID.randomUUID().toString()): String =
            JSONObject()
                .put("uuid", uuid)
                .put("name", hostname)
                .put("type", "ikev2-eap")
                .put("remote", JSONObject().put("addr", hostname))
                .put("local", JSONObject().put("eap_id", username))
                .toString(2)
    }
}
