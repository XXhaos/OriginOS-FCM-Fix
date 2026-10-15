package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import io.github.originosfcmguardian.ui.HomeSourceLoadState
import io.github.originosfcmguardian.ui.HomeUiState
import io.github.originosfcmguardian.ui.StoppedRecoveryPhase
import io.github.originosfcmguardian.ui.StoppedRecoveryUiState
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeSnapshot
import io.github.originosfcmguardian.fcmrecovery.FcmEpisodeAvailability

data class DiagnosticEnvironment(
    val sdkInt: Int,
    val androidRelease: String,
    val manufacturer: String,
    val model: String,
    val guardianVersionName: String,
    val guardianVersionCode: Long,
) {
    init {
        require(sdkInt > 0)
        require(guardianVersionCode > 0)
    }
}

enum class DiagnosticSourceAvailability {
    LOADING,
    AVAILABLE,
    EMPTY,
    PARTIAL,
    MISSING,
    CORRUPT,
    UNAVAILABLE,
}

data class DiagnosticTarget(
    val packageName: String,
    val currentLabel: String,
)

/** Historical journal metadata only; creation time is not a completion or readback time. */
data class DiagnosticRecoveryRecord(
    val packageName: String,
    val createdAtEpochMillis: Long,
    val source: StoppedRecoverySource,
    val stage: StoppedRecoveryStage,
    val outcome: StoppedRecoveryOutcome?,
)

data class DiagnosticStoppedRecovery(
    val loaded: Boolean = false,
    val storageBlocked: Boolean = false,
    val needsReconcile: Boolean = false,
    val busy: Boolean = false,
    val phase: StoppedRecoveryPhase = StoppedRecoveryPhase.IDLE,
    val latestRecord: DiagnosticRecoveryRecord? = null,
)

enum class DiagnosticAutomaticProtectionEvidenceScope { APP_RUNTIME_SNAPSHOT, DAEMON_PROJECTED_UI_SNAPSHOT }
enum class DiagnosticRecoveryOutcome { FLAG_CLEARED, STILL_STOPPED, NO_WRITE_NEEDED, BLOCKED, UNCONFIRMED, UNKNOWN }

/** Presentation flags already held in memory, without a new durable-authority check. */
data class DiagnosticAutomaticProtection(
    val evidenceScope: DiagnosticAutomaticProtectionEvidenceScope = DiagnosticAutomaticProtectionEvidenceScope.APP_RUNTIME_SNAPSHOT,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val savedAuthorization: Boolean = false,
    val revocationSaveFailed: Boolean = false,
    val waitingForPrivilege: Boolean = false,
    val waitingForReconciliation: Boolean = false,
    val lastCheckedAtEpochMillis: Long? = null,
    val lastOutcome: DiagnosticRecoveryOutcome? = null,
)

data class CombinedDiagnosticReport(
    val capturedAtEpochMillis: Long,
    val environment: DiagnosticEnvironment,
    val shizukuStatus: String,
    val targets: List<DiagnosticTarget>?,
    val sourceAvailability: Map<String, DiagnosticSourceAvailability>,
    val stoppedRecovery: DiagnosticStoppedRecovery = DiagnosticStoppedRecovery(),
    val automaticProtection: DiagnosticAutomaticProtection = DiagnosticAutomaticProtection(),
    val fcmRecoveryTrial: FcmRecoveryUiState = FcmRecoveryUiState(),
    val fcmRecoveryEpisodes: FcmRecoveryEpisodeSnapshot = FcmRecoveryEpisodeSnapshot(),
    val fcmProtection: io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState = io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState(),
    val protectionLifecycle: io.github.originosfcmguardian.autoprotection.ProtectionLifecycleSnapshot = io.github.originosfcmguardian.autoprotection.ProtectionLifecycleSnapshot(),
    val protectionResources: io.github.originosfcmguardian.autoprotection.ProtectionResourceSnapshot = io.github.originosfcmguardian.autoprotection.ProtectionResourceSnapshot(),
    val daemonDiagnostics: io.github.originosfcmguardian.daemon.DaemonDiagnosticsSnapshot = io.github.originosfcmguardian.daemon.DaemonDiagnosticsSnapshot(),
)

/**
 * Creates a bounded report from states already present on the home screen. Sensitive identity
 * fields are intentionally not represented by the report model, so the encoder cannot leak them.
 */
