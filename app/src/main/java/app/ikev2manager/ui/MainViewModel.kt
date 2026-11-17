package app.ikev2manager.ui

import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ikev2manager.App
import app.ikev2manager.data.AppSettings
import app.ikev2manager.data.ServerRow
import app.ikev2manager.data.metrics
import app.ikev2manager.domain.ErrorClassifier
import app.ikev2manager.domain.ServerImportParser
import app.ikev2manager.domain.SortMode
import app.ikev2manager.domain.Target
import app.ikev2manager.domain.sortRows
import app.ikev2manager.export.Exporter
import app.ikev2manager.vpn.ConnectOutcome
import app.ikev2manager.vpn.ConnectRequest
import app.ikev2manager.vpn.Stage2Runner
import app.ikev2manager.vpn.TunnelResult
import app.ikev2manager.vpn.VpnCredentials
import app.ikev2manager.vpn.VpnPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Stage2Select(val label: String, val n: Int?) {
    ALL("All", null), TOP5("Top 5", 5), TOP10("Top 10", 10), TOP20("Top 20", 20), CUSTOM("Selected", null)
}

enum class ExportKind { CSV, SUMMARY, BEST, FAILED }

data class RunState(
    val running: Boolean = false,
    val stage: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
)

data class ConnState(
    val serverId: Long? = null,
    val serverName: String? = null,
    val phase: VpnPhase = VpnPhase.IDLE,
    val startedAtMs: Long = 0,
    val connectMs: Long? = null,
    val tunnel: TunnelResult? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container

    val settings: StateFlow<AppSettings> =
        c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val sort = MutableStateFlow(SortMode.BEST_OVERALL)

    val rows: StateFlow<List<ServerRow>> =
        combine(c.repo.rows(settings.map { it.ranking }), sort) { r, s -> sortRows(r, s) { row -> row.metrics() } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _run = MutableStateFlow(RunState())
    val run: StateFlow<RunState> = _run.asStateFlow()

    private val _conn = MutableStateFlow(ConnState())
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    val stage2Select = MutableStateFlow(Stage2Select.TOP10)

    private val _hasCreds = MutableStateFlow(c.credentials.hasCredentials())
    val hasCredentials: StateFlow<Boolean> = _hasCreds.asStateFlow()

    val online: StateFlow<Boolean> =
        c.network.observe().stateIn(viewModelScope, SharingStarted.Eagerly, c.network.isOnline())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var job: Job? = null
    private var diagJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { c.repo.pruneOlderThan(90) }
    }

    private fun say(m: String) { _messages.tryEmit(m) }

    private fun busy(): Boolean =
        (job?.isActive == true) || _conn.value.phase in setOf(VpnPhase.CONNECTING, VpnPhase.CONNECTED, VpnPhase.AWAITING_CONSENT)

    // ---------- servers ----------
    fun importText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = ServerImportParser.parse(text, c.repo.existingHostnames())
            val added = c.repo.importParsed(res.valid)
            say("Added $added, duplicates ${res.duplicates}, invalid ${res.invalid.size}")
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = c.context.contentResolver.openInputStream(uri)?.use { s ->
                    String(s.readNBytes(5 * 1024 * 1024), Charsets.UTF_8)
                } ?: error("Cannot open file")
                importText(text)
            } catch (e: Exception) {
                say("Import failed: ${e.message}")
            }
        }
    }

    fun importClipboard() {
        val cm = c.context.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c.context)?.toString()
        if (text.isNullOrBlank()) say("Clipboard is empty") else importText(text)
    }

    fun addManual(host: String, label: String) = importText(if (label.isBlank()) host else "$host,$label")

    fun updateServer(row: ServerRow, name: String, label: String, tags: String) {
        viewModelScope.launch(Dispatchers.IO) {
            c.repo.update(row.server.copy(name = name.ifBlank { row.server.name }, label = label.ifBlank { null }, tags = tags.trim()))
        }
    }

    fun deleteIds(ids: Collection<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            c.repo.delete(ids)
            _selected.value = _selected.value - ids.toSet()
        }
    }

    fun deleteSelected() = deleteIds(_selected.value.toList())
    fun toggleSelect(id: Long) { _selected.value = _selected.value.let { if (id in it) it - id else it + id } }
    fun selectAll() { _selected.value = rows.value.map { it.server.id }.toSet() }
    fun clearSelection() { _selected.value = emptySet() }

    fun scanHistory(id: Long) = c.repo.scanHistory(id)
    fun vpnHistory(id: Long) = c.repo.vpnHistory(id)

    // ---------- credentials ----------
    fun storedUsername(): String = c.credentials.load()?.username.orEmpty()

    fun saveCredentials(user: String, pass: String) {
        try {
            c.credentials.save(VpnCredentials(user.trim(), pass))
            _hasCreds.value = true
            say("Credentials saved (encrypted)")
        } catch (e: Exception) {
            say("Could not save: ${e.message}")
        }
    }

    fun deleteCredentials() {
        c.credentials.clear()
        _hasCreds.value = false
        say("Credentials deleted")
    }

    // ---------- stage 1 ----------
    fun startStage1() {
        if (busy()) { say("Another operation is running"); return }
        val targets = rows.value.map { Target(it.server.id, it.server.hostname) }
        if (targets.isEmpty()) { say("Add servers first"); return }
        if (!c.network.isOnline()) { say("No network connection"); return }
        val cfg = settings.value.stage1()
        job = viewModelScope.launch {
            _run.value = RunState(true, 1, 0, targets.size)
            try {
                var done = 0
                c.scanner.scan(targets, cfg).collect { r ->
                    c.repo.saveStage1(r)
                    done++
                    _run.value = RunState(true, 1, done, targets.size)
                }
                say("Quick scan finished (${targets.size} servers)")
            } catch (e: CancellationException) {
                say("Scan cancelled")
                throw e
            } finally {
                _run.value = RunState()
            }
        }
    }

    // ---------- stage 2 ----------
    fun startStage2() {
        val all = rows.value
        val sel = stage2Select.value
        val picked = when (sel) {
            Stage2Select.ALL -> all
            Stage2Select.CUSTOM -> all.filter { it.server.id in _selected.value }
            else -> all.filter { it.stage1?.dnsOk == true }
                .sortedByDescending { it.scores.prelim ?: -1.0 }
                .take(sel.n ?: 0)
        }
        if (picked.isEmpty()) {
            say(if (sel == Stage2Select.CUSTOM) "Select servers in the list first" else "Run the quick scan first")
            return
        }
        testServers(picked)
    }

    fun testSingle(id: Long) {
        rows.value.firstOrNull { it.server.id == id }?.let { testServers(listOf(it)) }
    }

    private fun testServers(picked: List<ServerRow>) {
        if (busy()) { say("Another operation is running"); return }
        val creds = c.credentials.load() ?: run { say("Enter credentials first"); return }
        if (!c.network.isOnline()) { say("No network connection"); return }
        val names = picked.associate { it.server.id to it.server.name }
        val targets = picked.map { Target(it.server.id, it.server.hostname) }
        val cfg = settings.value.stage2()
        job = viewModelScope.launch {
            _run.value = RunState(true, 2, 0, targets.size)
            val runner = Stage2Runner(c.native, c.tunnel, c.network)
            var done = 0
            try {
                runner.run(targets, creds, cfg, onStart = { i, t ->
                    _run.value = RunState(true, 2, i, targets.size, names[t.id])
                }) { r ->
                    c.repo.saveVpn(r)
                    done++
                }
                say("VPN test finished ($done of ${targets.size})")
            } catch (e: CancellationException) {
                say("VPN test cancelled")
                throw e
            } finally {
                _run.value = RunState()
            }
        }
    }

    fun cancelRun() { job?.cancel() }

    // ---------- manual connection ----------
    fun connect(serverId: Long) {
        if (busy()) { say("Another operation is running"); return }
        val creds = c.credentials.load() ?: run { say("Enter credentials first"); return }
        val row = rows.value.firstOrNull { it.server.id == serverId } ?: return
        val timeout = settings.value.vpnTimeoutSec * 1000L
        job = viewModelScope.launch {
            _conn.value = ConnState(serverId, row.server.name, VpnPhase.CONNECTING)
            when (val o = c.native.connect(ConnectRequest(row.server.hostname, creds, timeout))) {
                is ConnectOutcome.Connected -> {
                    _conn.value = ConnState(serverId, row.server.name, VpnPhase.CONNECTED, System.currentTimeMillis(), o.connectMs)
                    runDiagnostics()
                    while (isActive && c.native.isTunnelUp()) delay(2000)
                    if (isActive) {
                        _conn.value = ConnState(error = "Connection lost")
                        c.native.disconnect()
                    }
                }
                is ConnectOutcome.Failed -> {
                    c.native.disconnect()
                    _conn.value = ConnState(error = "${o.category.label}: ${ErrorClassifier.explain(o.category)}")
                    say("Connect failed: ${o.category.label}")
                }
                is ConnectOutcome.HandedOff -> _conn.value = ConnState(error = o.detail)
            }
        }
    }

    fun connectBest() {
        val best = rows.value.filter { it.vpn?.success == true }.maxByOrNull { it.scores.overall }
        if (best == null) say("No server has passed a real VPN test yet") else connect(best.server.id)
    }

    fun runDiagnostics() {
        if (_conn.value.phase != VpnPhase.CONNECTED) return
        diagJob?.cancel()
        diagJob = viewModelScope.launch {
            val r = c.tunnel.run(c.native.tunnelNetwork(), settings.value.tunnelSamples, 2500)
            _conn.value = _conn.value.copy(tunnel = r)
        }
    }

    fun disconnect() {
        job?.cancel()
        diagJob?.cancel()
        viewModelScope.launch {
            c.native.disconnect()
            _conn.value = ConnState()
        }
    }

    fun openInStrongSwan(serverId: Long) {
        val creds = c.credentials.load() ?: run { say("Enter credentials first"); return }
        val row = rows.value.firstOrNull { it.server.id == serverId } ?: return
        viewModelScope.launch {
            when (val o = c.strongSwan.connect(ConnectRequest(row.server.hostname, creds, 0))) {
                is ConnectOutcome.HandedOff -> say(o.detail)
                is ConnectOutcome.Failed -> say(o.detail)
                is ConnectOutcome.Connected -> Unit
            }
        }
    }

    // ---------- settings / export ----------
    fun updateSettings(t: (AppSettings) -> AppSettings) {
        viewModelScope.launch(Dispatchers.IO) { c.settings.update(t) }
    }

    fun exportText(kind: ExportKind): String = when (kind) {
        ExportKind.CSV -> Exporter.csv(rows.value)
        ExportKind.SUMMARY -> Exporter.summary(rows.value)
        ExportKind.BEST -> Exporter.best(rows.value)
        ExportKind.FAILED -> Exporter.failed(rows.value)
    }

    fun settingsJson(): String = JSONObject(settings.value.toMap()).toString(2)

    fun importSettingsJson(text: String) {
        try {
            val o = JSONObject(text)
            val m = o.keys().asSequence().associateWith { o.optString(it) }
            updateSettings { AppSettings.fromMap(m) }
            say("Preferences imported")
        } catch (e: Exception) {
            say("Invalid preferences file")
        }
    }

    suspend fun writeTo(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        try {
            c.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            say("Saved")
        } catch (e: Exception) {
            say("Save failed: ${e.message}")
        }
    }

    suspend fun readFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            c.context.contentResolver.openInputStream(uri)?.use { String(it.readNBytes(1024 * 1024), Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
    }
}
