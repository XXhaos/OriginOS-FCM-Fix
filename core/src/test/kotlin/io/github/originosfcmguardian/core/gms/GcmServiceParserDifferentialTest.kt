package io.github.originosfcmguardian.core.gms

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compare representation changes against the frozen pre-optimization parser.
 * Raw device input is optional, local-only and never included in assertion messages. */
class GcmServiceParserDifferentialTest {
    private val header = "SERVICE com.google.android.gms/.gcm.GcmService a12 pid=123 user=0\n"
    private val connected = header + "  Client:\n    Is client connected: true\n"

    private fun compare(bytes: ByteArray, label: String) = assertEquals(
        LegacyGcmServiceParser.parse(bytes), GcmServiceParser.parse(bytes), label,
    )

    @Test fun `service marker containing only trailing whitespace retains legacy behavior`() {
        val markers = listOf("  SERVICE ", "  SERVICE \t", "\tSERVICE \u00a0\u2003", "  SERVICE \t\u2028")
        for (delimiter in listOf("\n", "\r\n", "\r", "\r\r\n", "\n\r")) {
            for ((index, marker) in markers.withIndex()) {
                compare((marker + "\n" + connected).replace("\n", delimiter).toByteArray(),
                    "whitespace-only service marker before header $index")
                compare((connected + marker + "\n").replace("\n", delimiter).toByteArray(),
                    "whitespace-only service marker after header $index")
            }
        }
    }

    @Test fun `legacy parity across byte boundaries unicode whitespace errors and delimiters`() {
        val fixtures = mutableListOf("", header, connected,
            "No services match: ${GcmServiceParser.COMPONENT}\n",
            connected + "    connectTime=1:23:45/00:00\n",
            connected + "    Is client connected: false\n",
            connected + header, connected + "Permission Denial\n",
            connected + "*** DUMP TIMEOUT ***\n")
        for (symbol in listOf("x", "é", "中", "😀")) {
            val byteWidth = symbol.toByteArray().size
            val count = GcmServiceParser.MAX_LINE_BYTES / byteWidth
            for (delta in -1..1) {
                val line = symbol.repeat(count + delta) + "\n"
                fixtures += connected + line
                // Rejection priority must remain in input order.
                fixtures += connected + "Permission Denial\n" + line
                fixtures += connected + line + "Permission Denial\n"
            }
        }
        for (prefix in listOf("", "\t", "\u00a0", "\u2003", "\u2028")) {
            fixtures += header + prefix + "Is client connected: true\n"
            fixtures += prefix + header + "    Is client connected: true\n"
        }
        for (delimiter in listOf("\n", "\r\n", "\r", "\r\r\n", "\n\r")) {
            for ((index, text) in fixtures.toList().withIndex()) {
                compare(text.replace("\n", delimiter).toByteArray(), "delimiter fixture $index")
            }
        }
        for (count in listOf(GcmServiceParser.MAX_LINES - 4, GcmServiceParser.MAX_LINES - 3,
            GcmServiceParser.MAX_LINES, GcmServiceParser.MAX_BYTES)) {
            compare((connected + "\n".repeat(count)).toByteArray(), "line/total boundary $count")
        }
        for (suffix in listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte(), 10),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), 10),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte(), 10))) {
            compare(connected.toByteArray() + suffix, "invalid UTF-8 tail")
        }
    }

    @Test fun `legacy parity over deterministic mixed structural and private-shaped input`() {
        val random = Random(20261029)
        val lines = listOf("", "Client:", "   unrelated private diagnostics",
            "    Is client connected: true", "    Is client connected: false",
            "    Is client connected: TRUE", "Is client connected: true",
            header.trimEnd(), "SERVICE other/.Service abc pid=123 user=0",
            "    connectTime=54:47/00:00", "    connectTime=bad", "Permission Denial",
            "  SecurityException", "ERROR private", "*** SERVICE DUMP TIMEOUT ***",
            "    heartbeat timeout configuration: 300", "  é 中 😀")
        repeat(500) { index ->
            val text = buildString {
                if (random.nextBoolean()) append(connected)
                repeat(random.nextInt(0, 30)) {
                    append(lines[random.nextInt(lines.size)])
                    append(listOf("\n", "\r\n", "\r")[random.nextInt(3)])
                }
                if (random.nextBoolean()) append("\n")
            }
            compare(text.toByteArray(), "mixed fixture $index")
        }
    }

    @Test fun `optional private capture parity and allocation timing sample`() {
        val path = System.getenv("GUARDIAN_GCM_BENCHMARK_INPUT") ?: return
        val input = File(path).readBytes()
        compare(input, "private local capture")
        val inputs = listOf("private-capture" to input,
            "synthetic-bounded" to (connected + ("    diagnostic: " + "x".repeat(180) + "\n").repeat(280)).toByteArray())
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (bean?.isThreadAllocatedMemorySupported == true) bean.isThreadAllocatedMemoryEnabled = true
        val iterations = 2_000
        for ((label, bytes) in inputs) {
            repeat(1_000) { LegacyGcmServiceParser.parse(bytes); GcmServiceParser.parse(bytes) }
            for ((name, parser) in listOf("legacy" to LegacyGcmServiceParser::parse, "current" to GcmServiceParser::parse)) {
                val beforeAllocated = bean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1
                val beforeNanos = System.nanoTime()
                var complete = 0
                repeat(iterations) { if (parser(bytes).completeness == SourceCompleteness.COMPLETE) complete++ }
                val elapsed = System.nanoTime() - beforeNanos
                val allocated = bean?.getThreadAllocatedBytes(Thread.currentThread().id)?.minus(beforeAllocated) ?: -1
                // Counts and measurements only; never print raw text, paths or identities.
                println("gcm-benchmark input=$label parser=$name iterations=$iterations complete=$complete " +
                    "nanosPerParse=${elapsed / iterations} bytesPerParse=${if (allocated < 0) -1 else allocated / iterations}")
            }
        }
    }
}