fun buildCombinedDiagnosticReport(
    state: HomeUiState,
    environment: DiagnosticEnvironment,
    capturedAtEpochMillis: Long,
): CombinedDiagnosticReport {
    require(capturedAtEpochMillis > 0)
    val recovery = state.stoppedRecovery
    val recoveryRecord = if (recovery.loaded && !recovery.storageBlocked) {
        val identity = recovery.recordIdentity
        val createdAt = recovery.recordedAtMillis?.takeIf { it > 0 }
        val source = recovery.recordSource
        val stage = recovery.recordStage
        if (identity != null && createdAt != null && source != null && stage != null) {
            DiagnosticRecoveryRecord(
                packageName = identity.packageName,
                createdAtEpochMillis = createdAt,
                source = source,
                stage = stage,
                outcome = recovery.recordOutcome.takeUnless {
                    recovery.needsReconcile || stage == StoppedRecoveryStage.PREPARED || stage == StoppedRecoveryStage.CLAIMED
                },
            )
        } else null
    } else null
    val automatic = state.automaticProtection
    val episodeSource = state.fcmRecovery.episodesSnapshot
    val episodeSnapshot = episodeSource.copy(episodes = java.util.Collections.unmodifiableList(
        if (episodeSource.availability == FcmEpisodeAvailability.AVAILABLE)
            episodeSource.episodes.map { it.copy() } else emptyList()))

    val sources = linkedMapOf(
        "targetSelection" to when {
            state.targetSelectionStoreCorrupt -> DiagnosticSourceAvailability.CORRUPT
            state.targetSelectionLoadState == HomeSourceLoadState.LOADING ->
                DiagnosticSourceAvailability.LOADING
            state.targetSelectionLoadState == HomeSourceLoadState.FAILED ->
                DiagnosticSourceAvailability.UNAVAILABLE
            state.selectedTargets.isEmpty() -> DiagnosticSourceAvailability.EMPTY
            else -> DiagnosticSourceAvailability.AVAILABLE
        },
        // Retired source remains a null/unavailable tombstone for existing report readers.
        "dailyProtection" to DiagnosticSourceAvailability.UNAVAILABLE,
        "stoppedRecovery" to recoveryAvailability(recovery, recoveryRecord),
        "automaticProtection" to DiagnosticSourceAvailability.AVAILABLE,
        "fcmRecoveryEpisodes" to episodeSourceAvailability(episodeSnapshot),
    )

    return CombinedDiagnosticReport(
        capturedAtEpochMillis = capturedAtEpochMillis,
        environment = environment.copy(
            androidRelease = environment.androidRelease.take(MAX_TEXT_LENGTH),
            manufacturer = environment.manufacturer.take(MAX_TEXT_LENGTH),
            model = environment.model.take(MAX_TEXT_LENGTH),
            guardianVersionName = environment.guardianVersionName.take(MAX_TEXT_LENGTH),
        ),
        shizukuStatus = state.shizuku.status.name,
        targets = if (
            state.targetSelectionStoreCorrupt ||
            state.targetSelectionLoadState != HomeSourceLoadState.LOADED
        ) {
            null
        } else {
            state.selectedTargets.map { target ->
                DiagnosticTarget(
                    packageName = target.identity.packageName,
                    currentLabel = target.label.take(MAX_LABEL_LENGTH),
                )
            }
        },
        sourceAvailability = sources,
        fcmRecoveryTrial = state.fcmRecovery.copy(events = state.fcmRecovery.events.takeLast(64),
            episodesSnapshot = FcmRecoveryEpisodeSnapshot()),
        fcmRecoveryEpisodes = episodeSnapshot,
        fcmProtection = state.fcmProtection.copy(latestObservation = null),
        stoppedRecovery = DiagnosticStoppedRecovery(
            loaded = recovery.loaded,
            storageBlocked = recovery.storageBlocked,
            needsReconcile = recovery.needsReconcile,
            busy = recovery.busy,
            phase = recovery.phase,
            latestRecord = recoveryRecord,
        ),
        automaticProtection = DiagnosticAutomaticProtection(
            evidenceScope = if (state.automaticProtectionFromDaemon) DiagnosticAutomaticProtectionEvidenceScope.DAEMON_PROJECTED_UI_SNAPSHOT
                else DiagnosticAutomaticProtectionEvidenceScope.APP_RUNTIME_SNAPSHOT,
            enabled = automatic.enabled,
            busy = automatic.busy,
            savedAuthorization = automatic.savedAuthorization,
            revocationSaveFailed = automatic.revocationSaveFailed,
            waitingForPrivilege = automatic.waitingForPrivilege,
            waitingForReconciliation = automatic.waitingForReconciliation,
            lastCheckedAtEpochMillis = automatic.lastCheckedAtMillis?.takeIf { it > 0 },
            lastOutcome = automatic.lastOutcome?.let { value ->
                DiagnosticRecoveryOutcome.entries.firstOrNull { it.name == value } ?: DiagnosticRecoveryOutcome.UNKNOWN
            },
        ),
    )
}

