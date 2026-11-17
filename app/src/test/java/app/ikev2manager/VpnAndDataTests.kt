package app.ikev2manager

import app.ikev2manager.data.Repository
import app.ikev2manager.data.SecretCipher
import app.ikev2manager.data.CredentialStore
import app.ikev2manager.data.ServerEntity
import app.ikev2manager.data.ServerRow
import app.ikev2manager.data.Stage1Entity
import app.ikev2manager.data.VpnTestEntity
import app.ikev2manager.data.AppSettings
import app.ikev2manager.domain.FailureCategory
import app.ikev2manager.domain.Scores
import app.ikev2manager.domain.Stats
import app.ikev2manager.domain.Target
import app.ikev2manager.export.Exporter
import app.ikev2manager.network.DnsProbe
import app.ikev2manager.network.NetworkStatus
import app.ikev2manager.network.PingProbe
import app.ikev2manager.network.UdpProbe
import app.ikev2manager.vpn.ConnectOutcome
import app.ikev2manager.vpn.ConnectRequest
import app.ikev2manager.vpn.EngineCapabilities
import app.ikev2manager.vpn.Stage2Config
import app.ikev2manager.vpn.Stage2Runner
import app.ikev2manager.vpn.StrongSwanHandoffEngine
import app.ikev2manager.vpn.TunnelProbe
import app.ikev2manager.vpn.TunnelResult
import app.ikev2manager.vpn.VpnCredentials
import app.ikev2manager.vpn.VpnEngine
import app.ikev2manager.vpn.VpnPhase
import app.ikev2manager.vpn.VpnStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException

class VpnStateMachineTest {
    @Test fun legalLifecycle() {
        val sm = VpnStateMachine()
        assertTrue(sm.move(VpnPhase.AWAITING_CONSENT))
        assertTrue(sm.move(VpnPhase.CONNECTING))
        assertTrue(sm.move(VpnPhase.CONNECTED))
        assertTrue(sm.move(VpnPhase.DISCONNECTING))
        assertTrue(sm.move(VpnPhase.IDLE))
        assertEquals(VpnPhase.IDLE, sm.phase.value)
    }

    @Test fun illegalTransitionsAreRejected() {
        val sm = VpnStateMachine()
        assertFalse(sm.move(VpnPhase.CONNECTED)) // cannot be connected without connecting first
        assertEquals(VpnPhase.IDLE, sm.phase.value)
        sm.move(VpnPhase.CONNECTING)
        sm.move(VpnPhase.FAILED)
        assertFalse(sm.move(VpnPhase.CONNECTED))
        sm.reset()
        assertEquals(VpnPhase.IDLE, sm.phase.value)
    }
}

class CredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // Reversible fake cipher: output never contains the plaintext bytes in order.
    private val fake = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(0x7F) + plain.reversedArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray().reversedArray()
    }

    @Test fun roundTripAndNoPlaintextOnDisk() {
        val f = File(tmp.root, "cred.bin")
        val store = CredentialStore(f, fake)
        store.save(VpnCredentials("alice", "s3cret-pass"))
        val raw = String(f.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("s3cret-pass"))
        assertFalse(raw.contains("alice"))
        assertEquals(VpnCredentials("alice", "s3cret-pass"), store.load())
    }

    @Test fun clearAndToStringNeverLeakPassword() {
        val f = File(tmp.root, "cred.bin")
        val store = CredentialStore(f, fake)
        store.save(VpnCredentials("bob", "hunter2"))
        assertFalse(VpnCredentials("bob", "hunter2").toString().contains("hunter2"))
        store.clear()
        assertFalse(store.hasCredentials())
        assertEquals(null, store.load())
    }

    @Test fun undecryptableBlobYieldsNull() {
        val f = File(tmp.root, "cred.bin")
        f.writeBytes(byteArrayOf(1, 2, 3))
        val broken = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(blob: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals(null, CredentialStore(f, broken).load())
    }
}

class ProbeTests {
    @Test fun parsesPingOutput() {
        val out = """
            64 bytes from 1.2.3.4: icmp_seq=1 ttl=57 time=12.3 ms
            64 bytes from 1.2.3.4: icmp_seq=2 ttl=57 time=20 ms
            --- 1.2.3.4 ping statistics ---
        """.trimIndent()
        assertEquals(listOf(12.3, 20.0), PingProbe.parseTimes(out))
    }

