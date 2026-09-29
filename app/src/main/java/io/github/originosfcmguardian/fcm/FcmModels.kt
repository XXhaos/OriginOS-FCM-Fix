package io.github.originosfcmguardian.fcm

data class GuardianProbe(
    val testId: String,
    val sentAtEpochMillis: Long,
)

enum class MessagePriority {
    HIGH,
    NORMAL,
    UNKNOWN,
}

enum class ObservedBoolean {
    YES,
    NO,
    UNKNOWN,
}

enum class NetworkAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

enum class NotificationOutcome {
    RECEIPT_STORED,
    PERMISSION_DENIED,
    APP_NOTIFICATIONS_BLOCKED,
    CHANNEL_BLOCKED,
    POST_REQUESTED,
    POST_FAILED,
}

data class FcmReceipt(
    val testId: String,
    val sentAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val receivedAtElapsedMillis: Long,
    val messagePriority: MessagePriority,
    val deviceInteractive: ObservedBoolean,
    val deviceIdleMode: ObservedBoolean,
    val networkAvailability: NetworkAvailability,
    val notificationOutcome: NotificationOutcome = NotificationOutcome.RECEIPT_STORED,
    val handlerDurationMillis: Long? = null,
    val processStartedAtElapsedMillis: Long? = null,
) {
    init {
        require(GuardianProbeParser.isSafeTestId(testId))
        require(sentAtEpochMillis > 0)
        require(receivedAtEpochMillis > 0)
        require(receivedAtElapsedMillis >= 0)
        require(handlerDurationMillis == null || handlerDurationMillis >= 0)
        require(
            processStartedAtElapsedMillis == null ||
                processStartedAtElapsedMillis in 0..receivedAtElapsedMillis,
        )
    }

    /** Cross-clock comparison only. It can be negative or inaccurate when clocks differ. */
    val apparentWallClockDeltaMillis: Long
        get() = receivedAtEpochMillis - sentAtEpochMillis

    /**
     * Process lifetime at this callback, when a coherent monotonic start time was captured.
     * This alone proves neither absence before send nor that FCM exclusively started the process.
     */
    val processAgeAtCallbackMillis: Long?
        get() = processStartedAtElapsedMillis?.let { receivedAtElapsedMillis - it }
}

sealed interface FcmEvidenceSnapshot {
    data object Empty : FcmEvidenceSnapshot
    data class Available(val receipts: List<FcmReceipt>) : FcmEvidenceSnapshot
    data object Corrupt : FcmEvidenceSnapshot
}

sealed interface RecordReceiptResult {
    data class Stored(val receipt: FcmReceipt) : RecordReceiptResult
    data object Duplicate : RecordReceiptResult
    data object Corrupt : RecordReceiptResult
    data object Failed : RecordReceiptResult
}

enum class FcmConfigurationStatus {
    MISSING,
    CONFIGURED_NOT_TESTED,
}

enum class RegistrationStatus {
    NOT_REQUESTED,
    REQUESTING,
    TOKEN_READY,
    CONFIG_MISSING,
    TIMED_OUT,
    SDK_IO_FAILURE,
    FAILED,
}

enum class NotificationPermissionStatus {
    GRANTED,
    REQUIRED,
}

data class FcmUiState(
    val configuration: FcmConfigurationStatus = FcmConfigurationStatus.MISSING,
    val registration: RegistrationStatus = RegistrationStatus.NOT_REQUESTED,
    val registrationDetail: String = "尚未请求注册",
    val token: SensitiveToken? = null,
    val tokenVisible: Boolean = false,
    val clipboardDetail: String? = null,
    val notificationPermission: NotificationPermissionStatus = NotificationPermissionStatus.REQUIRED,
    val latestReceipt: FcmReceipt? = null,
    val evidenceCorrupt: Boolean = false,
)

class SensitiveToken internal constructor(internal val value: String) {
    val length: Int get() = value.length
    override fun toString(): String = "SensitiveToken(<redacted>)"
}