private fun recoveryAvailability(
    recovery: StoppedRecoveryUiState,
    record: DiagnosticRecoveryRecord?,
): DiagnosticSourceAvailability = when {
    recovery.storageBlocked -> DiagnosticSourceAvailability.UNAVAILABLE
    !recovery.loaded -> DiagnosticSourceAvailability.LOADING
    recovery.needsReconcile || recovery.busy || recovery.phase == StoppedRecoveryPhase.RECOVERING ||
        recovery.phase == StoppedRecoveryPhase.UNCONFIRMED || record?.stage == StoppedRecoveryStage.PREPARED ||
        record?.stage == StoppedRecoveryStage.CLAIMED || record?.outcome == StoppedRecoveryOutcome.UNCONFIRMED ->
        DiagnosticSourceAvailability.PARTIAL
    record != null -> DiagnosticSourceAvailability.AVAILABLE
    recovery.recordIdentity != null || recovery.recordedAtMillis != null || recovery.recordSource != null ||
        recovery.recordStage != null || recovery.recordOutcome != null || recovery.receiptOutcome != null ||
        recovery.phase != StoppedRecoveryPhase.IDLE -> DiagnosticSourceAvailability.PARTIAL
    else -> DiagnosticSourceAvailability.EMPTY
}

internal fun episodeSourceAvailability(snapshot: FcmRecoveryEpisodeSnapshot): DiagnosticSourceAvailability =
    when (snapshot.availability) {
        FcmEpisodeAvailability.NOT_LOADED -> DiagnosticSourceAvailability.LOADING
        FcmEpisodeAvailability.CORRUPT -> DiagnosticSourceAvailability.CORRUPT
        FcmEpisodeAvailability.UNAVAILABLE -> DiagnosticSourceAvailability.UNAVAILABLE
        FcmEpisodeAvailability.AVAILABLE -> when {
            snapshot.writeFailures > 0 || snapshot.droppedUpdates > 0 -> DiagnosticSourceAvailability.PARTIAL
            snapshot.episodes.isEmpty() -> DiagnosticSourceAvailability.EMPTY
            else -> DiagnosticSourceAvailability.AVAILABLE
        }
    }

