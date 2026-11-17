package app.ikev2manager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.ikev2manager.data.ThemeMode
import app.ikev2manager.vpn.VpnPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ScanConfigScreen(vm: MainViewModel, nav: NavHostController) {
    val st by vm.settings.collectAsStateWithLifecycle()
    val select by vm.stage2Select.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()

    ScreenScaffold("Scan configuration", onBack = { nav.popBackStack() }) { pad ->
        Page(pad) {
            Panel("Stage 1: network scan (preliminary)") {
                IntSetting("Concurrency", st.concurrency, 1..50) { v -> vm.updateSettings { it.copy(concurrency = v) } }
                IntSetting("DNS timeout", st.dnsTimeoutMs, 500..15000, " ms") { v -> vm.updateSettings { it.copy(dnsTimeoutMs = v) } }
                IntSetting("Ping count", st.pingCount, 1..10) { v -> vm.updateSettings { it.copy(pingCount = v) } }
                IntSetting("Ping timeout", st.pingTimeoutSec, 1..5, " s") { v -> vm.updateSettings { it.copy(pingTimeoutSec = v) } }
                SwitchSetting("UDP 500/4500 probes", st.udpProbes) { v -> vm.updateSettings { it.copy(udpProbes = v) } }
                SwitchSetting("TCP 443 probe", st.tcpProbe) { v -> vm.updateSettings { it.copy(tcpProbe = v) } }
            }
            Panel("Stage 2: real VPN test (one tunnel at a time)") {
                IntSetting("Connection timeout", st.vpnTimeoutSec, 5..60, " s") { v -> vm.updateSettings { it.copy(vpnTimeoutSec = v) } }
                IntSetting("Tunnel probes", st.tunnelSamples, 3..20) { v -> vm.updateSettings { it.copy(tunnelSamples = v) } }
                IntSetting("Retries on timeout", st.retries, 0..3) { v -> vm.updateSettings { it.copy(retries = v) } }
                Text("Servers to test", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stage2Select.entries.forEach { s ->
                        FilterChip(selected = select == s, onClick = { vm.stage2Select.value = s }, label = { Text(s.label) })
                    }
                }
                Text(
                    "Top N uses the preliminary score of servers whose DNS resolved. Keep the screen on during the test; " +
                        "the test runs only while the app is open.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (!run.running) {
                Button(onClick = { vm.startStage1() }, Modifier.fillMaxWidth()) { Text("Run stage 1") }
                Button(onClick = { vm.startStage2() }, Modifier.fillMaxWidth()) { Text("Run stage 2 (${select.label})") }
            } else {
                OutlinedButton(onClick = { vm.cancelRun() }, Modifier.fillMaxWidth()) { Text("Cancel running scan") }
            }
        }
    }
}

@Composable
fun CredentialsScreen(vm: MainViewModel, nav: NavHostController) {
    val has by vm.hasCredentials.collectAsStateWithLifecycle()
    var user by remember { mutableStateOf(vm.storedUsername()) }
    var pass by remember { mutableStateOf("") }

    ScreenScaffold("Credentials", onBack = { nav.popBackStack() }) { pad ->
        Page(pad) {
            Panel("VPN account (used for every server)") {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it },
                    label = { Text(if (has) "Password (stored; type to replace)" else "Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = user.isNotBlank() && pass.isNotEmpty(),
                        onClick = { vm.saveCredentials(user, pass); pass = "" },
                    ) { Text("Save") }
                    OutlinedButton(enabled = has, onClick = { vm.deleteCredentials(); user = ""; pass = "" }) { Text("Delete") }
                }
                Text(
                    if (has) "Stored encrypted with a non-exportable Android Keystore key." else "Nothing stored.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Panel("Compatibility") {
                Text("Supported: IKEv2/IPsec with EAP-MSCHAPv2 (username + password), server certificate checked against the system trust store.")
                Text("Not supported by the Android platform VPN: pre-shared keys, client certificates, legacy algorithms (3DES, MODP1024).")
                Text("For such servers use the external strongSwan hand-off from a server's detail page.")
            }
        }
    }
}

@Composable
fun ConnectionScreen(vm: MainViewModel, nav: NavHostController) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val seconds by produceState(0L, conn.startedAtMs, conn.phase) {
        while (true) {
            value = if (conn.phase == VpnPhase.CONNECTED && conn.startedAtMs > 0) (System.currentTimeMillis() - conn.startedAtMs) / 1000 else 0
            delay(1000)
        }
    }
    ScreenScaffold("Connection") { pad ->
        Page(pad) {
            Panel("Status") {
                KeyValue("Server", conn.serverName ?: "-")
                KeyValue("State", conn.phase.name.lowercase())
                if (conn.phase == VpnPhase.CONNECTED) {
                    KeyValue("Duration", String.format("%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60))
                    KeyValue("Connect time", conn.connectMs?.let { "$it ms" } ?: "-")
                }
                conn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            conn.tunnel?.let { t ->
                Panel("Tunnel diagnostics") {
                    KeyValue("Internet", if (t.internetOk) "reachable" else "no traffic")
                    KeyValue("HTTPS check", if (t.httpOk) "OK" else "failed")
                    KeyValue("Latency", fmtMs(t.summary.avg))
                    KeyValue("Loss", fmtPct(t.summary.lossPct))
                    KeyValue("Stability", fmtScore(t.stability))
                }
            }
            if (conn.phase == VpnPhase.CONNECTED) {
                OutlinedButton(onClick = { vm.runDiagnostics() }, Modifier.fillMaxWidth()) { Text("Re-run diagnostics") }
                Button(onClick = { vm.disconnect() }, Modifier.fillMaxWidth()) { Text("Disconnect") }
            } else if (conn.phase == VpnPhase.CONNECTING) {
                Button(onClick = { vm.disconnect() }, Modifier.fillMaxWidth()) { Text("Cancel") }
            } else {
                Button(onClick = { vm.connectBest() }, Modifier.fillMaxWidth()) { Text("Connect to best server") }
                OutlinedButton(onClick = { nav.navigate("servers") }, Modifier.fillMaxWidth()) { Text("Choose a server") }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val st by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(ExportKind.CSV) }

    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { vm.writeTo(uri, vm.exportText(pending)) }
    }
    val txtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch { vm.writeTo(uri, vm.exportText(pending)) }
    }
    val prefSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.writeTo(uri, vm.settingsJson()) }
    }
    val prefLoader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.readFrom(uri)?.let { vm.importSettingsJson(it) } }
    }

    fun export(kind: ExportKind) {
        pending = kind
        if (kind == ExportKind.CSV) csvSaver.launch("ikev2-servers.csv") else txtSaver.launch("ikev2-${kind.name.lowercase()}.txt")
    }

    ScreenScaffold("Settings") { pad ->
        Page(pad) {
            Panel("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(selected = st.theme == m, onClick = { vm.updateSettings { it.copy(theme = m) } }, label = { Text(m.name.lowercase()) })
                    }
                }
            }
            Panel("Ranking") {
                val r = st.ranking
                IntSetting("Real VPN test share", (r.vpnShare * 100).toInt(), 50..100, "%") { v -> vm.updateSettings { it.copy(ranking = r.copy(vpnShare = v / 100.0)) } }
                IntSetting("Weight: connect time", (r.wConnect * 100).toInt(), 0..100) { v -> vm.updateSettings { it.copy(ranking = r.copy(wConnect = v / 100.0)) } }
                IntSetting("Weight: tunnel latency", (r.wLatency * 100).toInt(), 0..100) { v -> vm.updateSettings { it.copy(ranking = r.copy(wLatency = v / 100.0)) } }
                IntSetting("Weight: packet loss", (r.wLoss * 100).toInt(), 0..100) { v -> vm.updateSettings { it.copy(ranking = r.copy(wLoss = v / 100.0)) } }
                IntSetting("Weight: stability", (r.wStability * 100).toInt(), 0..100) { v -> vm.updateSettings { it.copy(ranking = r.copy(wStability = v / 100.0)) } }
                Text(
                    "Overall = (1 - share) x preliminary network score + share x VPN score. VPN score = weighted connect time, " +
                        "tunnel latency, loss and stability of a real tunnel (0 if the test failed). Untested servers are capped at " +
                        "${r.untestedCap.toInt()}.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Panel("Credentials") {
                OutlinedButton(onClick = { nav.navigate("credentials") }) { Text("Manage credentials") }
                OutlinedButton(onClick = { vm.deleteCredentials() }) { Text("Delete stored credentials") }
            }
            Panel("Export (never includes passwords)") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { export(ExportKind.CSV) }) { Text("CSV") }
                    OutlinedButton(onClick = { export(ExportKind.SUMMARY) }) { Text("Summary") }
                    OutlinedButton(onClick = { export(ExportKind.BEST) }) { Text("Best") }
                    OutlinedButton(onClick = { export(ExportKind.FAILED) }) { Text("Failed") }
                }
            }
            Panel("Preferences file (no credentials, no servers)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { prefSaver.launch("ikev2-preferences.json") }) { Text("Export") }
                    OutlinedButton(onClick = { prefLoader.launch(arrayOf("application/json", "text/plain")) }) { Text("Import") }
                }
            }
        }
    }
}
