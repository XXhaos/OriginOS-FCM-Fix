package io.github.originosfcmguardian.doze

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

internal enum class DozeOwnerSource { ACTIVE, HIDDEN_SYSTEM }
internal enum class DozeOwnerInventoryQualification { UNKNOWN }

internal data class DozeOwnerProfile(val userId: Int, val installed: Boolean, val hidden: Boolean, val enabled: Int)

/** A settings record, including retained pkg=null records; installation remains profile-specific. */
internal data class DozeOwnerCandidate(
    val source: DozeOwnerSource,
    val displayName: String,
    val compatName: String?,
    val appId: Int,
    val pccId: Int,
    val retained: Boolean,
    val sharedUser: String?,
    val profiles: List<DozeOwnerProfile>,
    val versionCode: Long? = null,
) {
    val packageName: String get() = compatName ?: displayName
}

internal data class DozeOwnerRename(val from: String, val to: String)

internal class DozeOwnerMatches internal constructor(
    val candidates: List<DozeOwnerCandidate>,
    val hasUnresolvedAppId: Boolean,
    val hasUnresolvedPcc: Boolean,
    val hasAliasOrSharedConflict: Boolean,
) {
    // Neither a singleton nor successful text parsing supplies exclusive-owner proof.
    val exclusiveOwner: Boolean get() = false
}

/**
 * Structured text prerequisites only. No API can upgrade this to inventory/writer authority.
 * The emitter has no trailer/count: EOF at a complete record boundary cannot reveal omitted records.
 * Even a successful trusted process and matching external roster require separately reviewed proof.
 */
internal class DozeOwnerCandidates internal constructor(
    val records: List<DozeOwnerCandidate>,
    val renames: List<DozeOwnerRename>,
    val expectedUserIds: Set<Int>,
) {
    val parsedComplete: Boolean get() = true
    val inventoryQualification: DozeOwnerInventoryQualification get() = DozeOwnerInventoryQualification.UNKNOWN
    val inventoryQualified: Boolean get() = false

    fun matchingRawAppId(appId: Int): DozeOwnerMatches {
        check(appId in 0..99_999) { "Invalid owner lookup appId" }
        // Nonnegative PCC is not proven to be an ordinary appId/fullUID mapping. Keep every such
        // record as ambiguous, even if its raw number differs; never silently filter it out.
        // The observed -1 sentinel has no proven UID meaning. Preserve its records for every
        // ordinary lookup, including a lookup with no known appId match; keep sources separate.
        val selected = records.filter { it.appId == appId || it.appId == -1 || it.pccId >= 0 }
        val aliases = selected.flatMap { listOfNotNull(it.displayName, it.compatName) }.toSet()
        val conflict = selected.any { it.sharedUser != null || it.compatName != null } ||
            selected.map { it.packageName }.distinct().size > 1 ||
            renames.any { it.from in aliases || it.to in aliases }
        return DozeOwnerMatches(immutableList(selected), selected.any { it.appId == -1 },
            selected.any { it.pccId >= 0 }, conflict)
    }
}

/**
 * Exact non-checkin Settings identity grammar; unrelated deeply indented metadata is drained.
 * Failure-looking metadata is conservatively rejected; firmware compatibility is not device verified.
 */
internal object DozeOwnerCandidateParser {
    const val RAW_BYTES = 16 * 1024 * 1024
    const val LINE_BYTES = 16 * 1024
    const val MAX_RECORDS = 2_048
    const val MAX_USERS = 64
    const val MAX_RENAMES = 2_048
    private const val MAX_STRUCTURED_CHARS = 512 * 1024
    private val name = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    private val header = Regex("  Package \\[([A-Za-z][A-Za-z0-9_.]*)\\] \\([0-9a-f]{1,8}\\):")
    private val profile = Regex("    User ([0-9]+): (.+)")
    private val signed = Regex("-?(?:0|[1-9][0-9]*)")
    private val version = Regex("    versionCode=(0|[1-9][0-9]*) minSdk=(0|[1-9][0-9]*) targetSdk=(0|[1-9][0-9]*)")
    private val profileKeys = setOf(
        "ceDataInode", "deDataInode", "pccCeDataInode", "pccDeDataInode", "installed", "hidden",
        "vHidden", "suspended", "appLockEnabled", "distractionFlags", "stopped", "vStopped",
        "notLaunched", "enabled", "instant", "virtual", "quarantined",
    )
    private val failure = Regex(
        "permission (?:denial|denied)|(?:^|\\s)(?:error|failure|exception)(?:[ :]|$)|" +
            "[A-Za-z.]+Exception\\b|unknown argument|unable to find package|timed out|(?:^|\\s)timeout(?=\\s|:|$)|dump aborted",
        RegexOption.IGNORE_CASE,
    )

