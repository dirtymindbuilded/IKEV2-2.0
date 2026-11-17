package app.ikev2manager.domain

data class Target(val id: Long, val hostname: String)

object HostnameValidator {
    private val labelRegex = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val ipv4Regex = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun isValid(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (isIpv4(host) || isIpv6(host)) return true
        val labels = host.removeSuffix(".").split('.')
        if (labels.any { !labelRegex.matches(it) }) return false
        return !labels.last().all { it.isDigit() }
    }

    fun isIpv4(h: String): Boolean {
        val m = ipv4Regex.matchEntire(h) ?: return false
        return m.groupValues.drop(1).all { it.toInt() in 0..255 }
    }

    fun isIpv6(h: String): Boolean =
        h.count { it == ':' } >= 2 &&
            h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
}

data class ParsedServer(val hostname: String, val name: String, val label: String?)

data class ImportResult(val valid: List<ParsedServer>, val invalid: List<String>, val duplicates: Int)

object ServerImportParser {
    private val headerWords = setOf("hostname", "host", "server", "address")

    /** Accepts TXT (one host per line) or CSV (host[,label]). Hostname is the dedupe key (case-insensitive). */
    fun parse(text: String, existing: Set<String> = emptySet()): ImportResult {
        val seen = HashSet<String>()
        existing.forEach { seen.add(it.lowercase()) }
        val valid = ArrayList<ParsedServer>()
        val invalid = ArrayList<String>()
        var duplicates = 0
        var first = true
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split(',', ';', '\t').map { it.trim().trim('"') }
            val host = fields[0]
            if (first) {
                first = false
                if (host.lowercase() in headerWords) continue
            }
            val label = fields.getOrNull(1)?.takeIf { it.isNotEmpty() }
            if (!HostnameValidator.isValid(host)) {
                invalid.add(line)
                continue
            }
            if (!seen.add(host.lowercase())) {
                duplicates++
                continue
            }
            valid.add(ParsedServer(host.lowercase(), host, label))
        }
        return ImportResult(valid, invalid, duplicates)
    }
}
