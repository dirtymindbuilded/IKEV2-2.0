package app.ikev2manager

import app.ikev2manager.domain.ErrorClassifier
import app.ikev2manager.domain.FailureCategory
import app.ikev2manager.domain.HostnameValidator
import app.ikev2manager.domain.Metrics
import app.ikev2manager.domain.PrelimInput
import app.ikev2manager.domain.Ranking
import app.ikev2manager.domain.RankingConfig
import app.ikev2manager.domain.ServerImportParser
import app.ikev2manager.domain.SortMode
import app.ikev2manager.domain.Stats
import app.ikev2manager.domain.VpnInput
import app.ikev2manager.domain.sortRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {
    @Test fun trimsAndRemovesDuplicatesCaseInsensitively() {
        val r = ServerImportParser.parse("  A.example.com \na.example.com\nb.example.com\n\n")
        assertEquals(listOf("a.example.com", "b.example.com"), r.valid.map { it.hostname })
        assertEquals("A.example.com", r.valid[0].name) // original spelling preserved
        assertEquals(1, r.duplicates)
    }

    @Test fun rejectsInvalidHostnames() {
        val r = ServerImportParser.parse("bad_host!\n-a.com\n1.2.3.999\nok.example.com")
        assertEquals(listOf("ok.example.com"), r.valid.map { it.hostname })
        assertEquals(3, r.invalid.size)
    }

    @Test fun parsesCsvWithHeaderLabelsAndComments() {
        val r = ServerImportParser.parse("\uFEFFhostname,label\n# comment\nus1.example.com,US East\n\"de1.example.com\",Germany")
        assertEquals(listOf("us1.example.com", "de1.example.com"), r.valid.map { it.hostname })
        assertEquals("US East", r.valid[0].label)
    }

    @Test fun skipsHostsAlreadyStored() {
        val r = ServerImportParser.parse("a.example.com\nb.example.com", existing = setOf("A.EXAMPLE.COM"))
        assertEquals(1, r.valid.size)
        assertEquals(1, r.duplicates)
    }

    @Test fun validatesIpLiteralsAndLabels() {
        assertTrue(HostnameValidator.isValid("203.0.113.5"))
        assertTrue(HostnameValidator.isValid("2001:db8::1"))
        assertTrue(HostnameValidator.isValid("vpn-1.example.co.uk"))
        assertFalse(HostnameValidator.isValid("exa mple.com"))
        assertFalse(HostnameValidator.isValid("a".repeat(64) + ".com"))
        assertFalse(HostnameValidator.isValid(""))
    }

    @Test fun handlesHundredsOfServers() {
        val text = (1..800).joinToString("\n") { "s$it.example.com" }
        assertEquals(800, ServerImportParser.parse(text).valid.size)
    }
}

class StatsTest {
    @Test fun latencyAndLossCalculations() {
        val s = Stats.summarize(listOf(10.0, 20.0, 30.0), sent = 4)
        assertEquals(20.0, s.avg!!, 1e-9)
        assertEquals(10.0, s.min!!, 1e-9)
        assertEquals(30.0, s.max!!, 1e-9)
        assertEquals(25.0, s.lossPct, 1e-9)
        assertEquals(10.0, s.jitter!!, 1e-9)
    }

    @Test fun totalLossAndNoSamples() {
        val s = Stats.summarize(emptyList(), 4)
        assertNull(s.avg)
        assertEquals(100.0, s.lossPct, 1e-9)
        assertEquals(0.0, Stats.stability(s), 1e-9)
        assertEquals(100.0, Stats.summarize(emptyList(), 0).lossPct, 1e-9)
    }

    @Test fun stabilityDropsWithLossAndJitter() {
        val steady = Stats.stability(Stats.summarize(listOf(50.0, 50.0, 50.0, 50.0), 4))
        val lossy = Stats.stability(Stats.summarize(listOf(50.0, 50.0), 4))
        val jittery = Stats.stability(Stats.summarize(listOf(10.0, 200.0, 10.0, 200.0), 4))
        assertEquals(100.0, steady, 1e-9)
        assertTrue(lossy < steady)
        assertTrue(jittery < steady)
    }
}

class RankingTest {
    private val goodPing = PrelimInput(true, 15.0, 0.0, true)
    private val okVpn = VpnInput(true, 4000, 120.0, 0.0, 90.0)