    fun parse(input: InputStream, expectedUserIds: Set<Int>, mayContinue: () -> Boolean = { true }): DozeOwnerCandidates {
        check(expectedUserIds.isNotEmpty() && expectedUserIds.size <= MAX_USERS && 0 in expectedUserIds &&
            expectedUserIds.all { it in 0..21_474 }) { "Invalid independent user roster" }
        val expected = Collections.unmodifiableSet(LinkedHashSet(expectedUserIds))
        val records = ArrayList<DozeOwnerCandidate>()
        val renames = ArrayList<DozeOwnerRename>()
        val identities = HashSet<Pair<DozeOwnerSource, String>>()
        val renameSources = HashSet<String>()
        var section = 0
        var record: Record? = null
        var structuredChars = 0
        fun finishRecord() {
            val current = record ?: return
            check(current.appId != null && current.pccId != null && current.retained != null &&
                current.profiles.keys == expected) { "Missing owner identity or profile fields" }
            check(records.size < MAX_RECORDS) { "Owner record capacity exceeded" }
            val packageName = current.compatName ?: current.displayName
            check(identities.add(current.source to packageName)) { "Duplicate owner record in source" }
            structuredChars += current.displayName.length + (current.compatName?.length ?: 0) +
                (current.sharedUser?.length ?: 0) + current.profiles.size * 64 + (current.versionCode?.toString()?.length ?: 0)
            check(structuredChars <= MAX_STRUCTURED_CHARS) { "Owner structured capacity exceeded" }
            records += DozeOwnerCandidate(current.source, current.displayName, current.compatName,
                current.appId!!, current.pccId!!, current.retained!!, current.sharedUser,
                immutableList(current.profiles.values.toList()), current.versionCode)
            record = null
        }
        fun accept(value: String, oversized: Boolean) {
            check(mayContinue() && !Thread.currentThread().isInterrupted) { "Owner read cancelled" }
            check(!failure.containsMatchIn(value)) { "Package dump reported failure" }
            val selected = value.startsWith("    appId") || value.startsWith("    pccId") ||
                value.startsWith("    pkg=") || value.startsWith("    compat name") ||
                value.startsWith("    sharedUser") || value.startsWith("    User ") || value.startsWith("    versionCode")
            check(!oversized || (!selected && record != null && value.startsWith("    "))) {
                "Oversized owner grammar line"
            }
            if (oversized || value.isBlank()) return
            if (!value.startsWith(' ')) {
                finishRecord()
                val next = when (value) {
                    "Packages:" -> 1
                    "Renamed packages:" -> 2
                    "Hidden system packages:" -> 3
                    else -> error("Unknown package dump heading")
                }
                check(next > section && (section != 0 || next == 1)) { "Duplicate or unordered package section" }
                section = next
                return
            }
            if (value.startsWith("  Package ")) {
                check(section == 1 || section == 3) { "Package record outside owner section" }
                finishRecord()
                val match = requireNotNull(header.matchEntire(value)) { "Malformed owner header" }
                record = Record(if (section == 1) DozeOwnerSource.ACTIVE else DozeOwnerSource.HIDDEN_SYSTEM,
                    checkedName(match.groupValues[1]))
                return
            }
            if (section == 2) {
                check(value.startsWith("  ") && !value.startsWith("   ")) { "Malformed rename row" }
                val names = value.removePrefix("  ").split(',')
                check(names.size == 2) { "Malformed rename row" }
                val from = checkedName(names[0])
                val to = checkedName(names[1])
                check(from != to && renameSources.add(from) && renames.size < MAX_RENAMES) { "Conflicting rename row" }
                structuredChars += from.length + to.length
                check(structuredChars <= MAX_STRUCTURED_CHARS) { "Owner structured capacity exceeded" }
                renames += DozeOwnerRename(from, to)
                return
            }
            val current = requireNotNull(record) { "Owner row outside package record" }
            check(value.startsWith("    ")) { "Unknown package dump row" }
            when {
                value.startsWith("    versionCode") -> {
                    check(current.versionCode == null) { "Duplicate owner version" }
                    val match = requireNotNull(version.matchEntire(value)) { "Malformed owner version" }
                    current.versionCode = requireNotNull(match.groupValues[1].toLongOrNull()) { "Owner version overflow" }
                    check(match.groupValues[2].toIntOrNull() != null && match.groupValues[3].toIntOrNull() != null) {
                        "Owner SDK overflow"
                    }
                }
                value.startsWith("    compat name") -> {
                    check(value.startsWith("    compat name=") && current.compatName == null) { "Duplicate or malformed compat name" }
                    current.compatName = checkedName(value.removePrefix("    compat name="))
                    check(current.compatName != current.displayName) { "Conflicting compat name" }
                }
                value.startsWith("    appId") -> {
                    check(value.startsWith("    appId=") && current.appId == null) { "Duplicate or malformed appId" }
                    current.appId = checkedInt(value.removePrefix("    appId="), -1..99_999)
                }
                value.startsWith("    pccId") -> {
                    check(value.startsWith("    pccId=") && current.pccId == null) { "Duplicate or malformed pccId" }
                    current.pccId = checkedInt(value.removePrefix("    pccId="), -1..Int.MAX_VALUE)
                }
                value.startsWith("    pkg=") -> {
                    check(current.retained == null) { "Duplicate pkg state" }
                    val pkg = value.removePrefix("    pkg=")
                    check(pkg.isNotBlank()) { "Missing pkg state" }
                    current.retained = pkg == "null"
                }
                value.startsWith("    sharedUser") -> {
                    check(value.startsWith("    sharedUser=") && current.sharedUser == null) { "Duplicate or malformed shared owner" }
                    val shared = value.removePrefix("    sharedUser=")
                    check(shared.isNotBlank() && shared.length <= 512) { "Invalid shared owner" }
                    current.sharedUser = shared
                }
                value.startsWith("    User ") -> {
                    val match = requireNotNull(profile.matchEntire(value)) { "Malformed owner profile" }
                    val userId = checkedInt(match.groupValues[1], 0..21_474)
                    check(userId in expected && !current.profiles.containsKey(userId)) { "Unknown or duplicate owner user" }
                    val fields = LinkedHashMap<String, String>()
                    for (token in match.groupValues[2].split(' ')) {
                        val pair = token.split('=')
                        check(pair.size == 2 && pair[0] in profileKeys && fields.put(pair[0], pair[1]) == null) {
                            "Unknown or duplicate owner profile field"
                        }
                    }
                    check(fields.keys == profileKeys) { "Missing owner profile field" }
                    for (key in listOf("ceDataInode", "deDataInode", "pccCeDataInode", "pccDeDataInode")) {
                        check(signed.matches(fields.getValue(key)) && fields.getValue(key).toLongOrNull() != null) {
                            "Invalid owner profile inode"
                        }
                    }
                    checkedInt(fields.getValue("distractionFlags"))
                    // Actual firmware prints ps.getVHidden as an integer, not a boolean.
                    checkedInt(fields.getValue("vHidden"))
                    val enabled = checkedInt(fields.getValue("enabled"), 0..4)
                    for (key in profileKeys - setOf("ceDataInode", "deDataInode", "pccCeDataInode", "pccDeDataInode", "distractionFlags", "vHidden", "enabled")) {
                        check(fields.getValue(key) in setOf("true", "false")) { "Invalid owner profile flag" }
                    }
                    current.profiles[userId] = DozeOwnerProfile(userId, fields.getValue("installed") == "true",
                        fields.getValue("hidden") == "true", enabled)
                }
            }
        }
        val line = ByteArrayOutputStream()
        var oversized = false
        var rawBytes = 0
        fun flush() {
            val bytes = line.toByteArray()
            val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            // An oversized irrelevant prefix may end in a partial UTF-8 character; no text is retained.
            val value = if (oversized) String(bytes, StandardCharsets.UTF_8)
                else decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString()
            accept(value, oversized)
            line.reset()
            oversized = false
        }
        val buffer = ByteArray(8 * 1024)
        while (true) {
            check(mayContinue() && !Thread.currentThread().isInterrupted) { "Owner read cancelled" }
            val count = input.read(buffer)
            if (count < 0) break
            rawBytes += count
            check(rawBytes <= RAW_BYTES) { "Package dump exceeds raw byte bound" }
            for (index in 0 until count) {
                val byte = buffer[index]
                if (byte == '\n'.code.toByte()) flush()
                else if (line.size() < LINE_BYTES) line.write(byte.toInt())
                else oversized = true
            }
        }
        // PrintWriter.println terminates every emitter line; a partial final line is rejected.
        check(line.size() == 0 && !oversized) { "Unterminated package dump line" }
        finishRecord()
        check(section > 0 && records.any { it.source == DozeOwnerSource.ACTIVE } && mayContinue()) {
            "Missing active owner records or cancelled read"
        }
        return DozeOwnerCandidates(immutableList(records), immutableList(renames), expected)
    }

    private fun checkedName(value: String): String {
        check(value.length in 1..255 && name.matches(value)) { "Invalid owner package name" }
        return value
    }

    private fun checkedInt(value: String, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int {
        check(signed.matches(value)) { "Invalid owner integer" }
        val parsed = value.toIntOrNull()
        check(parsed != null && parsed in range) { "Owner integer overflow or out of range" }
        return parsed
    }

    private class Record(val source: DozeOwnerSource, val displayName: String) {
        var compatName: String? = null
        var appId: Int? = null
        var pccId: Int? = null
        var retained: Boolean? = null
        var sharedUser: String? = null
        var versionCode: Long? = null
        val profiles = LinkedHashMap<Int, DozeOwnerProfile>()
    }
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
