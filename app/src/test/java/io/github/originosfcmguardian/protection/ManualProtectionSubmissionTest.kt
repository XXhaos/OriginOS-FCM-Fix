package io.github.originosfcmguardian.protection

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualProtectionSubmissionTest {
    private class EnqueueFuture : ListenableFuture<Unit> {
        private val delegate = CompletableFuture<Unit>()
        override fun addListener(listener: Runnable, executor: Executor) {
            delegate.whenComplete { _, _ -> executor.execute(listener) }
        }
        fun succeed() { delegate.complete(Unit) }
        fun fail() { delegate.completeExceptionally(IllegalStateException("private failure detail")) }
        override fun cancel(mayInterruptIfRunning: Boolean) = delegate.cancel(mayInterruptIfRunning)
        override fun isCancelled() = delegate.isCancelled
        override fun isDone() = delegate.isDone
        override fun get(): Unit = delegate.get()
        override fun get(timeout: Long, unit: TimeUnit): Unit = delegate.get(timeout, unit)
    }

    @Test fun acknowledgedSubmissionIsNotReportedAsFinishedInspection() = runBlocking {
        val result = submitManualProtection { EnqueueFuture().apply { succeed() } }
        assertEquals(ManualProtectionSubmissionResult.ACCEPTED, result)
        assertTrue(result.message.contains("不等于巡检完成"))
    }

    @Test fun asynchronousFailureIsSanitizedAndRetryable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val future = EnqueueFuture()
        val result = async { submitManualProtection { entered.complete(Unit); future } }
        entered.await()
        future.fail()
        assertEquals(ManualProtectionSubmissionResult.FAILED, result.await())
        assertFalse(result.await().message.contains("private failure detail"))
    }

    @Test fun synchronousEnqueueExceptionIsHandled() = runBlocking {
        assertEquals(ManualProtectionSubmissionResult.FAILED, submitManualProtection { error("enqueue failed") })
    }

    @Test fun timeoutDoesNotCancelWorkOrRetryAndLateAcknowledgementIsSafe() = runBlocking {
        val future = EnqueueFuture()
        var calls = 0
        assertEquals(ManualProtectionSubmissionResult.UNCONFIRMED, submitManualProtection(30) { calls++; future })
        assertEquals(1, calls)
        assertFalse(future.isCancelled)
        future.succeed()
    }

    @Test fun callerCancellationPropagatesWithoutCancellingWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val future = EnqueueFuture()
        var completedNormally = false
        val job = launch {
            submitManualProtection { entered.complete(Unit); future }
            completedNormally = true
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(completedNormally)
        assertFalse(future.isCancelled)
        future.fail()
    }

    @Test fun cancelledEnqueueFutureIsAVisibleSubmissionFailure() = runBlocking {
        val future = EnqueueFuture().apply { cancel(false) }
        assertEquals(ManualProtectionSubmissionResult.FAILED, submitManualProtection { future })
    }
}