    @Test fun ikeHeaderShape() {
        val h500 = UdpProbe.buildIkeHeader(500)
        assertEquals(28, h500.size)
        assertEquals(0x20.toByte(), h500[17])
        assertEquals(34.toByte(), h500[18])
        val h4500 = UdpProbe.buildIkeHeader(4500)
        assertEquals(32, h4500.size)
        assertEquals(0, h4500[0].toInt())
    }

    @Test fun dnsSuccessAndFailure() = runTest {
        val ok = DnsProbe { arrayOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)), InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })) }
        val r = ok.resolve("x.example.com", 1000)
        assertTrue(r.ok)
        assertEquals(listOf("10.0.0.1"), r.ipv4)
        assertEquals(1, r.ipv6.size)

        val bad = DnsProbe { throw UnknownHostException("nope.example.com") }
        val f = bad.resolve("nope.example.com", 1000)
        assertFalse(f.ok)
        assertTrue(f.error!!.contains("nope"))
    }
}

class Stage2RunnerTest {
    private class FakeEngine(val outcomes: Map<String, List<ConnectOutcome>>) : VpnEngine {
        override val id = "fake"
        override val displayName = "fake"
        override val capabilities = EngineCapabilities(true, true, emptyList(), "")
        override val phase: StateFlow<VpnPhase> = MutableStateFlow(VpnPhase.IDLE)
        val calls = mutableListOf<String>()
        var disconnects = 0
        private val counters = HashMap<String, Int>()
        override suspend fun connect(req: ConnectRequest): ConnectOutcome {
            calls.add(req.hostname)
            val i = counters.getOrDefault(req.hostname, 0)
            counters[req.hostname] = i + 1
            val list = outcomes.getValue(req.hostname)
            return list[minOf(i, list.size - 1)]
        }
        override suspend fun disconnect() { disconnects++ }
        override fun tunnelNetwork() = null
        override fun isTunnelUp() = false
    }

    private class FakeProbe(val internet: Boolean) : TunnelProbe {
        override suspend fun run(network: android.net.Network?, samples: Int, timeoutMs: Int): TunnelResult {
            val s = Stats.summarize(if (internet) listOf(40.0, 42.0, 44.0) else emptyList(), 3)
            return TunnelResult(internet, internet, s, Stats.stability(s))
        }
    }

    private class FakeNet(var online: Boolean = true) : NetworkStatus { override fun isOnline() = online }

    private val creds = VpnCredentials("u", "p")
    private val cfg = Stage2Config(timeoutMs = 1000, samples = 3, retries = 1, settleMs = 0)
    private fun targets(vararg h: String) = h.mapIndexed { i, s -> Target(i + 1L, s) }

    @Test fun testsSequentiallyAndDisconnectsBetweenServers() = runTest {
        val engine = FakeEngine(mapOf(
            "a" to listOf(ConnectOutcome.Connected(1200, null)),
            "b" to listOf(ConnectOutcome.Failed(FailureCategory.AUTH, "bad creds")),
        ))
        val results = mutableListOf<app.ikev2manager.vpn.VpnTestResult>()
        Stage2Runner(engine, FakeProbe(true), FakeNet()).run(targets("a", "b"), creds, cfg) { results.add(it) }
        assertEquals(listOf("a", "b"), engine.calls)
        assertTrue(results[0].success)
        assertEquals(1200L, results[0].connectMs)
        assertEquals(FailureCategory.AUTH, results[1].failure)
        assertTrue(engine.disconnects >= 3)
    }

    @Test fun authFailureIsNotRetriedButTimeoutIs() = runTest {
        val engine = FakeEngine(mapOf(
            "auth" to listOf(ConnectOutcome.Failed(FailureCategory.AUTH, "x")),
            "slow" to listOf(ConnectOutcome.Failed(FailureCategory.TIMEOUT, "t"), ConnectOutcome.Connected(900, null)),
        ))
        val results = mutableListOf<app.ikev2manager.vpn.VpnTestResult>()
        Stage2Runner(engine, FakeProbe(true), FakeNet()).run(targets("auth", "slow"), creds, cfg) { results.add(it) }
        assertEquals(listOf("auth", "slow", "slow"), engine.calls)
        assertTrue(results[1].success)
    }