/** Fixed JSON schema. It contains summaries only, never raw events, tokens or installation IDs. */
fun encodeCombinedDiagnosticReport(report: CombinedDiagnosticReport): ByteArray {
    val json = buildString {
        append("{\"schema\":").string(DIAGNOSTIC_REPORT_SCHEMA)
        append(",\"capturedAtEpochMillis\":").append(report.capturedAtEpochMillis)
        append(",\"environment\":{")
        field("sdkInt", report.environment.sdkInt)
        field("androidRelease", report.environment.androidRelease)
        field("manufacturer", report.environment.manufacturer)
        field("model", report.environment.model)
        field("guardianVersionName", report.environment.guardianVersionName)
        field("guardianVersionCode", report.environment.guardianVersionCode, comma = false)
        append('}')
        append(",\"shizukuStatus\":").string(report.shizukuStatus)
        append(",\"targets\":")
        report.targets?.let { targets ->
            append('[')
            targets.forEachIndexed { index, target ->
                if (index > 0) append(',')
                append('{')
                field("packageName", target.packageName)
                field("currentLabel", target.currentLabel, comma = false)
                append('}')
            }
            append(']')
        } ?: append("null")
        append(",\"dailyProtection\":{")
        field("scope", "SELECTED_APP_INSTALLATION_IDENTITY_ONLY")
        field("retired", true)
        field("status", null as String?)
        field("checkedAtEpochMillis", null as Long?, comma = false)
        append('}')
        append(",\"stoppedRecovery\":{")
        with(report.stoppedRecovery) {
            field("loaded", loaded)
            field("storageBlocked", storageBlocked)
            field("needsReconcile", needsReconcile)
            field("busy", busy)
            field("phase", phase.name)
            append("\"latestRecord\":")
            latestRecord?.let { record ->
                append('{')
                field("packageName", record.packageName)
                field("createdAtEpochMillis", record.createdAtEpochMillis)
                field("source", record.source.name)
                field("stage", record.stage.name)
                field("outcome", record.outcome?.name, comma = false)
                append('}')
            } ?: append("null")
        }
        append('}')
        append(",\"automaticProtection\":{")
        with(report.automaticProtection) {
            field("evidenceScope", evidenceScope.name)
            field("enabled", enabled)
            field("busy", busy)
            field("savedAuthorization", savedAuthorization)
            field("revocationSaveFailed", revocationSaveFailed)
            field("waitingForPrivilege", waitingForPrivilege)
            field("waitingForReconciliation", waitingForReconciliation)
            field("lastCheckedAtEpochMillis", lastCheckedAtEpochMillis)
            field("lastOutcome", lastOutcome?.name, comma = false)
        }
        append('}')
        append(",\"protectionLifecycle\":{")
        with(report.protectionLifecycle) {
            field("availability", availability.name)
            field("droppedEvents", droppedEvents)
            field("systemExitHistoryAvailable", exitHistoryAvailable)
            field("serviceHasNoRecordedEnd", serviceExpected)
            field("evidenceLimit", "No clean service end recorded is not proof of an OS kill. Records are asynchronous; unavailable or dropped records leave cause unknown. System exits use their original historical time; no description or trace is collected.")
            append("\"events\":[")
            events.takeLast(64).forEachIndexed { index, event ->
                if (index > 0) append(',')
                append('{')
                field("atEpochMillis", event.atMillis)
                field("elapsedMillis", event.elapsedMillis)
                field("guardianProcessId", event.processId)
                field("event", event.kind.name)
                field("reason", event.reason.name)
                field("systemExitReason", event.systemExitReason?.toLong())
                field("systemExitStatus", event.systemExitStatus?.toLong(), comma = false)
                append('}')
            }
            append("]}")
        }
        append(",\"protectionResources\":")
        appendProtectionResourceJson(report.protectionResources, report.daemonDiagnostics.availability.name)
        append(",\"daemonDiagnostics\":")
        appendDaemonDiagnosticsJson(report.daemonDiagnostics)
        append(",\"sourceAvailability\":{")
        report.sourceAvailability.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            string(entry.key).append(':').string(entry.value.name)
        }
        append('}')
        append(",\"fcmProtection\":{")
        field("configured", report.fcmProtection.configured)
        field("storageBlocked", report.fcmProtection.storageBlocked)
        field("requiresConfirmation", report.fcmProtection.requiresConfirmation)
        field("maintenance", report.fcmProtection.maintenance.name)
        field("confirmationFailure", report.fcmProtection.confirmationFailure?.name)
        field("recoveryReady", report.fcmProtection.recoveryReady, comma = false)
        append('}')
        append(",\"fcmRecoveryTrial\":{")
        field("configured", report.fcmRecoveryTrial.configured)
        field("storageBlocked", report.fcmRecoveryTrial.storageBlocked)
        field("requiresConfirmation", report.fcmRecoveryTrial.requiresConfirmation)
        field("powerReferenceScope", "WHOLE_PHONE_BATTERY_AND_GUARDIAN_MAIN_PROCESS_CPU_REFERENCE_ONLY")
        field("powerReferenceLimitations", "Battery is whole-phone correlation, not Guardian-attributed mAh or energy. CPU excludes Shizuku/shell/GMS/radio; compare only identical processInstanceId with continuous nondecreasing process age and CPU.")
        append("\"events\":[")
        report.fcmRecoveryTrial.events.takeLast(64).forEachIndexed { index, event ->
            if (index > 0) append(',')
            append('{')
            field("atEpochMillis", event.atMillis)
            field("elapsedMillis", event.elapsedMillis)
            field("source", "GUARDIAN_FCM_TRIAL")
            field("action", event.action.name)
            field("service", event.service.name)
            field("completeness", event.completeness.name)
            field("socket", event.socket.name)
            field("eligible", event.eligible)
            field("eligibility", event.eligibility.name)
            field("requestStatus", event.status?.name)
            field("requestReason", event.reason?.name)
            event.operation?.let { field("requestOperation", it.name) }
            field("observationUnknownReason", event.observationUnknownReason?.name)
            append("\"powerReference\":")
            event.power?.let { p ->
                append('{')
                field("wholePhoneBatteryPercent", p.batteryPercent?.toLong())
                field("chargingStatus", p.chargingStatus?.name)
                field("pluggedSource", p.pluggedSource?.name)
                field("deviceInteractive", p.interactive)
                field("deviceIdle", p.deviceIdle)
                field("guardianMainProcessCpuMillis", p.mainProcessCpuMillis)
                field("processAgeMillis", p.processAgeMillis)
                field("processInstanceId", p.processInstanceId, comma = false)
                append('}')
            } ?: append("null")
            append('}')
        }
        append("]}")
        append(",\"fcmRecoveryEpisodes\":{")
        with(report.fcmRecoveryEpisodes) {
            val visible = if (availability == FcmEpisodeAvailability.AVAILABLE)
                episodes.takeLast(MAX_EPISODE_PREVIEW) else emptyList()
            field("availability", availability.name)
            field("retainedEpisodeCount", if (availability == FcmEpisodeAvailability.AVAILABLE) episodes.size.toLong() else null)
            field("evictedEpisodes", evictedEpisodes)
            field("writeFailures", writeFailures)
            field("droppedUpdates", droppedUpdates)
            field("previewLimit", MAX_EPISODE_PREVIEW)
            field("omittedFromPreview", if (availability == FcmEpisodeAvailability.AVAILABLE)
                (episodes.size - visible.size).toLong() else null)
            field("previewTruncated", episodes.size > visible.size && availability == FcmEpisodeAvailability.AVAILABLE)
            field("evidenceLimit", FCM_EPISODE_EVIDENCE_LIMIT)
            append("\"episodes\":[")
            visible.forEachIndexed { index, episode ->
                if (index > 0) append(',')
                append('{')
                field("sequence", episode.sequence)
                field("processId", episode.processId)
                field("processStartedElapsedMillis", episode.processStartedElapsedMillis)
                field("startedAtEpochMillis", episode.startedAtMillis)
                field("startedElapsedMillis", episode.startedElapsedMillis)
                field("kind", episode.kind.name)
                field("lastObservedAtEpochMillis", episode.lastObservedAtMillis)
                field("lastObservedElapsedMillis", episode.lastObservedElapsedMillis)
                field("requestAtEpochMillis", episode.requestAtMillis)
                field("requestElapsedMillis", episode.requestElapsedMillis)
                field("requestPending", episode.requestPending)
                field("requestStatus", episode.requestStatus?.name)
                episode.requestOperation?.let { field("requestOperation", it.name) }
                field("resultAtEpochMillis", episode.resultAtMillis)
                field("resultElapsedMillis", episode.resultElapsedMillis)
                field("requestReason", episode.reason?.name)
                field("endedAtEpochMillis", episode.endedAtMillis)
                field("endedElapsedMillis", episode.endedElapsedMillis)
                field("outcome", episode.outcome.name)
                field("timeoutObserved", episode.timeoutObserved)
                field("lastBlock", episode.lastBlock.name, comma = false)
                append('}')
            }
            append("]}")
        }
        append(",\"unknowns\":{")
        field("fcmToNotificationCausality", UNKNOWN)
        field("appWakeCause", UNKNOWN)
        field("continuousFcmConnection", UNKNOWN)
        field("notificationInstallationContinuity", UNKNOWN, comma = false)
        append('}')
        append(",\"removedCapabilities\":")
        stringArray(REMOVED_DIAGNOSTIC_CAPABILITIES)
        append(",\"limitations\":")
        stringArray(DIAGNOSTIC_REPORT_LIMITATIONS)
        append('}')
    }
    val bytes = json.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_REPORT_BYTES) { "Combined diagnostic report exceeds size limit" }
    return bytes
}

