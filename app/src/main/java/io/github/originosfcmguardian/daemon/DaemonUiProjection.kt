package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZoneId

/** App-side value only: no Binder, Context, local store or execution capability. */
internal data class DaemonAppStatus(
    val record: DaemonControlRecord?, val status: DaemonOwnerStatus, val dataRevision: Long?,
    val closing: Boolean, val released: Boolean,
    val sessionStartedElapsed: Long? = null, val sessionStartedAtMillis: Long? = null,
    val lastCheckedAtMillis: Long? = null, val latestGmsObservation: GmsConnectionObservation? = null,
    val needsReconciliation: Boolean = false,
    val recoveryReadiness: DaemonRecoveryReadinessEvidence? = null,
)

internal data class DaemonUiSnapshot(
    val automatic: AutomaticProtectionState,
    val fcm: FcmProtectionUiState,
    val selectedIdentities: List<SelectedTargetIdentity>,
    val counts: RecoveryCountsUiState,
    val configurationAvailable: Boolean,
    val hostReady: Boolean,
    val heartbeatUi: GmsAutoHeartbeatUiState = GmsAutoHeartbeatUiState(),
    val recoveryUi: FcmRecoveryUiState = FcmRecoveryUiState(),
    val stoppedRecord: StoppedRecoveryRecord? = null,
    val stoppedRecordAvailable: Boolean = false,
)

