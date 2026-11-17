package app.ikev2manager.export

import app.ikev2manager.data.ServerRow
import app.ikev2manager.data.reachable
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Builds report text. By construction nothing here can contain credentials: ServerRow has none. */
object Exporter {
    fun csvEscape(v: String): String {
        // Neutralise spreadsheet formula injection from imported names/labels.
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun n(v: Double?, d: Int = 1) = v?.let { String.format(Locale.ROOT, "%.${d}f", it) } ?: ""

    fun csv(rows: List<ServerRow>): String {
        val sb = StringBuilder()
        sb.append("name,hostname,label,tags,dns_ok,dns_ms,ipv4,ipv6,icmp_avg_ms,icmp_loss_pct,udp500,udp4500,vpn_status,failure,connect_ms,tunnel_avg_ms,tunnel_loss_pct,stability,prelim_score,vpn_score,overall_score\n")
        for (r in rows) {
            val s = r.stage1
            val v = r.vpn
            val cells = listOf(
                r.server.name, r.server.hostname, r.server.label.orEmpty(), r.server.tags,
                s?.dnsOk?.toString().orEmpty(), s?.dnsMs?.toString().orEmpty(), s?.ipv4.orEmpty(), s?.ipv6.orEmpty(),
                n(s?.pingAvg), n(s?.lossPct), s?.udp500.orEmpty(), s?.udp4500.orEmpty(),
                when { v == null -> "not_tested"; v.success -> "success"; else -> "failed" },
                v?.failure.orEmpty(), v?.connectMs?.toString().orEmpty(), n(v?.tunnelAvg), n(v?.lossPct), n(v?.stability),
                n(r.scores.prelim), n(r.scores.vpn), n(r.scores.overall),
            )
            sb.append(cells.joinToString(",") { csvEscape(it) }).append('\n')
        }
        return sb.toString()
    }

    fun summary(rows: List<ServerRow>, now: Long = System.currentTimeMillis()): String {
        val tested = rows.count { it.vpn != null }
        val ok = rows.count { it.vpn?.success == true }
        val reach = rows.count { it.stage1?.reachable() == true }
        return buildString {
            appendLine("IKEv2 Manager scan summary")
            appendLine("Generated: " + DateFormat.getDateTimeInstance().format(Date(now)))
            appendLine("Servers: ${rows.size}")
            appendLine("Preliminary-reachable (DNS + any probe answered): $reach")
            appendLine("Real VPN tests run: $tested, successful: $ok, failed: ${tested - ok}")
            appendLine("Note: preliminary probes (ICMP/UDP/TCP) do not prove IKEv2 works; only real VPN tests do.")
        }
    }

    fun best(rows: List<ServerRow>, limit: Int = 10): String {
        val top = rows.filter { it.vpn?.success == true }.sortedByDescending { it.scores.overall }.take(limit)
        return buildString {
            appendLine("Best servers (real VPN tests passed)")
            if (top.isEmpty()) appendLine("None yet. Run a stage 2 VPN test first.")
            top.forEachIndexed { i, r ->
                appendLine("${i + 1}. ${r.server.hostname}  score=${n(r.scores.overall, 0)}  tunnel=${n(r.vpn?.tunnelAvg, 0)}ms  connect=${r.vpn?.connectMs}ms  loss=${n(r.vpn?.lossPct, 0)}%")
            }
        }
    }

    fun failed(rows: List<ServerRow>): String = buildString {
        appendLine("Failed servers")
        val bad = rows.filter { it.vpn?.success == false || it.stage1?.dnsOk == false }
        if (bad.isEmpty()) appendLine("None.")
        for (r in bad) {
            val reason = when {
                r.vpn?.success == false -> "VPN: ${r.vpn?.failure ?: "unknown"} - ${r.vpn?.detail.orEmpty()}"
                else -> "DNS: ${r.stage1?.error ?: "failed"}"
            }
            appendLine("${r.server.hostname}  $reason")
        }
    }
}
