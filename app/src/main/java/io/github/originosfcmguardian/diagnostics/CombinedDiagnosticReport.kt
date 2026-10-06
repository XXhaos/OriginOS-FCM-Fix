package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.monitoring.MonitorSessionStatus
import io.github.originosfcmguardian.notification.NotificationFactKind
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import io.github.originosfcmguardian.ui.HomeSourceLoadState
import io.github.originosfcmguardian.ui.HomeUiState
import io.github.originosfcmguardian.ui.StoppedRecoveryPhase
import io.github.originosfcmguardian.ui.StoppedRecoveryUiState
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState

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

data class DiagnosticFcmReceipt(
    val receivedAtEpochMillis: Long,
    val messagePriority: String,
    val notificationOutcome: String,
    val deviceInteractive: String,
    val deviceIdleMode: String,
    val networkAvailability: String,
    val handlerDurationMillis: Long?,
    val processAgeAtCallbackMillis: Long?,
)

data class DiagnosticNotificationFacts(
    val enabled: Boolean,
    val accessGranted: Boolean,
    val connected: Boolean,
    val retainedCount: Int,
    val postedOrUpdatedCount: Int,
    val removedCount: Int,
    val latestObservedAtEpochMillis: Long?,
    val droppedEvents: Int,
    val stopPersistenceUncertain: Boolean,
)

