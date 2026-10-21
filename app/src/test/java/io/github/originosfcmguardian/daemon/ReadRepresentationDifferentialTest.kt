package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.autostart.lowerHexDigest
import io.github.originosfcmguardian.core.gms.GmsSocketParser
import io.github.originosfcmguardian.core.gms.SocketSource
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import io.github.originosfcmguardian.core.isSafeAndroidPackageName
import io.github.originosfcmguardian.privileged.*
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec
import io.github.originosfcmguardian.recovery.RecoveryJournalState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Frozen pre-optimization algorithms remain test-only differential references. */
class ReadRepresentationDifferentialTest {
    private val uid = 10208
    private val decodeMethod = DaemonShellStorage.Companion.javaClass.getDeclaredMethod(
        "decode", DaemonRecordRole::class.java, ByteArray::class.java).apply { isAccessible = true }

    private fun currentEnvelope(role: DaemonRecordRole, bytes: ByteArray): ByteArray = try {
        decodeMethod.invoke(DaemonShellStorage.Companion, role, bytes) as ByteArray
    } catch (failure: InvocationTargetException) { throw failure.targetException }

    @Test fun lowercaseHexMatchesLegacyForEveryByteAndCertificateIdentities() {
        for (byte in 0..255) assertEquals(legacyHex(byteArrayOf(byte.toByte())), lowerHexDigest(byteArrayOf(byte.toByte())))
        val certificateSets = listOf(
            listOf(byteArrayOf(0)), listOf(certificate(1)), listOf(certificate(2), certificate(1)),
            (1..MAX_CURRENT_SIGNERS).map(::certificate), listOf(ByteArray(65536) { it.toByte() }))
        for (certificates in certificateSets) {
            val fields = fields(1).copy(currentSignerCertificates = certificates)
            assertEquals(legacyValidated(fields), validatedStoppedPackage(fields, fields.packageName!!))
            assertEquals(legacyGmsSigner(certificates), gmsSignerDigest(certificates))
            assertEquals(legacyGmsSigner(certificates.reversed()), gmsSignerDigest(certificates.reversed()))
        }
        val valid = fields(1)
        for (invalid in listOf(null, valid.copy(packageName = "other.package"), valid.copy(uid = 2000),
                valid.copy(firstInstallTime = 0), valid.copy(stopped = null),
                valid.copy(currentSignerCertificates = null), valid.copy(currentSignerCertificates = emptyList()),
                valid.copy(currentSignerCertificates = listOf(byteArrayOf())),
                valid.copy(currentSignerCertificates = listOf(ByteArray(65537))),
                valid.copy(currentSignerCertificates = List(17) { certificate(it) }),
                valid.copy(currentSignerCertificates = listOf(certificate(1), certificate(1))))) {
            assertEquals(legacyValidated(invalid, valid.packageName!!), validatedStoppedPackage(invalid, valid.packageName!!))
        }
    }

    @Test fun socketParserMatchesLegacyAcrossCompleteMalformedAndBudgetedTables() {
        for (ipv6 in listOf(false, true)) {
            val head = header(ipv6)
            val matched = row(ipv6, owner = uid)
            val cases = listOf("", head, head + matched, head + row(ipv6, owner = uid + 1),
                head + matched.replace("146C", "146d"), head + matched.replace("146C", "146E"),
                head + matched.replace("146C", "01BB"), head + matched.replace(" 01 ", " 02 "),
                head + matched.replace(" 10208 ", " -1 "), head + matched.replace(" 0 123 ", " 0 overflow "),
                head + matched.replace("00000000:00000000", "not-a-queue"),
                head + matched.replace("00:00000000", "invalid"), head + matched.replace(" 00000000 ", " invalid "),
                head + matched.replace("0:", "bad:"), head + matched.replace(":1234", ":ZZZZ"),
                head + matched.trimEnd(), head + matched + "torn", head + matched + "malformed\n",
                head + "malformed\n" + matched, "unknown\n" + head + matched,
                head + matched.replace(" ", "\t"), (head + matched).replace("\n", "\r\n"),
                head + matched + "\u0000\n", head + "x".repeat(GmsSocketParser.MAX_LINE_BYTES + 1),
                head + row(ipv6, owner = uid + 1).repeat(GmsSocketParser.MAX_LINES),
                head + (row(ipv6, owner = uid + 1).trimEnd() + " ".repeat(700) + "\n").repeat(400),
                table(ipv6, 512))
            for ((index, text) in cases.withIndex()) compareSockets(text.toByteArray(), ipv6, "ipv6=$ipv6 case=$index")
        }
    }

