package io.github.originosfcmguardian.gmsconfig

import io.github.originosfcmguardian.privileged.GmsObservationRpc
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.GMS_PACKAGE_NAME
import io.github.originosfcmguardian.ui.HomeUiState
import io.github.originosfcmguardian.ui.gmsBackgroundSummary
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GmsConfigurationLoaderTest {
    private val context = GmsConfigContext("test/device/build", 0, 14,
        GmsConfigIdentity(SelectedTargetIdentity(GMS_PACKAGE_NAME, 10208, 100, listOf("a".repeat(64))), 1, 200, true))
    private fun snapshot() = GmsConfigSnapshot(context, 100_000, 10_000, EvidenceStatus.CONFORMING,
        EvidenceStatus.CONFORMING, EvidenceStatus.CONFORMING, EvidenceStatus.CONFORMING,
        EvidenceStatus.CONFORMING, GmsConfigFailure.NONE)
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }

    /** Reproduces the original init/resume replacement against the actual production RPC. */
    @Test fun cancelledMetadataReplacementIsBusyAndCombinedLoadDropsValidHistory() = runBlocking {
        val rpc = GmsObservationRpc()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val initial = launch(Dispatchers.Default) {
            rpc.bounded { entered.countDown(); awaitUninterruptibly(release) }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            initial.cancelAndJoin()
            val history = snapshot()
            val combined = runCatching { history to rpc.bounded { history } }
            assertTrue(combined.exceptionOrNull() is RejectedExecutionException)
            assertNull(combined.getOrNull())
        } finally { release.countDown(); rpc.close() }
    }

    @Test fun initialResumeAndRepeatedSignalsCoalesceWithoutCancellingMetadata() = runBlocking {
        val rpc = GmsObservationRpc()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val publications = mutableListOf<GmsConfigurationLoad>()
        val published = CompletableDeferred<Unit>()
        val changed = context.copy(bootCount = 15)
        val loader = GmsConfigurationLoader(this, {}, read = {
            readGmsConfiguration(::snapshot) { rpc.bounded {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown(); awaitUninterruptibly(release); context
                } else changed
            } }
        }, publish = { publications.add(it); published.complete(Unit) })
        try {
            loader.refresh(); yield()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) { loader.refresh() }
            assertEquals(1, calls.get())
            release.countDown()
            withTimeout(5_000) { published.await() }
            yield()
            assertEquals(2, calls.get())
            assertEquals(changed, publications.single().context)
            assertFalse(publications.single().storeFailed)
            assertFalse(publications.single().contextFailed)
            assertFalse(publications.single().snapshot!!.isFresh(changed, 100_001, 10_001))
        } finally { loader.close(); release.countDown(); rpc.close() }
    }

    @Test fun contextBusyOrTimeoutPreservesHistoryAndCannotReportCorruptOrHealthy() = runBlocking {
        for (timeout in listOf(false, true)) {
            val result = readGmsConfiguration(::snapshot) {
                if (timeout) withTimeout(20) { awaitCancellation() }
                else throw RejectedExecutionException()
            }
            assertEquals(snapshot(), result.snapshot)
            assertNull(result.context)
            assertFalse(result.storeFailed)
            assertTrue(result.contextFailed)
            val summary = gmsBackgroundSummary(HomeUiState(gmsConfigSnapshot = result.snapshot,
                gmsConfigContext = result.context, gmsConfigLoadFailed = result.storeFailed,
                gmsConfigContextFailed = result.contextFailed), 100_001, 10_001)
            assertTrue(summary.contains("当前配置未知"))
            assertFalse(summary.contains("记录读取失败"))
            assertFalse(summary.contains("最近检查未见异常"))
        }
    }

    @Test fun damagedRecordFailsWithoutReadingContextAndCheckFailureIsNotCorruption() = runBlocking {
        val result = readGmsConfiguration({
            GmsConfigurationCodec.decode("not-base64") ?: error("record damaged")
        }, { fail("Do not infer freshness for damaged history"); context })
        assertNull(result.snapshot)
        assertTrue(result.storeFailed)
        assertFalse(result.contextFailed)
        assertTrue(gmsBackgroundSummary(HomeUiState(gmsConfigLoadFailed = true)).contains("记录读取失败"))
        assertFalse(gmsBackgroundSummary(HomeUiState(gmsConfigSnapshot = snapshot(), gmsConfigCheckFailed = true))
            .contains("记录读取失败"))
    }

    @Test fun exitCancellationDiscardsLateMetadataAndPendingRefresh() = runBlocking {
        val rpc = GmsObservationRpc()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var reads = 0
        val publications = mutableListOf<GmsConfigurationLoad>()
        val loader = GmsConfigurationLoader(this, {}, read = {
            reads++
            readGmsConfiguration(::snapshot) { rpc.bounded {
                entered.countDown(); awaitUninterruptibly(release); finished.countDown(); context
            } }
        }, publish = publications::add)
        try {
            loader.refresh(); yield()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) { loader.refresh() }
            loader.close(); yield()
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            loader.refresh(); yield()
            assertEquals(1, reads)
            assertTrue(publications.isEmpty())
        } finally { loader.close(); release.countDown(); rpc.close() }
    }

    @Test fun sameCheckerMetadataAndCheckReadsSerializeAndPublicationRunsAfterGateRelease() = runBlocking {
        val gate = GmsConfigurationReadGate()
        val rpc = GmsObservationRpc()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val publications = mutableListOf<GmsConfigurationLoad>()
        val published = CompletableDeferred<Unit>()
        val loader = GmsConfigurationLoader(this, {}, read = {
            readGmsConfiguration(::snapshot) { gate.metadata { rpc.bounded { calls.incrementAndGet(); context } } }
        }, publish = { publications.add(it); published.complete(Unit) })
        val checkJob = launch {
            GmsConfigurationCheckCoordinator.check(read = {
                gate.checkRead {
                    rpc.bounded { entered.countDown(); awaitUninterruptibly(release) }
                    rpc.bounded { calls.incrementAndGet() }
                    snapshot()
                }
            }, timeoutSnapshot = { unknownGmsConfig(context, 100_000, 10_000, GmsConfigFailure.TIMED_OUT) },
                failureSnapshot = { unknownGmsConfig(context, 100_000, 10_000, GmsConfigFailure.READ_FAILED) },
                eligible = { true }, publish = { loader.refresh() })
        }
        try {
            yield(); assertTrue(entered.await(1, TimeUnit.SECONDS))
            loader.refresh(); yield()
            assertEquals(0, calls.get())
            release.countDown()
            checkJob.join()
            withTimeout(5_000) { published.await() }
            assertFalse(publications.single().contextFailed)
            assertEquals(context, publications.single().context)
            assertTrue(calls.get() in 2..3)
        } finally { loader.close(); release.countDown(); checkJob.cancelAndJoin(); rpc.close() }
    }

    @Test fun metadataAdmissionTimeoutIsWithinOriginalBudgetAndDoesNotEnterRpc() = runBlocking {
        val gate = GmsConfigurationReadGate(20)
        val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { gate.checkRead { release.await() } }
        var calls = 0
        try {
            val result = readGmsConfiguration(::snapshot) { gate.metadata { calls++; context } }
            assertTrue(result.contextFailed)
            assertEquals(snapshot(), result.snapshot)
            assertEquals(0, calls)
        } finally { release.complete(Unit); holder.join() }
    }

    @Test fun stuckMetadataTimeoutKeepsRpcBusyAndDiscardsLateValue() = runBlocking {
        val rpc = GmsObservationRpc(30)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val publications = mutableListOf<GmsConfigurationLoad>()
        val published = CompletableDeferred<Unit>()
        val loader = GmsConfigurationLoader(this, {}, read = {
            readGmsConfiguration(::snapshot) { rpc.bounded {
                awaitUninterruptibly(release); completed.countDown(); context
            } }
        }, publish = { publications.add(it); published.complete(Unit) })
        try {
            loader.refresh()
            withTimeout(5_000) { published.await() }
            assertTrue(publications.single().contextFailed)
            try { rpc.bounded { context }; fail("Stuck worker must remain BUSY") }
            catch (_: RejectedExecutionException) { }
            release.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            yield()
            assertNull(publications.single().context)
        } finally { loader.close(); release.countDown(); rpc.close() }
    }
}