data class DiagnosticMonitorSummary(
    val packageName: String,
    val startedAtEpochMillis: Long,
    val deadlineEpochMillis: Long,
    val status: String,
    val retainedEventCount: Int,
    val processStartCount: Int,
    val uidActiveCount: Int,
    val uidIdleCount: Int,
    val uidRunningCount: Int,
    val uidStoppedCount: Int,
    val pollCount: Int,
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

enum class DiagnosticAutomaticProtectionEvidenceScope { APP_RUNTIME_SNAPSHOT }
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
    val fcmConfiguration: String,
    val fcmRegistration: String,
    val notificationPermission: String,
    val latestFcmReceipt: DiagnosticFcmReceipt?,
    val notificationFacts: DiagnosticNotificationFacts?,
    val monitor: DiagnosticMonitorSummary?,
    val dailyProtectionStatus: String?,
    val dailyProtectionCheckedAtEpochMillis: Long?,
    val sourceAvailability: Map<String, DiagnosticSourceAvailability>,
    val stoppedRecovery: DiagnosticStoppedRecovery = DiagnosticStoppedRecovery(),
    val automaticProtection: DiagnosticAutomaticProtection = DiagnosticAutomaticProtection(),
    val fcmRecoveryTrial: FcmRecoveryUiState = FcmRecoveryUiState(),
    val fcmProtection: io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState = io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState(),
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
    val receipt = state.fcm.latestReceipt
    val notifications = state.notificationFacts.events.take(MAX_NOTIFICATION_FACTS)
    val monitorSession = state.deepMonitoring.session
    val monitorEvents = monitorSession?.events?.take(MAX_MONITOR_EVENTS).orEmpty()
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
        "fcmEvidence" to when {
            state.fcm.evidenceCorrupt -> DiagnosticSourceAvailability.CORRUPT
            state.fcmEvidenceLoadState == HomeSourceLoadState.LOADING ->
                DiagnosticSourceAvailability.LOADING
            state.fcmEvidenceLoadState == HomeSourceLoadState.FAILED ->
                DiagnosticSourceAvailability.UNAVAILABLE
            receipt == null -> DiagnosticSourceAvailability.EMPTY
            else -> DiagnosticSourceAvailability.AVAILABLE
        },
        "notificationFacts" to when {
            state.notificationFacts.historyCorrupt -> DiagnosticSourceAvailability.CORRUPT
            state.notificationFactsLoadState == HomeSourceLoadState.LOADING ->
                DiagnosticSourceAvailability.LOADING
            state.notificationFactsLoadState == HomeSourceLoadState.FAILED ->
                DiagnosticSourceAvailability.UNAVAILABLE
            state.notificationFacts.droppedEvents > 0 || state.notificationFacts.stopPersistenceUncertain ->
                DiagnosticSourceAvailability.PARTIAL
            notifications.isEmpty() -> DiagnosticSourceAvailability.EMPTY
            else -> DiagnosticSourceAvailability.AVAILABLE
        },
        "recentMonitor" to when {
            state.deepMonitoring.historyCorrupt -> DiagnosticSourceAvailability.CORRUPT
            state.deepMonitoringLoadState == HomeSourceLoadState.LOADING ->
                DiagnosticSourceAvailability.LOADING
            state.deepMonitoringLoadState == HomeSourceLoadState.FAILED ->
                DiagnosticSourceAvailability.UNAVAILABLE
            monitorSession == null -> DiagnosticSourceAvailability.EMPTY
            monitorSession.status in PARTIAL_MONITOR_STATUSES -> DiagnosticSourceAvailability.PARTIAL
            else -> DiagnosticSourceAvailability.AVAILABLE
        },
        "dailyProtection" to when (state.dailyProtectionLoadState) {
            HomeSourceLoadState.LOADING -> DiagnosticSourceAvailability.LOADING
            HomeSourceLoadState.FAILED -> DiagnosticSourceAvailability.UNAVAILABLE
            HomeSourceLoadState.LOADED -> if (state.dailyProtectionReport == null) {
                DiagnosticSourceAvailability.EMPTY
            } else if (state.dailyProtectionReport.containsRetiredAutostartEvidence) {
                DiagnosticSourceAvailability.PARTIAL
            } else {
                DiagnosticSourceAvailability.AVAILABLE
            }
        },
        "stoppedRecovery" to recoveryAvailability(recovery, recoveryRecord),
        "automaticProtection" to DiagnosticSourceAvailability.AVAILABLE,
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
        fcmConfiguration = state.fcm.configuration.name,
        fcmRegistration = state.fcm.registration.name,
        notificationPermission = state.fcm.notificationPermission.name,
        latestFcmReceipt = if (
            state.fcm.evidenceCorrupt || state.fcmEvidenceLoadState != HomeSourceLoadState.LOADED
        ) null else receipt?.let {
            DiagnosticFcmReceipt(
                receivedAtEpochMillis = it.receivedAtEpochMillis,
                messagePriority = it.messagePriority.name,
                notificationOutcome = it.notificationOutcome.name,
                deviceInteractive = it.deviceInteractive.name,
                deviceIdleMode = it.deviceIdleMode.name,
                networkAvailability = it.networkAvailability.name,
                handlerDurationMillis = it.handlerDurationMillis,
                processAgeAtCallbackMillis = it.processAgeAtCallbackMillis,
            )
        },
        notificationFacts = if (
            state.notificationFacts.historyCorrupt ||
            state.notificationFactsLoadState != HomeSourceLoadState.LOADED
        ) null else DiagnosticNotificationFacts(
            enabled = state.notificationFacts.enabled,
            accessGranted = state.notificationFacts.accessGranted,
            connected = state.notificationFacts.connected,
            retainedCount = notifications.size,
            postedOrUpdatedCount = notifications.count { it.kind == NotificationFactKind.POSTED_OR_UPDATED },
            removedCount = notifications.count { it.kind == NotificationFactKind.REMOVED },
            latestObservedAtEpochMillis = notifications.maxOfOrNull { it.observedAtEpochMillis },
            droppedEvents = state.notificationFacts.droppedEvents.coerceAtLeast(0),
            stopPersistenceUncertain = state.notificationFacts.stopPersistenceUncertain,
        ),
        monitor = if (
            state.deepMonitoring.historyCorrupt ||
            state.deepMonitoringLoadState != HomeSourceLoadState.LOADED
        ) null else monitorSession?.let { session ->
            DiagnosticMonitorSummary(
                packageName = session.identity.packageName,
                startedAtEpochMillis = session.startedAtEpochMillis,
                deadlineEpochMillis = session.deadlineEpochMillis,
                status = session.status.name,
                retainedEventCount = monitorEvents.size,
                processStartCount = monitorEvents.count { it.kind == ProcessTraceKind.PROCESS_START },
                uidActiveCount = monitorEvents.count { it.kind == ProcessTraceKind.UID_ACTIVE },
                uidIdleCount = monitorEvents.count { it.kind == ProcessTraceKind.UID_IDLE },
                uidRunningCount = monitorEvents.count { it.kind == ProcessTraceKind.UID_RUNNING },
                uidStoppedCount = monitorEvents.count { it.kind == ProcessTraceKind.UID_STOPPED },
                pollCount = session.pollCount,
            )
        },
        dailyProtectionStatus = if (state.dailyProtectionLoadState == HomeSourceLoadState.LOADED) {
            state.dailyProtectionReport?.let {
                if (it.containsRetiredAutostartEvidence) UNKNOWN else it.status.name
            }
        } else null,
        dailyProtectionCheckedAtEpochMillis = if (
            state.dailyProtectionLoadState == HomeSourceLoadState.LOADED
        ) state.dailyProtectionReport?.checkedAtEpochMillis else null,
        sourceAvailability = sources,
        fcmRecoveryTrial = state.fcmRecovery.copy(events = state.fcmRecovery.events.takeLast(64)),
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
        append(",\"fcm\":{")
        field("configuration", report.fcmConfiguration)
        field("registration", report.fcmRegistration)
        field("notificationPermission", report.notificationPermission)
        append("\"latestReceipt\":")
        report.latestFcmReceipt?.let { receipt ->
            append('{')
            field("receivedAtEpochMillis", receipt.receivedAtEpochMillis)
            field("messagePriority", receipt.messagePriority)
            field("notificationOutcome", receipt.notificationOutcome)
            field("deviceInteractive", receipt.deviceInteractive)
            field("deviceIdleMode", receipt.deviceIdleMode)
            field("networkAvailability", receipt.networkAvailability)
            field("handlerDurationMillis", receipt.handlerDurationMillis)
            field("processAgeAtCallbackMillis", receipt.processAgeAtCallbackMillis, comma = false)
            append('}')
        } ?: append("null")
        append('}')
        append(",\"notificationFacts\":")
        report.notificationFacts?.let { facts ->
            append('{')
            field("enabled", facts.enabled)
            field("accessGranted", facts.accessGranted)
            field("connected", facts.connected)
            field("retainedCount", facts.retainedCount)
            field("postedOrUpdatedCount", facts.postedOrUpdatedCount)
            field("removedCount", facts.removedCount)
            field("latestObservedAtEpochMillis", facts.latestObservedAtEpochMillis)
            field("droppedEvents", facts.droppedEvents)
            field("stopPersistenceUncertain", facts.stopPersistenceUncertain, comma = false)
            append('}')
        } ?: append("null")
        append(",\"recentMonitor\":")
        report.monitor?.let { monitor ->
            append('{')
            field("packageName", monitor.packageName)
            field("startedAtEpochMillis", monitor.startedAtEpochMillis)
            field("deadlineEpochMillis", monitor.deadlineEpochMillis)
            field("status", monitor.status)
            field("retainedEventCount", monitor.retainedEventCount)
            field("processStartCount", monitor.processStartCount)
            field("uidActiveCount", monitor.uidActiveCount)
            field("uidIdleCount", monitor.uidIdleCount)
            field("uidRunningCount", monitor.uidRunningCount)
            field("uidStoppedCount", monitor.uidStoppedCount)
            field("pollCount", monitor.pollCount, comma = false)
            append('}')
        } ?: append("null")
        append(",\"dailyProtection\":{")
        field("scope", "SELECTED_APP_INSTALLATION_IDENTITY_ONLY")
        field("status", report.dailyProtectionStatus)
        field("checkedAtEpochMillis", report.dailyProtectionCheckedAtEpochMillis, comma = false)
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
        append(",\"unknowns\":{")
        field("fcmToNotificationCausality", UNKNOWN)
        field("appWakeCause", UNKNOWN)
        field("continuousFcmConnection", UNKNOWN)
        field("notificationInstallationContinuity", UNKNOWN, comma = false)
        append('}')
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
private const val MAX_NOTIFICATION_FACTS = 200
private const val MAX_MONITOR_EVENTS = 500
private const val MAX_LABEL_LENGTH = 80
private const val MAX_TEXT_LENGTH = 128
internal const val MAX_REPORT_BYTES = 64 * 1024
internal const val DIAGNOSTIC_REPORT_SCHEMA = "guardian.combined-diagnostic.v7"
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
    "Daily protection checks selected app installation identity only, not stopped flags, FCM connectivity, or notification delivery; retired checks are UNKNOWN.",
    "Recovery record time is journal creation time, not command completion or readback time.",
    "RECONCILED describes a later read-only observation, not success of the original command.",
    "A historical package recovery record does not prove the current installation state or notification delivery.",
    "Automatic protection flags are app runtime presentation state without new durable authorization or privilege checks.",
    "Automatic last check time and retained outcome are independent; the check time is not an outcome timestamp.",
)

private val PARTIAL_MONITOR_STATUSES = setOf(
    MonitorSessionStatus.RUNNING,
    MonitorSessionStatus.UNAVAILABLE,
    MonitorSessionStatus.IDENTITY_CHANGED,
    MonitorSessionStatus.LIMIT_REACHED,
    MonitorSessionStatus.INTERRUPTED,
)