/** A coherent remote aggregate is the sole source. Mapping can only mutate private RAM copies. */
internal object DaemonUiProjection {
    fun project(snapshot: DaemonProtectionRootSnapshot, status: DaemonAppStatus?, nowElapsed: Long,
        nowMillis: Long, zone: ZoneId = ZoneId.systemDefault(), statusFresh: Boolean = true): DaemonUiSnapshot {
        val interrupted = snapshot.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) != null
        val authorityRead = runCatching {
            check(snapshot.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) == true)
            ProtectionAuthorityCodec.decode(requireNotNull(snapshot.bytes(DaemonDomainRole.AUTHORITY)))
        }
        val authority = authorityRead.getOrNull()
        val selectedRead = runCatching {
            val text = snapshot.bytes(DaemonDomainRole.SELECTION)?.let {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(it)).toString()
            }.orEmpty()
            (TargetSelectionCodec.decode(text) as? TargetSelectionDecodeResult.Valid)?.values
                ?: error("Selection unavailable")
        }
        val configurationAvailable = !interrupted && authorityRead.isSuccess && selectedRead.isSuccess
        val stopPending = snapshot.halt != null || snapshot.bytes(DaemonDomainRole.AUTHORITY_STOP_PENDING) != null
        val master = authority?.mode == ProtectionConsentMode.ACTIVE
        val coherent = statusFresh && status?.record == snapshot.control && status.dataRevision == snapshot.dataRevision
        // Host emits the origin only when its environment lifecycle and dispatch gate are READY.
        val origin = status?.sessionStartedElapsed?.takeIf { it >= 0 && it <= nowElapsed }
        val ready = coherent && status != null && status.status == DaemonOwnerStatus.ACTIVE &&
            !status.closing && !status.released && !status.needsReconciliation && origin != null && snapshot.control.mode == DaemonControlMode.ACTIVE &&
            configurationAvailable && !stopPending && master && authority?.storageBlocked != true
        val selected = selectedRead.getOrNull().orEmpty()
        val runnable = if (configurationAvailable && !stopPending) authority?.runnableConsents().orEmpty() else emptyList()
        val journal = runCatching {
            check(!interrupted && snapshot.bytes(DaemonDomainRole.STOPPED_BLOCKED) == null)
            snapshot.bytes(DaemonDomainRole.STOPPED_JOURNAL)?.let(RecoveryJournalCodec::decode)
        }
        val latest = journal.getOrNull()?.latest
        val reconcile = stopPending || journal.isFailure || latest?.requiresReconcile == true ||
            coherent && status?.status == DaemonOwnerStatus.PENDING_RECOVERY
        val detail = when {
            !configurationAvailable -> "保护配置暂不可用"
            stopPending -> "关闭或恢复结果待确认"
            !master -> "自动保护未开启"
            reconcile -> "恢复结果待确认"
            !ready -> hostDetail(status, coherent)
            else -> "保护已开启"
        }
        val automatic = AutomaticProtectionState(
            enabled = ready && !reconcile, detail = detail,
            lastCheckedAtMillis = status?.lastCheckedAtMillis.takeIf { coherent },
            lastOutcome = latest?.outcome?.name,
            savedAuthorization = runnable.isNotEmpty(), revocationSaveFailed = stopPending,
            waitingForService = master && !ready && !reconcile && configurationAvailable,
            waitingForReconciliation = reconcile, masterEnabled = master,
            configurationAvailable = configurationAvailable,
            coreSessionStartedAtElapsedMillis = origin.takeIf { ready },
            targets = authority?.consents().orEmpty().map { consent ->
                val invalid = consent.id in authority!!.invalidatedConsentIds
                val authorized = consent in runnable
                val ownRecord = latest?.takeIf { it.identity == consent.identity }
                AutomaticProtectionTargetState(consent.identity, savedAuthorization = authorized,
                    enabled = ready && !reconcile && authorized, configuredSelection = true,
                    waitingForPrivilege = authorized && !ready && !reconcile,
                    needsReconfirmation = invalid,
                    detail = if (invalid) "应用已变化，请重新选择" else detail,
                    lastCheckedAtMillis = ownRecord?.baselineAtMillis,
                    lastOutcome = ownRecord?.outcome?.name)
            })
        val fcmState = fcm(snapshot, status.takeIf { coherent }, ready, interrupted, nowElapsed)
        return DaemonUiSnapshot(automatic, fcmState,
            selected, counts(snapshot, selected, configurationAvailable, interrupted, nowMillis, zone),
            configurationAvailable, ready, heartbeatUi(snapshot, fcmState, interrupted),
            recoveryUi(snapshot, fcmState, interrupted), latest, journal.isSuccess)
    }

    private fun heartbeatUi(root: DaemonProtectionRootSnapshot, fcm: FcmProtectionUiState,
        interrupted: Boolean): GmsAutoHeartbeatUiState {
        val result = runCatching {
            val value = root.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode)
            check(value == null && root.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED) == null ||
                value != null && root.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) == true)
            value
        }
        val saved = result.getOrNull()
        val blocked = interrupted || result.isFailure || fcm.storageBlocked ||
            saved?.resumeBlock != null || root.bytes(DaemonDomainRole.HEARTBEAT_HALT) != null
        val pending = fcm.maintenance == FcmMaintenanceState.STOP_PENDING
        val configured = saved?.configured == true
        val active = !blocked && !pending && fcm.maintenance == FcmMaintenanceState.ACTIVE
        return GmsAutoHeartbeatUiState(configured = configured, active = active,
            paused = configured && !active, detail = fcm.detail,
            requiresConfirmation = configured && (blocked || fcm.requiresConfirmation), stopPending = pending,
            lastStatus = saved?.lastStatus, durableConsent = saved?.durableConsent != null,
            consentVersion = saved?.durableConsent?.version,
            resumeAuthorized = configured && !blocked && !pending && saved?.durableConsent != null,
            waitingForHost = configured && fcm.maintenance == FcmMaintenanceState.WAITING_HOST,
            resumeBlocked = blocked, manualBlocked = true)
    }

    private fun recoveryUi(root: DaemonProtectionRootSnapshot, fcm: FcmProtectionUiState,
        interrupted: Boolean): FcmRecoveryUiState {
        val result = runCatching {
            val saved = root.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: FcmRecoverySnapshot()
            // Marker corruption is uncertainty even though the UI does not display consumption flags.
            root.bytes(DaemonDomainRole.FCM_CONSUMED)?.let(FcmRecoveryStoreCodec::decode)
            root.bytes(DaemonDomainRole.FCM_MCS_CONSUMED)?.let(FcmRecoveryStoreCodec::decode)
            val episodes = root.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)
                ?: FcmRecoveryEpisodeSnapshot()
            saved to episodes
        }
        val saved = result.getOrNull()?.first
        val blocked = interrupted || result.isFailure || fcm.storageBlocked
        return FcmRecoveryUiState(configured = saved?.configured == true && root.bytes(DaemonDomainRole.FCM_RECOVERY_HALT) == null,
            storageBlocked = blocked, requiresConfirmation = fcm.requiresConfirmation || blocked,
            detail = if (blocked) "恢复记录异常，已暂停" else fcm.detail,
            events = saved?.events.orEmpty(), episodesSnapshot = result.getOrNull()?.second ?: FcmRecoveryEpisodeSnapshot())
    }

    private fun hostDetail(status: DaemonAppStatus?, coherent: Boolean): String = when {
        status == null || !coherent -> "保护状态暂不可用"
        status.closing || status.released -> "保护已停止"
        else -> when (status.status) {
            DaemonOwnerStatus.FAULT -> "保护异常，请重试"
            DaemonOwnerStatus.PENDING_RECOVERY -> "恢复结果待确认"
            DaemonOwnerStatus.OFF -> "等待启动"
            DaemonOwnerStatus.PREPARED, DaemonOwnerStatus.UNRECONCILED -> "等待启动"
            DaemonOwnerStatus.ACTIVE -> "等待 Shizuku 就绪"
        }
    }

    private fun counts(root: DaemonProtectionRootSnapshot, selected: List<SelectedTargetIdentity>,
        configurationAvailable: Boolean, interrupted: Boolean, now: Long, zone: ZoneId): RecoveryCountsUiState {
        val bytes = root.bytes(DaemonDomainRole.RECOVERY_COUNTS)
        if (!configurationAvailable || interrupted || bytes == null || root.bytes(DaemonDomainRole.COUNTS_UNAVAILABLE) != null)
            return RecoveryCountsUiState(detail = "统计暂不可用")
        // snapshot() maintains clock/migration metadata. Its adapter is deliberately RAM-only.
        var memory = bytes.copyOf()
        val store = RecoveryCountsStore(object : RecoveryCountsStorage {
            override fun read() = memory.copyOf()
            override fun write(bytes: ByteArray) { memory = bytes.copyOf() }
            override fun markUnavailable() {}
        })
        return store.snapshot(selected, now, zone)
    }

    private fun fcm(root: DaemonProtectionRootSnapshot, status: DaemonAppStatus?, hostReady: Boolean,
        interrupted: Boolean, elapsed: Long): FcmProtectionUiState {
        val consentRead = runCatching {
            root.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: FcmProtectionConsent()
        }
        val read = runCatching {
            val consent = consentRead.getOrThrow()
            val heartbeat = root.bytes(DaemonDomainRole.HEARTBEAT)?.let {
                check(root.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) == true)
                HeartbeatSnapshotCodec.decode(it)
            }
            check(heartbeat != null || root.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED) == null)
            val recovery = root.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: FcmRecoverySnapshot()
            Triple(consent, heartbeat, recovery)
        }
        val configured = consentRead.getOrNull()?.configured == true && root.bytes(DaemonDomainRole.FCM_PROTECTION_HALT) == null
        val observation = status?.latestGmsObservation
        if (interrupted || read.isFailure) return FcmProtectionUiState(configured = configured, storageBlocked = true,
            maintenance = if (configured) FcmMaintenanceState.RECONFIRM else FcmMaintenanceState.OFF,
            requiresConfirmation = configured, latestObservation = observation, detail = "记录异常，已暂停")
        val (consent, heartbeat, recovery) = read.getOrThrow()
        val pending = root.bytes(DaemonDomainRole.HEARTBEAT_PENDING) != null || heartbeat?.pendingAttempt != null ||
            heartbeat?.pendingArm != null || heartbeat?.pause == HeartbeatPause.OFF_PENDING
        val blocked = root.bytes(DaemonDomainRole.HEARTBEAT_HALT) != null ||
            heartbeat?.resumeBlock != null || heartbeat?.currentEpochUnknown() == true ||
            heartbeat?.pause in setOf(HeartbeatPause.RECONFIRM, HeartbeatPause.UNKNOWN_RESULT,
                HeartbeatPause.LEFTOVER_PENDING, HeartbeatPause.STORAGE_FAILURE)
        val sample = observation as? GmsConnectionObservation.Sample
        val fresh = sample?.isFresh(elapsed) == true
        val confirmation = configured && (!consent.recoveryPrepared || blocked ||
            sample != null && !consent.accepts(sample, true))
        val owner = runCatching { root.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)?.let(DaemonHeartbeatOwnerCodec::decode) }.getOrNull()
        val native = heartbeat != null && owner != null && owner.record == root.control && owner.epoch == consent.epoch &&
            owner.generation == heartbeat.generation && owner.binding == heartbeat.bindingDigest &&
            owner.heartbeatBytesDigest == daemonHeartbeatOwnerDigest(heartbeat)
        val active = hostReady && fresh && native && heartbeat?.configured == true && heartbeat.pause == null &&
            heartbeat.remoteSessionId != null && consent.accepts(requireNotNull(sample), false)
        val maintenance = when {
            pending -> FcmMaintenanceState.STOP_PENDING
            !configured -> FcmMaintenanceState.OFF
            blocked -> FcmMaintenanceState.RECONFIRM
            !hostReady -> FcmMaintenanceState.WAITING_HOST
            active -> FcmMaintenanceState.ACTIVE
            else -> FcmMaintenanceState.WAITING_CONNECTED
        }
        val evidence = status?.recoveryReadiness
        val episodeRead = runCatching { root.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)?.episodes.orEmpty() }
        val recoveryUnsettled = episodeRead.isFailure || episodeRead.getOrNull().orEmpty().any {
            it.requestPending || it.endedAtMillis == null && it.requestStatus == FcmRecoveryStatus.UNKNOWN
        }
        val recoveryReady = configured && hostReady && fresh && !pending && !blocked && !confirmation && !recoveryUnsettled && consent.recoveryPrepared &&
            root.bytes(DaemonDomainRole.FCM_RECOVERY_HALT) == null && recovery.configured &&
            sample != null && consent.accepts(sample, true) && recovery.binding == FcmRecoveryPolicy.binding(sample) &&
            FcmRecoveryPolicy.qualified(sample, elapsed) && evidence?.admitted == true &&
            evidence.isFreshFor(root.control, sample, status?.sessionStartedElapsed, elapsed)
        return FcmProtectionUiState(configured = configured, requiresConfirmation = confirmation,
            maintenance = maintenance, recoveryReady = recoveryReady, latestObservation = observation,
            detail = when {
                pending -> "停止待确认"
                !configured -> "尚未开启"
                confirmation -> "请重新确认以继续保护"
                !hostReady -> "请开启自动保护，并启动 Shizuku"
                active -> "正在维护连接"
                !fresh -> "连接状态暂不可用"
                else -> "等待完整连接"
            })
    }
}
