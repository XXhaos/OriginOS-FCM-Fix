package io.github.originosfcmguardian.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class FcmPackageStateReaderTest {
    private val main = "Packages:\n" +
        "  Package [com.google.android.gms] (abcd):\n" +
        "    User 0: installed=true hidden=false suspended=false stopped=false vStopped=false enabled=0\n"

    /** Synthetic discarded data only; no device dump, identities or tokens enter fixtures. */
    private fun padding(size: Int): String = buildString(size) {
        var remaining = size
        while (remaining > 0) {
            val length = minOf(remaining, 8000)
            if (length == 1) append('\n')
            else { append(' '); repeat(length - 2) { append('x') }; append('\n') }
            remaining -= length
        }
    }

    private class CompletedProcess(stdout: ByteArray, stderr: ByteArray = byteArrayOf(), private val exit: Int = 0) : Process() {
        private val out = ByteArrayInputStream(stdout)
        private val err = ByteArrayInputStream(stderr)
        override fun getInputStream() = out
        override fun getErrorStream() = err
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = exit
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = exit
        override fun isAlive() = false
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    @Test fun processEnvelopeRequiresCompleteOutputExitZeroAndNoStderr() {
        assertTrue(FcmPackageStateReader.readProcess(CompletedProcess(main.toByteArray())))
        assertFalse(FcmPackageStateReader.readProcess(CompletedProcess(main.toByteArray(), exit = 1)))
        assertFalse(FcmPackageStateReader.readProcess(CompletedProcess(main.toByteArray(), byteArrayOf(10))))
        assertFalse(FcmPackageStateReader.readProcess(CompletedProcess((main + "Tail:\nunfinished").toByteArray())))
    }

    @Test fun interruptedProcessReadFailsClosed() {
        try {
            Thread.currentThread().interrupt()
            assertFalse(FcmPackageStateReader.readProcess(CompletedProcess(main.toByteArray())))
        } finally { Thread.interrupted() }
    }

    @Test fun acceptsCompleteDumpAboveOldOneMiBCapWithoutRetainingDiscardedSections() {
        val dump = "Resolver:\n" + padding(2 * 1024 * 1024) + main + "Tail:\n  harmless\n"
        assertTrue(FcmPackageStateReader.parse(dump.toByteArray()))
    }

    @Test fun hiddenAndResolverCopiesAreNotTheMainPackagesSection() {
        val hidden = "Hidden system packages:\n  Packages:\n" + main.substringAfter('\n')
        val resolver = "Resolver:\n  Packages:\n  Package [com.google.android.gms] (abcd):\n"
        assertTrue(FcmPackageStateReader.parse((hidden + resolver + main).toByteArray()))
        assertFalse(FcmPackageStateReader.parse((hidden + resolver).toByteArray()))
    }

    @Test fun rejectsDuplicateMainSectionEvenWhenSecondIsEmpty() {
        assertFalse(FcmPackageStateReader.parse((main + "Tail:\nPackages:\n").toByteArray()))
        assertFalse(FcmPackageStateReader.parse((main + main).toByteArray()))
    }

    @Test fun discardedPrefixAndSuffixMustAlsoBeStrictUtf8() {
        val malformed = byteArrayOf(0xc3.toByte(), 0x28, 10)
        assertFalse(FcmPackageStateReader.parse("Resolver:\n".toByteArray() + malformed + main.toByteArray()))
        assertFalse(FcmPackageStateReader.parse(main.toByteArray() + "Tail:\n".toByteArray() + malformed))
        assertFalse(FcmPackageStateReader.parse(main.toByteArray() + byteArrayOf(0xe4.toByte())))
    }

    @Test fun validatesChineseAcrossArbitraryChunkBoundaries() {
        val bytes = ("Resolver:\n  合法中文\n" + main + "Tail:\n  完整\n").toByteArray()
        val collector = FcmPackageStateReader.Collector()
        for (index in bytes.indices) assertTrue(collector.accept(bytes, index, 1))
        assertTrue(collector.finish())
        assertFalse(collector.finish())
        assertFalse(collector.accept(byteArrayOf(10)))
    }

    @Test fun bulkSpansPreserveSplitHeadersCrLfAndFourByteUtf8() {
        val bytes = ("Resolver:\r\n  中文😀\r\n\n" + main.replace("\n", "\r\n") + "Tail:\r\n  尾部\n").toByteArray()
        for (chunk in listOf(1, 2, 3, 7, 4095, 4096, 4097)) {
            val collector = FcmPackageStateReader.Collector()
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(chunk, bytes.size - offset)
                assertTrue(collector.accept(bytes, offset, count))
                offset += count
            }
            assertTrue(collector.finish())
        }
    }

    @Test fun reusedBuffersHandleFullLineThenEmptyAndShortLinesWithoutStaleContent() {
        val bytes = ("Resolver:\n" + "x".repeat(FcmPackageStateReader.MAX_LINE_BYTES) +
            "\n\n  中\n" + main + "Tail:\n\n").toByteArray()
        for (chunk in listOf(1, 4095, 4096, 8192)) {
            val collector = FcmPackageStateReader.Collector()
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(chunk, bytes.size - offset)
                assertTrue(collector.accept(bytes, offset, count))
                offset += count
            }
            assertTrue(collector.finish())
        }
    }

    @Test fun decoderReuseStillRejectsIncompleteOverlongSurrogateAndOutOfRangeUtf8() {
        for (invalid in listOf(
            byteArrayOf(0xc3.toByte()),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
        )) {
            val bytes = ("Resolver:\n  合法中文😀\n" + main + "Tail:\n").toByteArray() + invalid + byteArrayOf(10)
            val collector = FcmPackageStateReader.Collector()
            var rejected = false
            for (index in bytes.indices) if (!collector.accept(bytes, index, 1)) rejected = true
            assertTrue(rejected)
            assertFalse(collector.finish())
        }
    }

    @Test fun lateDiscardedErrorIsCheckedAfterBulkBufferReuse() {
        val bytes = ("Resolver:\n" + padding(2 * 1024 * 1024) + main +
            "Tail:\n  中文\n  " + "x".repeat(8100) + " eXcEpTiOn\n").toByteArray()
        assertFalse(FcmPackageStateReader.parse(bytes))
    }

    @Test fun errorsAnywhereInDiscardedSectionsRejectOtherwiseQualifiedState() {
        for (error in listOf("Permission Denial", "Permission denied", "Error dumping", "Exception", "Dump timeout")) {
            assertFalse(FcmPackageStateReader.parse(("Resolver:\n  $error\n" + main).toByteArray()))
            assertFalse(FcmPackageStateReader.parse((main + "Tail:\n  $error\n").toByteArray()))
        }
        val bytes = (main + "Tail:\n  Permission Denial\n").toByteArray()
        val collector = FcmPackageStateReader.Collector()
        var accepted = true
        for (index in bytes.indices) if (!collector.accept(bytes, index, 1)) accepted = false
        assertFalse(accepted)
        assertFalse(collector.finish())
    }

    @Test fun requiresFinalNewlineAndDoesNotFinishEarlyAfterMainSection() {
        assertFalse(FcmPackageStateReader.parse(main.trimEnd().toByteArray()))
        assertFalse(FcmPackageStateReader.parse((main + "Tail:\n  unfinished").toByteArray()))
        assertFalse(FcmPackageStateReader.parse(byteArrayOf()))
        val collector = FcmPackageStateReader.Collector()
        assertTrue(collector.accept(main.toByteArray()))
        assertTrue(collector.accept("Tail:\n".toByteArray()))
        assertFalse(collector.accept(byteArrayOf(0xff.toByte(), 10)))
        assertFalse(collector.finish())
    }

    @Test fun rawCapacityAcceptsExactLimitAndRejectsOneExtraByte() {
        val prefix = "Resolver:\n"
        val dump = prefix + padding(FcmPackageStateReader.MAX_RAW_BYTES - prefix.length - main.length) + main
        assertTrue(FcmPackageStateReader.parse(dump.toByteArray()))
        assertFalse(FcmPackageStateReader.parse((dump + "\n").toByteArray()))
    }

    @Test fun retainedMainCapacityAcceptsExactLimitAndRejectsOneExtraByte() {
        val dump = main + padding(FcmPackageStateReader.MAX_PACKAGES_BYTES - main.length)
        assertTrue(FcmPackageStateReader.parse(dump.toByteArray()))
        assertFalse(FcmPackageStateReader.parse((dump + "\n").toByteArray()))
    }

    @Test fun discardedLinesAreBoundedToo() {
        val limit = FcmPackageStateReader.MAX_LINE_BYTES
        assertTrue(FcmPackageStateReader.parse(("Resolver:\n" + "x".repeat(limit) + "\n" + main).toByteArray()))
        assertFalse(FcmPackageStateReader.parse(("Resolver:\n" + "x".repeat(limit + 1) + "\n" + main).toByteArray()))
        assertFalse(FcmPackageStateReader.parse((main + "Tail:\n" + "x".repeat(limit + 1) + "\n").toByteArray()))
    }

    @Test fun retainsStrictIdentityUserAndFlagQualification() {
        assertTrue(FcmPackageStateReader.parse(main.replace("vStopped=false ", "").toByteArray()))
        assertTrue(FcmPackageStateReader.parse(main.replace("enabled=0", "enabled=1").toByteArray()))
        for (dump in listOf(
            main.replace("com.google.android.gms", "another.package"),
            main + "  Package [com.google.android.gms] (1234):\n",
            main + "    User 0: installed=true hidden=false suspended=false stopped=false enabled=0\n",
            main.replace("User 0:", "User 1:"),
            main.replace("stopped=false", "stopped=true"),
            main.replace("vStopped=false", "vStopped=true"),
            main.replace("vStopped=false", "vStopped=unknown"),
            main.replace("hidden=false", "hidden=true"),
            main.replace("suspended=false", "suspended=true"),
            main.replace("installed=true", "installed=false"),
            main.replace("enabled=0", "enabled=unknown"),
            main.replace("enabled=0", "enabled=0 enabled=1"),
            main.replace("stopped=false", "stopped=unknown"),
        )) assertFalse(FcmPackageStateReader.parse(dump.toByteArray()))
    }
}