private fun StringBuilder.field(name: String, value: String?, comma: Boolean = true): StringBuilder {
    string(name).append(':').nullableString(value)
    if (comma) append(',')
    return this
}

private fun StringBuilder.field(name: String, value: Long?, comma: Boolean = true): StringBuilder {
    string(name).append(':').append(value ?: "null")
    if (comma) append(',')
    return this
}

private fun StringBuilder.field(name: String, value: Int, comma: Boolean = true): StringBuilder =
    field(name, value.toLong(), comma)

private fun StringBuilder.field(name: String, value: Boolean?, comma: Boolean = true): StringBuilder {
    string(name).append(':').append(value?.toString() ?: "null")
    if (comma) append(',')
    return this
}

private fun StringBuilder.nullableString(value: String?): StringBuilder =
    if (value == null) append("null") else string(value)

private fun StringBuilder.stringArray(values: List<String>): StringBuilder {
    append('[')
    values.forEachIndexed { index, value ->
        if (index > 0) append(',')
        string(value)
    }
    return append(']')
}

private fun StringBuilder.string(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    return append('"')
}

internal class PreparedCombinedDiagnosticExport {
    private var bytes: ByteArray? = null

    fun prepare(snapshot: ByteArray) {
        bytes = snapshot.copyOf()
    }

