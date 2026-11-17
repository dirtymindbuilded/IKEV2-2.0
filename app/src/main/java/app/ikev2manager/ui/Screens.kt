package app.ikev2manager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.ikev2manager.data.ServerRow
import app.ikev2manager.data.reachable
import app.ikev2manager.domain.SortMode
import app.ikev2manager.vpn.VpnPhase

@Composable
fun DashboardScreen(vm: MainViewModel, nav: NavHostController) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val hasCreds by vm.hasCredentials.collectAsStateWithLifecycle()
    val select by vm.stage2Select.collectAsStateWithLifecycle()

    ScreenScaffold("IKEv2 Manager") { pad ->
        Page(pad) {
            if (!online) Panel { Text("No network connection (airplane mode or offline).", color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Servers", rows.size.toString(), Modifier.weight(1f))
                StatTile("Reachable*", rows.count { it.stage1?.reachable() == true }.toString(), Modifier.weight(1f))
                StatTile("VPN verified", rows.count { it.vpn?.success == true }.toString(), Modifier.weight(1f))
            }
            Text(
                "*Preliminary only: DNS resolved and some probe answered. This does not prove IKEv2 works.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Panel("Connection") {
                if (conn.phase == VpnPhase.CONNECTED || conn.phase == VpnPhase.CONNECTING) {
                    Text("${conn.phase.name.lowercase()}: ${conn.serverName.orEmpty()}")
                    OutlinedButton(onClick = { nav.navigate("connection") }) { Text("Open") }
                } else {
                    Text("Not connected")
                }
            }
            val last = rows.mapNotNull { it.stage1?.timestamp }.maxOrNull()
            Panel("Last scan") { Text(if (last == null) "Never" else fmtTime(last)) }

            if (!hasCreds) {
                Panel("Credentials missing") {
                    Text("Real VPN tests need a username and password.")
                    Button(onClick = { nav.navigate("credentials") }) { Text("Enter credentials") }
                }
            }

            if (run.running) {
                Panel(if (run.stage == 1) "Quick scan" else "VPN test") {
                    if (run.total > 0) LinearProgressIndicator(progress = { run.done.toFloat() / run.total }, modifier = Modifier.fillMaxWidth())
                    Text("${run.done} / ${run.total}  ${run.current.orEmpty()}")
                    OutlinedButton(onClick = { vm.cancelRun() }) { Text("Cancel") }
                }
            } else {
                Button(onClick = { vm.startStage1() }, Modifier.fillMaxWidth()) { Text("Quick scan (stage 1)") }
                Button(onClick = { vm.startStage2() }, Modifier.fillMaxWidth()) { Text("VPN test: ${select.label} (stage 2)") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.connectBest() }) { Text("Connect to best") }
                    OutlinedButton(onClick = { nav.navigate("scan") }) { Text("Scan options") }
                }
            }
        }
    }
}