    @Test fun tunnelWithoutTrafficIsNoInternetNotSuccess() = runTest {
        val engine = FakeEngine(mapOf("a" to listOf(ConnectOutcome.Connected(800, null))))
        val results = mutableListOf<app.ikev2manager.vpn.VpnTestResult>()
        Stage2Runner(engine, FakeProbe(false), FakeNet()).run(targets("a"), creds, cfg) { results.add(it) }
        assertFalse(results[0].success)
        assertEquals(FailureCategory.NO_INTERNET, results[0].failure)
    }

    @Test fun consentDenialStopsTheWholeRun() = runTest {
        val engine = FakeEngine(mapOf(
            "a" to listOf(ConnectOutcome.Failed(FailureCategory.USER_CANCELLED, "denied")),
            "b" to listOf(ConnectOutcome.Connected(1, null)),
        ))
        val results = mutableListOf<app.ikev2manager.vpn.VpnTestResult>()
        Stage2Runner(engine, FakeProbe(true), FakeNet()).run(targets("a", "b"), creds, cfg) { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(listOf("a"), engine.calls)
    }

    @Test fun offlineIsReportedAsNetworkUnavailable() = runTest {
        val engine = FakeEngine(mapOf("a" to listOf(ConnectOutcome.Connected(1, null))))
        val results = mutableListOf<app.ikev2manager.vpn.VpnTestResult>()
        Stage2Runner(engine, FakeProbe(true), FakeNet(online = false)).run(targets("a"), creds, cfg) { results.add(it) }
        assertEquals(FailureCategory.NETWORK_UNAVAILABLE, results[0].failure)
        assertTrue(engine.calls.isEmpty())
    }
}

class ExportAndProfileTests {
    private fun row(name: String, host: String, vpnOk: Boolean?) = ServerRow(
        ServerEntity(1, host, name),
        Stage1Entity(1, 1, 0, true, 10, "1.2.3.4", "", true, 4, 20.0, 18.0, 25.0, 0.0, "RESPONDED", "NO_RESPONSE", true, null),
        vpnOk?.let { VpnTestEntity(1, 1, 0, it, if (it) null else "AUTH", "d", 1500, 50.0, 40.0, 60.0, 0.0, 95.0) },
        Scores(60.0, if (vpnOk == true) 80.0 else null, 70.0),
    )

    @Test fun csvNeverContainsCredentialsAndNeutralisesFormulas() {
        val csv = Exporter.csv(listOf(row("=HYPERLINK(\"x\")", "a.example.com", true)))
        assertTrue(csv.lines().first().startsWith("name,hostname"))
        assertFalse(csv.lowercase().contains("password"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\""))
    }

    @Test fun reportsListBestAndFailed() {
        val rows = listOf(row("ok", "ok.example.com", true), row("bad", "bad.example.com", false), row("new", "new.example.com", null))
        assertTrue(Exporter.best(rows).contains("ok.example.com"))
        assertFalse(Exporter.best(rows).contains("bad.example.com"))
        assertTrue(Exporter.failed(rows).contains("bad.example.com"))
        assertTrue(Exporter.summary(rows).contains("Servers: 3"))
    }

    @Test fun strongSwanProfileHasNoPassword() {
        val json = StrongSwanHandoffEngine.buildProfileJson("vpn.example.com", "alice", "uuid-1")
        val o = JSONObject(json)
        assertEquals("ikev2-eap", o.getString("type"))
        assertEquals("vpn.example.com", o.getJSONObject("remote").getString("addr"))
        assertEquals("alice", o.getJSONObject("local").getString("eap_id"))
        assertFalse(json.contains("pass", ignoreCase = true))
    }

    @Test fun settingsRoundTripAndClamping() {
        val s = AppSettings(concurrency = 33, retries = 2)
        assertEquals(s, AppSettings.fromMap(s.toMap()))
        assertEquals(50, AppSettings.fromMap(mapOf("concurrency" to "9999")).concurrency)
        assertEquals(AppSettings(), AppSettings.fromMap(mapOf("theme" to "garbage", "concurrency" to "x")))
    }
}
