package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Core session liveness only; no persisted consent clock or polling tick creates this origin. */
class AutomaticProtectionUptimeTest {
    private val identity = SelectedTargetIdentity("example.recipient", 10435, 100, listOf("a".repeat(64)))
    private val token = TargetConsent.Recipient(RecipientConsentToken(
        "12345678-1234-1234-1234-123456789abc", 1, 1_000, identity, VERIFIED_AUTOSTART_FINGERPRINT))
    private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 10, 1_000), emptyList(), null),
        targetConsents = listOf(token), schemaVersion = 3, masterEnabledAtMillis = 1_500)
    private fun grant(id: String = "uptime-session") = AutomaticProtectionGrant(id,
        listOf(TargetConsentLease(authority.generation, token)), ProtectionConsentStore(object : ProtectionAuthorityStorage {
            override fun <T> exclusive(action: () -> T): T = action()
            override fun read() = authority
            override fun write(value: ProtectionAuthoritySnapshot) = error("Uptime must not write authority")
        }))
    private fun state(start: Long? = null) = AutomaticProtectionState(masterEnabled = true, savedAuthorization = true,
        targets = listOf(AutomaticProtectionTargetState(identity, savedAuthorization = true)),
        coreSessionStartedAtElapsedMillis = start)

    /** Exercise real runtime transitions without Android service/storage calls or production hooks. */
    @Suppress("UNCHECKED_CAST")
    private fun withRuntime(action: (AutomaticProtectionRuntime, AutomaticProtectionGrant) -> Unit) {
        val runtime = AutomaticProtectionRuntime
        fun field(name: String) = AutomaticProtectionRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
        synchronized(runtime) {
            val grantField = field("grant")
            val flow = field("mutable").get(runtime) as MutableStateFlow<AutomaticProtectionState>
            val gate = field("gate").get(runtime) as AutomaticSessionGate
            val revision = field("revision")
            val oldGrant = grantField.get(runtime)
            val oldState = flow.value
            val oldRevision = revision.getLong(runtime)
            check(oldGrant == null) { "Test must not replace an existing owner" }
            val current = grant()
            try {
                check(gate.reserve(current.id))
                grantField.set(runtime, current)
                flow.value = state()
                action(runtime, current)
            } finally {
                current.writes.block()
                current.live.set(false)
                gate.revoke(current.id)
                grantField.set(runtime, oldGrant)
                flow.value = oldState
                revision.setLong(runtime, oldRevision)
            }
        }
    }

    @Test fun coreEntryRequiresCurrentLiveClaimedOwnerAndDoesNotUseMasterOnTime() {
        val current = grant()
        val previous = state()
        assertSame(previous, startedAutomaticProtectionSessionState(previous, current, current, true, 2_000))
        current.cancel = {}
        val entered = startedAutomaticProtectionSessionState(previous, current, current, true, 2_000)
        assertEquals(2_000L, entered.coreSessionStartedAtElapsedMillis)
        assertFalse(entered.enabled) // Readiness is a separate presentation gate.
        assertNotEquals(authority.masterEnabledAtMillis, entered.coreSessionStartedAtElapsedMillis)
        assertSame(entered, startedAutomaticProtectionSessionState(entered, current, current, true, 9_000))
        val replacement = grant(current.id).also { it.cancel = {} }
        assertSame(entered, startedAutomaticProtectionSessionState(entered, current, replacement, true, 9_000))
        assertSame(previous, startedAutomaticProtectionSessionState(previous, current, current, false, 9_000))
        current.live.set(false)
        assertSame(previous, startedAutomaticProtectionSessionState(previous, current, current, true, 9_000))
    }

    @Test fun invalidElapsedClockCannotCreateAnOriginAndBootZeroIsValid() {
        val current = grant().also { it.cancel = {} }
        assertNull(startedAutomaticProtectionSessionState(state(), current, current, true, -1).coreSessionStartedAtElapsedMillis)
        assertEquals(0L, startedAutomaticProtectionSessionState(state(), current, current, true, 0).coreSessionStartedAtElapsedMillis)
    }

    @Test fun updatesNeverMintAnOriginAndNormalDetectionRecoveryPollingPreserveIt() {
        val unstarted = updateAutomaticProtectionState(state(), "检测中")
        assertNull(unstarted.coreSessionStartedAtElapsedMillis)
        var running = state(2_000)
        for ((index, detail) in listOf("检测中", "正在恢复标准停止标记", "等待下次检测；检测可能受休眠影响").withIndex()) {
            running = updateAutomaticProtectionState(running, detail, busy = index == 1,
                checkedAtMillis = 3_000L + index, outcome = if (index == 2) "FLAG_CLEARED" else null, identity = identity)
            assertEquals(2_000L, running.coreSessionStartedAtElapsedMillis)
        }
    }

    @Test fun configuredProjectionPreservesTheSameLiveCoreButOffCannotRestoreOldOrigin() {
        val current = state(2_000)
        val projected = configuredAutomaticProtectionState(current, authority, "只读状态刷新")
        assertEquals(2_000L, projected.coreSessionStartedAtElapsedMillis)
        for (snapshot in listOf(null, authority.copy(mode = ProtectionConsentMode.USER_PAUSED),
                authority.copy(storageBlocked = true))) {
            assertNull(configuredAutomaticProtectionState(current, snapshot, "未运行").coreSessionStartedAtElapsedMillis)
        }
        val off = masterOffAutomaticProtectionState(current, "OFF")
        assertNull(off.coreSessionStartedAtElapsedMillis)
        assertNull(configuredAutomaticProtectionState(off, authority, "重新核对已保存选择").coreSessionStartedAtElapsedMillis)
        assertNull(configuredAutomaticProtectionReadState(current, Result.failure(IllegalStateException()), "失败")
            .coreSessionStartedAtElapsedMillis)
        assertNull(AutomaticProtectionState(masterEnabled = true, savedAuthorization = true).coreSessionStartedAtElapsedMillis)
    }

    @Test fun realClaimAndAllowWritesDoNotStartClockUntilServiceCoreEnters() = withRuntime { runtime, current ->
        runtime.started(current, 1_000) // Reserved but unclaimed is not a running core.
        assertNull(runtime.state.value.coreSessionStartedAtElapsedMillis)
        val retirement: () -> Unit = {}
        assertSame(current, runtime.claim(current.id, retirement) {})
        try {
            assertNull(runtime.state.value.coreSessionStartedAtElapsedMillis)
            runtime.allowWrites(current)
            runtime.update(current.id, "检测中")
            assertNull(runtime.state.value.coreSessionStartedAtElapsedMillis)
            runtime.started(current, 5_000)
            assertEquals(5_000L, runtime.state.value.coreSessionStartedAtElapsedMillis)
            runtime.started(current, 10_000)
            assertEquals(5_000L, runtime.state.value.coreSessionStartedAtElapsedMillis)
        } finally { runtime.detachServiceRetirement(retirement) }
    }

    @Test fun realTemporaryWaitingReadonlyAndBlockedWritesKeepTheSameCoreOrigin() = withRuntime { runtime, current ->
        val retirement: () -> Unit = {}
        assertNotNull(runtime.claim(current.id, retirement) {})
        try {
            runtime.started(current, 5_000)
            runtime.allowWrites(current)
            runtime.update(current.id, "检测中")
            val ticket = current.writes.ticket()
            runtime.waiting(current)
            assertFalse(current.writes.permits(ticket))
            assertFalse(runtime.state.value.enabled)
            assertEquals(5_000L, runtime.state.value.coreSessionStartedAtElapsedMillis)
            runtime.readOnly(current, "只读复检", busy = true)
            assertFalse(runtime.state.value.enabled)
            assertEquals(5_000L, runtime.state.value.coreSessionStartedAtElapsedMillis)
            runtime.blockWrites(current)
            runtime.waitForNextCheck(current, "等待下一轮完整核对")
            assertFalse(runtime.state.value.enabled)
            assertEquals(5_000L, runtime.state.value.coreSessionStartedAtElapsedMillis)
            runtime.allowWrites(current)
            runtime.update(current.id, "等待下次检测")
            assertTrue(runtime.state.value.enabled)
            assertEquals(5_000L, runtime.state.value.coreSessionStartedAtElapsedMillis)
        } finally { runtime.detachServiceRetirement(retirement) }
    }

    @Test fun realOldGrantCallbacksCannotAlterCurrentSessionOriginOrReadiness() = withRuntime { runtime, current ->
        val retirement: () -> Unit = {}
        assertNotNull(runtime.claim(current.id, retirement) {})
        try {
            runtime.started(current, 5_000)
            runtime.allowWrites(current)
            runtime.update(current.id, "检测中")
            val before = runtime.state.value
            val ticket = current.writes.ticket()
            val old = grant("old-session").also { it.cancel = {} }
            runtime.started(old, 99_000)
            runtime.waiting(old)
            runtime.readOnly(old, "旧回调")
            runtime.blockWrites(old)
            runtime.waitForNextCheck(old, "旧回调")
            runtime.allowWrites(old)
            runtime.update(old.id, "旧回调")
            runtime.detach(old.id)
            assertEquals(before, runtime.state.value)
            assertTrue(current.writes.permits(ticket))
        } finally { runtime.detachServiceRetirement(retirement) }
    }

    @Test fun realDetachClearsOriginAndLateCoreEntryCannotResurrectIt() = withRuntime { runtime, current ->
        val retirement: () -> Unit = {}
        assertNotNull(runtime.claim(current.id, retirement) {})
        try {
            runtime.started(current, 5_000)
            runtime.detach(current.id)
            assertNull(runtime.state.value.coreSessionStartedAtElapsedMillis)
            assertFalse(current.live.get())
            runtime.started(current, 99_000)
            runtime.update(current.id, "旧进程结束后的回调")
            assertNull(runtime.state.value.coreSessionStartedAtElapsedMillis)
            assertFalse(runtime.state.value.enabled)
        } finally { runtime.detachServiceRetirement(retirement) }
    }

    @Test fun interruptionRetiresCapabilitiesAndReplacementCoreStartsAtItsOwnEntry() {
        val current = grant().also { it.cancel = {}; it.writes.open() }
        val gate = AutomaticSessionGate().apply { check(reserve(current.id)); check(claim(current.id)) }
        val interrupted = interruptAutomaticProtectionGrant(current, gate, state(5_000), "core ended")
        assertNull(interrupted.coreSessionStartedAtElapsedMillis)
        assertFalse(current.live.get())
        assertNull(current.writes.ticket())
        val replacement = grant("replacement").also { it.cancel = {} }
        val entered = startedAutomaticProtectionSessionState(interrupted, replacement, replacement, true, 20_000)
        assertEquals(20_000L, entered.coreSessionStartedAtElapsedMillis)
    }
}