    @Test fun untestedServerIsCappedDespitePerfectPing() {
        val s = Ranking.scores(goodPing, null)
        assertTrue(s.overall <= 40.0)
        assertNull(s.vpn)
    }

    @Test fun mediocreRealTunnelBeatsPerfectPingOnly() {
        val tested = Ranking.scores(PrelimInput(true, 300.0, 5.0, false), okVpn)
        val pingOnly = Ranking.scores(goodPing, null)
        assertTrue(tested.overall > pingOnly.overall)
    }

    @Test fun failedVpnScoresLowerThanUntested() {
        val failed = Ranking.scores(goodPing, VpnInput(false, null, null, null, null))
        assertEquals(0.0, failed.vpn!!, 1e-9)
        assertTrue(failed.overall < Ranking.scores(goodPing, null).overall)
    }

    @Test fun blockedIcmpIsNotPenalisedAsBroken() {
        val p = Ranking.prelim(PrelimInput(true, null, null, false))!!
        assertEquals(50.0, p, 1e-9)
        assertEquals(0.0, Ranking.prelim(PrelimInput(false, null, null, false))!!, 1e-9)
    }

    @Test fun weightsAreConfigurable() {
        val fastConnectSlowTunnel = VpnInput(true, 1500, 450.0, 0.0, 80.0)
        val a = Ranking.vpn(fastConnectSlowTunnel, RankingConfig(wConnect = 1.0, wLatency = 0.0, wLoss = 0.0, wStability = 0.0))!!
        val b = Ranking.vpn(fastConnectSlowTunnel, RankingConfig(wConnect = 0.0, wLatency = 1.0, wLoss = 0.0, wStability = 0.0))!!
        assertTrue(a > b)
    }

    private fun m(o: Double, ok: Boolean, lat: Double?, con: Long?, loss: Double?, st: Double?) = Metrics(o, ok, lat, con, loss, st)

    @Test fun sortModes() {
        val rows = listOf(
            "a" to m(50.0, true, 200.0, 3000, 2.0, 70.0),
            "b" to m(80.0, true, 100.0, 5000, 0.0, 95.0),
            "c" to m(10.0, false, null, null, null, null),
        )
        fun sorted(mode: SortMode) = sortRows(rows, mode) { it.second }.map { it.first }
        assertEquals(listOf("b", "a", "c"), sorted(SortMode.BEST_OVERALL))
        assertEquals(listOf("b", "a", "c"), sorted(SortMode.LOWEST_TUNNEL_LATENCY))
        assertEquals(listOf("a", "b", "c"), sorted(SortMode.LOWEST_CONNECT_TIME))
        assertEquals(listOf("b", "a", "c"), sorted(SortMode.LOWEST_LOSS))
        assertEquals(listOf("b", "a", "c"), sorted(SortMode.HIGHEST_STABILITY))
        assertEquals(listOf("b", "a"), sorted(SortMode.SUCCESSFUL_ONLY))
    }
}

class ErrorClassifierTest {
    @Test fun classifiesVpnManagerEvents() {
        assertEquals(FailureCategory.AUTH, ErrorClassifier.classifyEvent(ErrorClassifier.CATEGORY_IKE_ERROR, 24))
        assertEquals(FailureCategory.IKE_NEGOTIATION, ErrorClassifier.classifyEvent(ErrorClassifier.CATEGORY_IKE_ERROR, 14))
        assertEquals(FailureCategory.DNS, ErrorClassifier.classifyEvent(ErrorClassifier.CATEGORY_NETWORK_ERROR, 0))
        assertEquals(FailureCategory.TIMEOUT, ErrorClassifier.classifyEvent(ErrorClassifier.CATEGORY_NETWORK_ERROR, 1))
        assertEquals(FailureCategory.USER_CANCELLED, ErrorClassifier.classifyEvent(ErrorClassifier.CATEGORY_DEACTIVATED, null))
        assertEquals(FailureCategory.UNKNOWN, ErrorClassifier.classifyEvent(null, null))
    }

    @Test fun onlyUnrecoverableErrorsAreTerminal() {
        assertTrue(ErrorClassifier.isTerminal(FailureCategory.AUTH))
        assertFalse(ErrorClassifier.isTerminal(FailureCategory.TIMEOUT))
        assertFalse(ErrorClassifier.isTerminal(null))
        assertNotNull(ErrorClassifier.explain(FailureCategory.AUTH))
    }
}