@Composable
fun ServerListScreen(vm: MainViewModel, nav: NavHostController) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importUri(uri) }

    ScreenScaffold("Servers", actions = {
        Box {
            TextButton(onClick = { sortMenu = true }) { Text(sort.label) }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortMode.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.label) }, onClick = { vm.sort.value = m; sortMenu = false })
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showAdd = true }) { Text("Add") }
                OutlinedButton(onClick = { vm.importClipboard() }) { Text("Paste") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.selectAll() }) { Text("Select all") }
                TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                if (selected.isNotEmpty()) TextButton(onClick = { confirmDelete = true }) { Text("Delete (${selected.size})") }
            }
            if (rows.isEmpty()) {
                Text("No servers yet. Add one, paste a list, or import a TXT/CSV file.", Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.server.id }) { r ->
                    ServerItem(r, r.server.id in selected, { vm.toggleSelect(r.server.id) }) { nav.navigate("server/${r.server.id}") }
                }
            }
        }
    }

    if (showAdd) {
        var host by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, label = { Text("Hostname") }, singleLine = true)
                    OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.addManual(host.trim(), label.trim()); showAdd = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.size} servers?") },
            text = { Text("Their scan and VPN test history is deleted too.") },
            confirmButton = { TextButton(onClick = { vm.deleteSelected(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ServerItem(r: ServerRow, checked: Boolean, onCheck: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() })
            Column(Modifier.weight(1f)) {
                Text(r.server.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = r.server.label?.let { "${r.server.hostname} - $it" } ?: r.server.hostname
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.statusLine(), style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtScore(r.scores.overall), style = MaterialTheme.typography.titleMedium)
                Text("net ${fmtScore(r.scores.prelim)} / vpn ${fmtScore(r.scores.vpn)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun ServerDetailScreen(vm: MainViewModel, nav: NavHostController, id: Long) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.server.id == id }
    val scans by remember(id) { vm.scanHistory(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tests by remember(id) { vm.vpnHistory(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var edit by remember { mutableStateOf(false) }

    ScreenScaffold(row?.server?.name ?: "Server", onBack = { nav.popBackStack() }) { pad ->
        if (row == null) {
            Text("Server not found", Modifier.padding(pad).padding(16.dp))
            return@ScreenScaffold
        }
        Page(pad) {
            Panel("Server") {
                KeyValue("Hostname", row.server.hostname)
                KeyValue("Label", row.server.label ?: "-")
                KeyValue("Tags", row.server.tags.ifBlank { "-" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { edit = true }) { Text("Edit") }
                    OutlinedButton(onClick = { vm.deleteIds(listOf(id)); nav.popBackStack() }) { Text("Delete") }
                }
            }
            Panel("Scores") {
                KeyValue("Preliminary network", fmtScore(row.scores.prelim))
                KeyValue("Actual VPN", fmtScore(row.scores.vpn))
                KeyValue("Overall", fmtScore(row.scores.overall))
                Text(
                    "Overall weights the real tunnel test heavily. Servers never tested through a tunnel are capped, " +
                        "so a low ping alone cannot rank a server as excellent.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            val s = row.stage1
            Panel("Latest network scan (preliminary)") {
                if (s == null) Text("Not scanned") else {
                    KeyValue("Scanned", fmtTime(s.timestamp))
                    KeyValue("DNS", if (s.dnsOk) "OK, ${s.dnsMs} ms" else "Failed: ${s.error.orEmpty()}")
                    KeyValue("IPv4", s.ipv4.ifBlank { "-" })
                    KeyValue("IPv6", s.ipv6.ifBlank { "-" })
                    KeyValue("ICMP", if (!s.pingSupported) "not available" else if (s.pingAvg == null) "no reply" else "avg ${fmtMs(s.pingAvg)} (min ${fmtMs(s.pingMin)}, max ${fmtMs(s.pingMax)})")
                    KeyValue("Packet loss", fmtPct(s.lossPct))
                    KeyValue("UDP 500", s.udp500 ?: "-")
                    KeyValue("UDP 4500", s.udp4500 ?: "-")
                    KeyValue("TCP 443", s.tcp443?.toString() ?: "-")
                    Text("UDP/ICMP/TCP results are hints, not proof that IKEv2 works.", style = MaterialTheme.typography.labelSmall)
                }
            }
            val v = row.vpn
            Panel("Latest VPN test (real tunnel)") {
                if (v == null) Text("Not tested") else {
                    KeyValue("Tested", fmtTime(v.timestamp))
                    KeyValue("Result", if (v.success) "Success" else "Failed: ${v.failure ?: "unknown"}")
                    if (!v.success) Text(v.detail.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    KeyValue("Connect time", v.connectMs?.let { "$it ms" } ?: "-")
                    KeyValue("Tunnel latency", fmtMs(v.tunnelAvg))
                    KeyValue("Tunnel loss", fmtPct(v.lossPct))
                    KeyValue("Stability", fmtScore(v.stability))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.testSingle(id) }) { Text("Test via VPN") }
                Button(onClick = { vm.connect(id); nav.navigate("connection") }) { Text("Connect") }
            }
            OutlinedButton(onClick = { vm.openInStrongSwan(id) }) { Text("Open in strongSwan app (external)") }
            Panel("History") {
                if (tests.isEmpty() && scans.isEmpty()) Text("No history yet")
                tests.take(10).forEach {
                    Text("${fmtTime(it.timestamp)}  VPN ${if (it.success) "OK ${fmtMs(it.tunnelAvg)}" else it.failure ?: "failed"}", style = MaterialTheme.typography.bodySmall)
                }
                if (tests.isNotEmpty() && scans.isNotEmpty()) HorizontalDivider()
                scans.take(10).forEach {
                    Text("${fmtTime(it.timestamp)}  scan ${if (it.dnsOk) fmtMs(it.pingAvg) else "DNS fail"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (edit && row != null) {
        var name by remember { mutableStateOf(row.server.name) }
        var label by remember { mutableStateOf(row.server.label.orEmpty()) }
        var tags by remember { mutableStateOf(row.server.tags) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Edit server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(label, { label = it }, label = { Text("Label / country") }, singleLine = true)
                    OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.updateServer(row, name, label, tags); edit = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } },
        )
    }
}
