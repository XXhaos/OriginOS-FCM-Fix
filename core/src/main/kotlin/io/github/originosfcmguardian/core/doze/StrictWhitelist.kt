package io.github.originosfcmguardian.core.doze

internal enum class MembershipSource(val wire: String) {
    SYSTEM_EXCIDLE("system-excidle"), SYSTEM("system"), USER("user")
}

internal data class Membership(val source: MembershipSource, val packageName: String, val appId: Int) {
    fun wire() = "${source.wire},$packageName,$appId"
}

internal object StrictWhitelist {
    const val MAX_RAW_BYTES = 128 * 1024
    private val linePattern = Regex("^(system-excidle|system|user),([A-Za-z_][A-Za-z0-9_.]*),(0|[1-9][0-9]*)$")

    fun parse(raw: String): Set<Membership> {
        require(raw.isNotEmpty() && raw.toByteArray(Charsets.UTF_8).size <= MAX_RAW_BYTES) {
            "Whitelist output is empty or oversized"
        }
        require(!raw.contains('\u0000') && !raw.contains('\r')) { "Invalid whitelist control character" }
        val entries = linkedSetOf<Membership>()
        val packageIds = mutableMapOf<String, Int>()
        val lines = raw.split('\n').let { if (it.last() == "") it.dropLast(1) else it }
        require(lines.isNotEmpty() && lines.size <= 4096) { "Invalid whitelist line count" }
        for (line in lines) {
            val match = linePattern.matchEntire(line) ?: throw IllegalArgumentException("Malformed whitelist line")
            val source = MembershipSource.entries.single { it.wire == match.groupValues[1] }
            val appId = match.groupValues[3].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid whitelist appId")
            require(appId in 0..99999) { "Whitelist appId out of range" }
            val membership = Membership(source, match.groupValues[2], appId)
            require(entries.add(membership)) { "Duplicate whitelist membership" }
            val previous = packageIds.putIfAbsent(membership.packageName, appId)
            require(previous == null || previous == appId) {
                "Conflicting whitelist appIds"
            }
        }
        return entries
    }
}