    @Test fun socketParserPreservesExactReadAndCancellationBoundariesAfterMatches() {
        for (ipv6 in listOf(false, true)) {
            val bytes = (header(ipv6) + row(ipv6, owner = uid) + row(ipv6, owner = uid + 1)).toByteArray()
            for (at in listOf(0, 1, header(ipv6).length, bytes.size - 1, bytes.size, bytes.size + 1)) {
                compareSockets(bytes, ipv6, "cancel=$at", cancelAt = at)
                compareSockets(bytes, ipv6, "io=$at", failAt = at)
                compareSockets(bytes, ipv6, "denied=$at", failAt = at, denied = true)
            }
        }
    }

    @Test fun envelopeMatchesLegacyForEveryRoleAndRejectsChecksummedStructuralChanges() {
        for (role in DaemonRecordRole.entries) {
            for (size in listOf(0, 1, 8193, role.maximumBytes)) {
                val payload = ByteArray(size) { (it % 251).toByte() }
                val encoded = envelope(role, payload)
                assertArrayEquals(payload, currentEnvelope(role, encoded))
                assertArrayEquals(legacyEnvelope(role, encoded), currentEnvelope(role, encoded))
            }
        }
        val role = DaemonRecordRole.CONTROL
        val valid = envelope(role, rootPayload(24))
        val variants = listOf(byteArrayOf(), valid.copyOf(43), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            valid.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() },
            valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() },
            envelope(role, byteArrayOf(1), magic = 0), envelope(role, byteArrayOf(1), ordinal = -1),
            envelope(role, byteArrayOf(1), ordinal = DaemonRecordRole.AUTHORITY.ordinal),
            envelope(role, byteArrayOf(1), declaredSize = -1), envelope(role, byteArrayOf(1), declaredSize = 0),
            envelope(role, byteArrayOf(1), declaredSize = role.maximumBytes + 1),
            envelope(role, ByteArray(role.maximumBytes + 1)))
        for ((index, bytes) in variants.withIndex()) {
            val before = bytes.copyOf()
            val legacy = runCatching { legacyEnvelope(role, bytes) }
            val current = runCatching { currentEnvelope(role, bytes) }
            assertTrue("legacy variant=$index", legacy.isFailure)
            assertEquals("variant=$index", legacy.exceptionOrNull()?.javaClass, current.exceptionOrNull()?.javaClass)
            assertArrayEquals(before, bytes)
        }
    }

    @Test fun decodedPayloadRemainsIndependentOfBothInputAndOtherReads() {
        val payload = rootPayload(24)
        val source = envelope(DaemonRecordRole.CONTROL, payload)
        val first = currentEnvelope(DaemonRecordRole.CONTROL, source)
        val second = currentEnvelope(DaemonRecordRole.CONTROL, source)
        first[0] = (first[0].toInt() xor 1).toByte()
        assertArrayEquals(payload, second)
        assertArrayEquals(payload, currentEnvelope(DaemonRecordRole.CONTROL, source))
        source.fill(0)
        assertArrayEquals(payload, second)
    }

    /** Opt in with GUARDIAN_RUN_REPRESENTATION_BENCHMARK=1; no timing/allocation thresholds. */
    @Test fun syntheticRepresentationWorkloads() {
        assumeTrue("Opt-in host benchmark", System.getenv("GUARDIAN_RUN_REPRESENTATION_BENCHMARK") == "1" ||
            System.getProperty("guardian.runRepresentationBenchmark") == "1")
        println("REPRESENTATION host=synthetic excludes=Binder,physical_disk,device,battery warmup=3 measured=5 envelopeCurrentIncludes=reflection")
        for (count in listOf(24, 207, 500)) {
            val targets = (1..count).map(::fields)
            val expected = targets.map { legacyValidated(it) }
            targets.forEachIndexed { index, value -> assertEquals(expected[index], validatedStoppedPackage(value, value.packageName!!)) }
            benchmark("identity targets=$count freshValidations=${count * 2}",
                legacy = { var sum = 0L; repeat(2) { targets.forEach { sum += legacyValidated(it)!!.hashCode() } }; sum },
                current = { var sum = 0L; repeat(2) { targets.forEach { sum += validatedStoppedPackage(it, it.packageName!!)!!.hashCode() } }; sum })
            val payload = rootPayload(count)
            val bytes = envelope(DaemonRecordRole.CONTROL, payload)
            assertArrayEquals(payload, currentEnvelope(DaemonRecordRole.CONTROL, bytes))
            benchmark("envelope targets=$count freshDecodes=${count * 3} rootBytes=${payload.size}",
                legacy = { var sum = 0L; repeat(count * 3) { sum += legacyEnvelope(DaemonRecordRole.CONTROL, bytes).contentHashCode() }; sum },
                current = { var sum = 0L; repeat(count * 3) { sum += currentEnvelope(DaemonRecordRole.CONTROL, bytes).contentHashCode() }; sum })
        }
        for (rows in listOf(32, 512, 1500)) {
            val tables = listOf(false, true).map { ipv6 -> ipv6 to table(ipv6, rows).toByteArray() }
            tables.forEach { (ipv6, bytes) -> compareSockets(bytes, ipv6, "benchmark rows=$rows") }
            benchmark("socket rowsPerTable=$rows tables=2 bytes=${tables.sumOf { it.second.size }}",
                legacy = { tables.sumOf { (ipv6, bytes) -> legacySockets(bytes.inputStream(), ipv6, uid).hashCode().toLong() } },
                current = { tables.sumOf { (ipv6, bytes) -> GmsSocketParser.parse(bytes.inputStream(), ipv6, uid).hashCode().toLong() } })
        }
    }

    private fun compareSockets(bytes: ByteArray, ipv6: Boolean, label: String, cancelAt: Int? = null,
        failAt: Int? = null, denied: Boolean = false) {
        fun input() = object : ByteArrayInputStream(bytes) {
            var calls = 0
            override fun read(): Int {
                if (calls++ == failAt) { if (denied) throw SecurityException("synthetic") else throw IOException("synthetic") }
                return super.read()
            }
        }
        val oldInput = input(); val newInput = input()
        val old = legacySockets(oldInput, ipv6, uid) { cancelAt?.let { oldInput.calls >= it } == true }
        val new = GmsSocketParser.parse(newInput, ipv6, uid) { cancelAt?.let { newInput.calls >= it } == true }
        assertEquals(label, old, new)
        assertEquals("$label read calls", oldInput.calls, newInput.calls)
    }

    private fun fields(index: Int) = StoppedPackageFields("example.target$index", "example.target$index",
        10_000 + index, 1_000L + index, listOf(certificate(index)), index % 2 == 0)
    private fun certificate(index: Int) = ByteArray(1024) { ((it * 31 + index * 17 + (it ushr 3)) % 251).toByte() }
    private fun header(ipv6: Boolean) = "  sl local_address ${if (ipv6) "remote_address" else "rem_address"} st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
    private fun row(ipv6: Boolean, owner: Int, index: Int = 0) =
        "  $index: ${"0".repeat(if (ipv6) 32 else 8)}:1234 ${"A".repeat(if (ipv6) 32 else 8)}:146C 01 00000000:00000000 00:00000000 00000000 $owner 0 123 1 0000000000000000\n"
    private fun table(ipv6: Boolean, rows: Int) = header(ipv6) + (0 until rows).joinToString("") { index ->
        row(ipv6, if (index == rows / 2) uid else 10_000 + index % 200, index) }

    private fun rootPayload(count: Int): ByteArray {
        val consents = (1..count).map { index -> TargetConsent.Recipient(RecipientConsentToken(
            "12345678-1234-1234-1234-%012d".format(Locale.ROOT, index), 1, 1_000,
            SelectedTargetIdentity("example.target$index", 10_000 + index, 1_000L + index,
                listOf(legacyGmsSigner(listOf(certificate(index))))), VERIFIED_AUTOSTART_FINGERPRINT)) }
            .sortedBy { it.identity.packageName }
        val authority = ProtectionAuthorityCodec.encode(ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
            BudgetSnapshot(BudgetClock(1, 100, 1_000), emptyList(), null), targetConsents = consents,
            schemaVersion = 3, masterEnabledAtMillis = 1_000))
        val snapshot = RootFixtures.snapshot(authority, mapOf(
            // Matches v95's fixed opaque counter payload; no recovery-count codec is exercised.
            DaemonDomainRole.RECOVERY_COUNTS to ByteArray(32 * 1024) { (it % 251).toByte() },
            DaemonDomainRole.STOPPED_JOURNAL to RecoveryJournalCodec.encode(RecoveryJournalState())))
        return DaemonProtectionRootCodec.encode(DaemonProtectionRootSnapshot(
            snapshot.control.copy(mode = DaemonControlMode.ACTIVE), snapshot.domains()))
    }

    private fun envelope(role: DaemonRecordRole, payload: ByteArray, magic: Int = 0x47445331,
        ordinal: Int = role.ordinal, declaredSize: Int = payload.size): ByteArray {
        val body = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(magic); it.writeInt(ordinal); it.writeInt(declaredSize); it.write(payload)
        } }.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun legacyHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun legacyGmsSigner(certificates: List<ByteArray>): String {
        require(certificates.size in 1..16 && certificates.all { it.size in 1..65536 })
        val hashes = certificates.map { legacyHex(MessageDigest.getInstance("SHA-256").digest(it)) }.sorted()
        require(hashes.distinct().size == hashes.size)
        return if (hashes.size == 1) hashes.single() else legacyHex(MessageDigest.getInstance("SHA-256")
            .digest(hashes.joinToString(",").toByteArray(Charsets.US_ASCII)))
    }
    private fun legacyValidated(fields: StoppedPackageFields?, packageName: String = fields?.packageName ?: "example.target1"):
        Pair<SelectedTargetIdentity, Boolean>? = runCatching {
        requireNotNull(fields); require(isSafeAndroidPackageName(packageName))
        require(fields.packageName == packageName && fields.applicationPackageName == packageName)
        val certificates = requireNotNull(fields.currentSignerCertificates)
        require(certificates.size in 1..MAX_CURRENT_SIGNERS)
        require(certificates.all { it.isNotEmpty() && it.size <= 64 * 1024 })
        val signers = certificates.map { legacyHex(MessageDigest.getInstance("SHA-256").digest(it)) }.sorted()
        require(signers.distinct().size == signers.size)
        SelectedTargetIdentity(packageName, requireNotNull(fields.uid), requireNotNull(fields.firstInstallTime), signers) to requireNotNull(fields.stopped)
    }.getOrNull()
    private fun legacyEnvelope(role: DaemonRecordRole, bytes: ByteArray): ByteArray {
        check(bytes.size in 44..role.maximumBytes + 44)
        val body = bytes.copyOfRange(0, bytes.size - 32)
        check(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(body)).use {
            check(it.readInt() == 0x47445331 && it.readInt() == role.ordinal)
            val size = it.readInt(); check(size in 0..role.maximumBytes && size == body.size - 12)
            ByteArray(size).also { payload -> it.readFully(payload); check(it.read() == -1) }
        }
    }

    /** Exact old parser, including per-row regex construction and byte-wise consumption. */
    private fun legacySockets(input: InputStream, ipv6: Boolean, uid: Int, interrupted: () -> Boolean = { false }): SocketSource {
        require(uid in 10_000..99_999)
        var bytes = 0; var lines = 0; var header = false; var malformed = false; var matched = false
        val line = StringBuilder()
        fun result(completeness: SourceCompleteness) = SocketSource(
            if (matched && completeness != SourceCompleteness.COMPLETE) SourceCompleteness.PARTIAL else completeness, matched)
        fun consume(): Boolean {
            lines++
            if (lines > GmsSocketParser.MAX_LINES) return false
            val fields = line.toString().trim().split(Regex("\\s+")); line.setLength(0)
            if (!header) {
                header = fields.size == 12 && fields.take(2) == listOf("sl", "local_address") &&
                    fields[2] == (if (ipv6) "remote_address" else "rem_address") && fields[3] == "st" &&
                    fields.drop(4).take(8) == listOf("tx_queue", "rx_queue", "tr", "tm->when", "retrnsmt", "uid", "timeout", "inode")
                if (!header) malformed = true
                return true
            }
            val width = if (ipv6) 32 else 8
            val endpoint = Regex("[A-Fa-f0-9]{$width}:[A-Fa-f0-9]{4}")
            val valid = fields.size >= 10 && fields[0].matches(Regex("[0-9]+:")) && endpoint.matches(fields[1]) && endpoint.matches(fields[2]) &&
                fields[3].matches(Regex("[A-Fa-f0-9]{2}")) && fields[4].matches(Regex("[A-Fa-f0-9]{8}:[A-Fa-f0-9]{8}")) &&
                fields[5].matches(Regex("[A-Fa-f0-9]{2}:[A-Fa-f0-9]{8}")) && fields[6].matches(Regex("[A-Fa-f0-9]{8}")) &&
                fields[7].toIntOrNull()?.let { it >= 0 } == true && fields[8].toLongOrNull()?.let { it >= 0 } == true &&
                fields[9].toLongOrNull()?.let { it >= 0 } == true
            if (!valid) malformed = true
            else if (fields[7].toInt() == uid && fields[3].equals("01", true) && fields[2].substringAfter(':').toInt(16) in 5228..5230) matched = true
            return true
        }
        while (true) {
            if (interrupted()) return result(SourceCompleteness.PARTIAL)
            val byte = try { input.read() } catch (_: IOException) { return result(SourceCompleteness.UNAVAILABLE) }
                catch (_: SecurityException) { return result(SourceCompleteness.DENIED) }
            if (byte < 0) break
            if (++bytes > GmsSocketParser.MAX_BYTES) return result(SourceCompleteness.TRUNCATED)
            if (byte == 10) {
                if (!consume()) return result(SourceCompleteness.TRUNCATED)
                if (lines == 1 && !header) return result(SourceCompleteness.MALFORMED)
            } else {
                if (byte !in 32..126 && byte != 9 && byte != 13) return result(SourceCompleteness.MALFORMED)
                if (line.length >= GmsSocketParser.MAX_LINE_BYTES) return result(SourceCompleteness.TRUNCATED)
                if (byte != 13) line.append(byte.toChar())
            }
        }
        if (line.isNotEmpty()) return result(SourceCompleteness.TRUNCATED)
        return result(if (!header || malformed) SourceCompleteness.MALFORMED else SourceCompleteness.COMPLETE)
    }

    private data class Measurement(val wall: Long, val cpu: Long?, val allocated: Long?, val checksum: Long)
    private fun benchmark(label: String, legacy: () -> Long, current: () -> Long) {
        val thread = ManagementFactory.getThreadMXBean()
        val cpu = thread.isCurrentThreadCpuTimeSupported && runCatching {
            if (!thread.isThreadCpuTimeEnabled) thread.isThreadCpuTimeEnabled = true
            thread.isThreadCpuTimeEnabled
        }.getOrDefault(false)
        val allocated = (thread as? com.sun.management.ThreadMXBean)?.takeIf {
            it.isThreadAllocatedMemorySupported && runCatching {
                if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
                it.isThreadAllocatedMemoryEnabled
            }.getOrDefault(false)
        }
        val threadId = Thread.currentThread().id
        fun measure(work: () -> Long): Measurement {
            val beforeAllocation = allocated?.getThreadAllocatedBytes(threadId)
            val beforeCpu = if (cpu) thread.currentThreadCpuTime else null
            val beforeWall = System.nanoTime()
            val checksum = work()
            val wall = System.nanoTime() - beforeWall
            val afterCpu = if (cpu) thread.currentThreadCpuTime else null
            val afterAllocation = allocated?.getThreadAllocatedBytes(threadId)
            return Measurement(wall, beforeCpu?.let { afterCpu!! - it }, beforeAllocation?.let { afterAllocation!! - it }, checksum)
        }
        repeat(3) { assertEquals(legacy(), current()) }
        val old = mutableListOf<Measurement>(); val new = mutableListOf<Measurement>()
        repeat(5) { index ->
            // Alternate order to reduce systematic warmup/GC-order bias.
            if (index % 2 == 0) { old += measure(legacy); new += measure(current) }
            else { new += measure(current); old += measure(legacy) }
            assertEquals(old.last().checksum, new.last().checksum)
        }
        fun median(values: List<Long?>): Long? = values.filterNotNull().takeIf { it.size == values.size }?.sorted()?.get(values.size / 2)
        fun millis(value: Long?) = value?.let { "%.3f".format(Locale.ROOT, it / 1_000_000.0) } ?: "unavailable"
        for ((name, samples) in listOf("legacy" to old, "current" to new)) println("REPRESENTATION $label algorithm=$name " +
            "medianWallMs=${millis(median(samples.map { it.wall }))} medianCpuMs=${millis(median(samples.map { it.cpu }))} " +
            "medianAllocatedBytes=${median(samples.map { it.allocated }) ?: "unavailable"} checksum=${samples.first().checksum}")
    }
}