    fun invalidate() {
        bytes = null
    }

    fun consume(documentSelected: Boolean): CombinedDiagnosticExportResult {
        val snapshot = bytes
        bytes = null
        return when {
            !documentSelected -> CombinedDiagnosticExportResult.Cancelled
            snapshot == null -> CombinedDiagnosticExportResult.Missing
            else -> CombinedDiagnosticExportResult.Ready(snapshot)
        }
    }
}

internal sealed interface CombinedDiagnosticExportResult {
    data object Cancelled : CombinedDiagnosticExportResult
    data object Missing : CombinedDiagnosticExportResult
    data class Ready(val bytes: ByteArray) : CombinedDiagnosticExportResult
}

private const val UNKNOWN = "UNKNOWN"
private const val MAX_LABEL_LENGTH = 80
private const val MAX_TEXT_LENGTH = 128
// Finite reserve for operation attribution in at most 64 events and 8 episode previews.
internal const val MAX_REPORT_BYTES = 1024 * 1024
internal const val DIAGNOSTIC_REPORT_SCHEMA = "guardian.combined-diagnostic.v13"
private const val LIMITATION_SUMMARY_ONLY =
    "This report contains bounded summaries of already known app state and no raw notification or process events."
private const val LIMITATION_CAUSALITY =
    "Temporal proximity does not prove FCM delivery, app wake, notification publication cause, or continuous connectivity."
private const val LIMITATION_CORRUPT =
    "A source marked CORRUPT, LOADING, MISSING, or UNAVAILABLE has null, UNKNOWN, or omitted values and must not be interpreted as empty or healthy."
private const val LIMITATION_SAMPLE_TIMES =
    "CapturedAt is the export time; component values were sampled independently and may be older or have different read times."
private const val LIMITATION_LIVE_STATE =
    "This click-time snapshot does not perform new privileged reads and cannot confirm state changes after capture."

internal val DIAGNOSTIC_REPORT_LIMITATIONS = listOf(
    LIMITATION_SUMMARY_ONLY,
    LIMITATION_CAUSALITY,
    LIMITATION_CORRUPT,
    LIMITATION_SAMPLE_TIMES,
    LIMITATION_LIVE_STATE,
    FCM_EPISODE_EVIDENCE_LIMIT,
    "Deep monitoring, notification fact collection and Guardian Firebase self-test are removed from this build. Historical files are not read or deleted. No collection does not prove absence of events or delivery failures.",
    "Scheduled read-only inspection is removed. Its compatibility report fields are null and unavailable; historical inspection files are not read or deleted.",
    "Recovery record time is journal creation time, not command completion or readback time.",
    "RECONCILED describes a later read-only observation, not success of the original command.",
    "A historical package recovery record does not prove the current installation state or notification delivery.",
    "Automatic protection flags are app runtime presentation state without new durable authorization or privilege checks.",
    "Automatic last check time and retained outcome are independent; the check time is not an outcome timestamp.",
)

internal val REMOVED_DIAGNOSTIC_CAPABILITIES = listOf(
    "DEEP_MONITORING", "NOTIFICATION_FACT_COLLECTION", "GUARDIAN_FIREBASE_SELF_TEST", "SCHEDULED_READ_ONLY_INSPECTION",
)

internal const val MAX_EPISODE_PREVIEW = 8
internal const val FCM_EPISODE_EVIDENCE_LIMIT =
    "CONNECTION_AFTER_REQUEST records a later qualified connection observation, not unique recovery causality or notification delivery. Request start, pending and returned result times are separate; missing legacy result times remain unknown. REQUESTED is command acceptance only. PRESENT_DISCONNECTED does not authorize service-absence recovery. Episode timestamps describe retained transition records, not every completed poll. Coverage gaps, unavailable storage, evictions and preview omissions leave missing history unknown; no incident record is not proof of uninterrupted connection."
