package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.autostart.validRuntimeFingerprint
import io.github.originosfcmguardian.autostart.RecoveryTargetQualifications
import io.github.originosfcmguardian.autostart.isRecoveryTargetPackage
import io.github.originosfcmguardian.autostart.validTargetUidMembers
import io.github.originosfcmguardian.autostart.qualifiedRecoveryTargetUid
import io.github.originosfcmguardian.autostart.TargetSigningEvidence
import io.github.originosfcmguardian.autostart.targetSigningContinues
import io.github.originosfcmguardian.autostart.hasVerifiedPackageUid as verifiedRecoveryPackageUid
import io.github.originosfcmguardian.core.isSafeAndroidPackageName
import java.security.MessageDigest

internal const val STOPPED_TARGET_PACKAGE = "org.telegram.messenger"
internal const val MAX_CURRENT_SIGNERS = 16

internal enum class StoppedObservationUnknown {
    INVALID_REQUEST, SHELL_UNAVAILABLE, UNSUPPORTED_BUILD, WRONG_USER,
    CAPABILITY_UNAVAILABLE, IDENTITY_UNKNOWN, IDENTITY_CHANGED, UID_OWNERSHIP_UNKNOWN,
    TIMED_OUT, CANCELLED, BUSY, BRIDGE_UNAVAILABLE, MALFORMED_RESPONSE, INELIGIBLE_TARGET, EXPECTED_IDENTITY_CHANGED,
}

/** Read-only evidence; Known does not grant permission to execute a recovery. */
internal sealed interface StoppedTargetObservation {
    val requestId: Long

    data class Known(
        override val requestId: Long,
        val identity: SelectedTargetIdentity,
        val stopped: Boolean,
        val exclusiveUidOwner: Boolean,
        val observedAtMillis: Long,
        val fingerprint: String = VERIFIED_AUTOSTART_FINGERPRINT,
        val currentUser: Int = 0,
        val serviceUid: Int = 2000,
        val qualifiedRecipient: Boolean = false,
    ) : StoppedTargetObservation {
        /** Qualification is trusted only from the fresh target read, never supplied by the UI. */
        val hasVerifiedPackageUid: Boolean get() = verifiedRecoveryPackageUid(exclusiveUidOwner, qualifiedRecipient)

        init {
            require(requestId > 0 && observedAtMillis > 0)
            require(identity.packageName == STOPPED_TARGET_PACKAGE || qualifiedRecipient)
            require(!qualifiedRecipient || isRecoveryTargetPackage(identity.packageName))
            require(identity.signerSha256.size <= MAX_CURRENT_SIGNERS)
            require(validRuntimeFingerprint(fingerprint) && currentUser == 0 && serviceUid == 2000)
        }
    }

    /** Unknown deliberately contains no partial/stale identity or stopped value. */
    data class Unknown(
        override val requestId: Long,
        val reason: StoppedObservationUnknown,
    ) : StoppedTargetObservation
}

internal data class StoppedProbeEnvironment(val fingerprint: String?, val currentUser: Int?, val serviceUid: Int?)

/** Values originate only in a fresh system PackageInfo response, never in client arguments. */
internal data class StoppedPackageFields(
    val packageName: String?,
    val applicationPackageName: String?,
    val uid: Int?,
    val firstInstallTime: Long?,
    val currentSignerCertificates: List<ByteArray>?,
    val stopped: Boolean?,
    val qualifications: RecoveryTargetQualifications? = null,
    val signing: TargetSigningEvidence? = null,
)

internal interface StoppedIdentityReadPort {
    fun environment(): StoppedProbeEnvironment
    fun telegramPackage(): StoppedPackageFields?
    fun targetPackage(packageName: String): StoppedPackageFields? =
        if (packageName == STOPPED_TARGET_PACKAGE) telegramPackage() else null
    fun packagesForUid(uid: Int): List<String>?
}

internal fun validatedStoppedPackage(fields: StoppedPackageFields?, packageName: String = STOPPED_TARGET_PACKAGE): Pair<SelectedTargetIdentity, Boolean>? =
    runCatching {
        requireNotNull(fields)
        require(isSafeAndroidPackageName(packageName))
        require(fields.packageName == packageName && fields.applicationPackageName == packageName)
        val certificates = requireNotNull(fields.currentSignerCertificates)
        require(certificates.size in 1..MAX_CURRENT_SIGNERS)
        require(certificates.all { it.isNotEmpty() && it.size <= 64 * 1024 })
        val signers = certificates.map { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.sorted()
        require(signers.distinct().size == signers.size)
        SelectedTargetIdentity(
            packageName, requireNotNull(fields.uid), requireNotNull(fields.firstInstallTime), signers,
        ) to requireNotNull(fields.stopped)
    }.getOrNull()

/** Brackets UID ownership with full installation identity reads; does not claim atomicity. */
internal fun observeStoppedTelegram(
    requestId: Long,
    port: StoppedIdentityReadPort,
    interruption: () -> StoppedObservationUnknown? = { null },
    nowMillis: () -> Long = System::currentTimeMillis,
): StoppedTargetObservation {
    fun unknown(reason: StoppedObservationUnknown) = StoppedTargetObservation.Unknown(requestId, reason)
    var observedEnvironment: StoppedProbeEnvironment? = null
    fun guard(): StoppedObservationUnknown? {
        interruption()?.let { return it }
        val environment = port.environment()
        return when {
            environment.serviceUid != 2000 -> StoppedObservationUnknown.SHELL_UNAVAILABLE
            !validRuntimeFingerprint(environment.fingerprint) -> StoppedObservationUnknown.UNSUPPORTED_BUILD
            environment.currentUser != 0 -> StoppedObservationUnknown.WRONG_USER
            observedEnvironment != null && environment != observedEnvironment -> StoppedObservationUnknown.IDENTITY_CHANGED
            else -> { observedEnvironment = environment; interruption() }
        }
    }
    if (requestId <= 0) return unknown(StoppedObservationUnknown.INVALID_REQUEST)
    return try {
        guard()?.let { return unknown(it) }
        val before = validatedStoppedPackage(port.telegramPackage())
            ?: return unknown(StoppedObservationUnknown.IDENTITY_UNKNOWN)
        interruption()?.let { return unknown(it) }
        val owners = port.packagesForUid(before.first.uid)
        interruption()?.let { return unknown(it) }
        val after = validatedStoppedPackage(port.telegramPackage())
            ?: return unknown(StoppedObservationUnknown.IDENTITY_UNKNOWN)
        guard()?.let { return unknown(it) }
        if (before.first != after.first) return unknown(StoppedObservationUnknown.IDENTITY_CHANGED)
        if (owners == null || owners.size !in 1..64 || owners.distinct().size != owners.size ||
            owners.any { !isSafeAndroidPackageName(it) } || STOPPED_TARGET_PACKAGE !in owners
        ) return unknown(StoppedObservationUnknown.UID_OWNERSHIP_UNKNOWN)
        val timestamp = nowMillis()
        interruption()?.let { return unknown(it) }
        StoppedTargetObservation.Known(requestId, after.first, after.second, owners.size == 1, timestamp,
            fingerprint = requireNotNull(observedEnvironment?.fingerprint))
    } catch (_: Throwable) {
        unknown(interruption() ?: StoppedObservationUnknown.CAPABILITY_UNAVAILABLE)
    }
}

/** Dedicated recipient read: no caller supplied qualification can become trusted evidence. */
internal fun observeStoppedTarget(requestId: Long, expected: SelectedTargetIdentity, port: StoppedIdentityReadPort,
    interruption: () -> StoppedObservationUnknown? = { null }, nowMillis: () -> Long = System::currentTimeMillis,
): StoppedTargetObservation {
    fun unknown(reason: StoppedObservationUnknown) = StoppedTargetObservation.Unknown(requestId, reason)
    var observedEnvironment: StoppedProbeEnvironment? = null
    fun guard(): StoppedObservationUnknown? {
        interruption()?.let { return it }
        val env = port.environment()
        return when {
            env.serviceUid != 2000 -> StoppedObservationUnknown.SHELL_UNAVAILABLE
            !validRuntimeFingerprint(env.fingerprint) -> StoppedObservationUnknown.UNSUPPORTED_BUILD
            env.currentUser != 0 -> StoppedObservationUnknown.WRONG_USER
            observedEnvironment != null && env != observedEnvironment -> StoppedObservationUnknown.IDENTITY_CHANGED
            else -> { observedEnvironment = env; interruption() }
        }
    }
    if (requestId <= 0) return unknown(StoppedObservationUnknown.INVALID_REQUEST)
    if (!isRecoveryTargetPackage(expected.packageName)) return unknown(StoppedObservationUnknown.INELIGIBLE_TARGET)
    return try {
        guard()?.let { return unknown(it) }
        val beforeFields = port.targetPackage(expected.packageName)
        val before = validatedStoppedPackage(beforeFields, expected.packageName)
            ?: return unknown(StoppedObservationUnknown.IDENTITY_UNKNOWN)
        if (beforeFields?.qualifications?.eligible != true) return unknown(StoppedObservationUnknown.INELIGIBLE_TARGET)
        interruption()?.let { return unknown(it) }
        val owners = port.packagesForUid(before.first.uid)
        interruption()?.let { return unknown(it) }
        val afterFields = port.targetPackage(expected.packageName)
        val after = validatedStoppedPackage(afterFields, expected.packageName)
            ?: return unknown(StoppedObservationUnknown.IDENTITY_UNKNOWN)
        guard()?.let { return unknown(it) }
        if (before.first != after.first) return unknown(StoppedObservationUnknown.IDENTITY_CHANGED)
        if (afterFields?.qualifications?.eligible != true) return unknown(StoppedObservationUnknown.INELIGIBLE_TARGET)
        if (!validTargetUidMembers(expected.packageName, owners)) return unknown(StoppedObservationUnknown.UID_OWNERSHIP_UNKNOWN)
        if (!qualifiedRecoveryTargetUid(expected.packageName, requireNotNull(beforeFields?.qualifications), owners) ||
            !qualifiedRecoveryTargetUid(expected.packageName, requireNotNull(afterFields?.qualifications), owners)) {
            return unknown(StoppedObservationUnknown.INELIGIBLE_TARGET)
        }
        if (!targetSigningContinues(expected, before.first, beforeFields?.signing) ||
            !targetSigningContinues(expected, after.first, afterFields?.signing)) {
            return unknown(StoppedObservationUnknown.EXPECTED_IDENTITY_CHANGED)
        }
        val timestamp = nowMillis()
        interruption()?.let { return unknown(it) }
        StoppedTargetObservation.Known(requestId, expected, after.second, owners?.size == 1, timestamp,
            fingerprint = requireNotNull(observedEnvironment?.fingerprint), qualifiedRecipient = true)
    } catch (_: Throwable) { unknown(interruption() ?: StoppedObservationUnknown.CAPABILITY_UNAVAILABLE) }
}

/** Small versioned wire schema, separate from SafeCommand and its output contract. */
internal object StoppedObservationCodec {
    const val BUNDLE_KEY = "telegram_stopped_observation_v1"
    private const val MAX_PAYLOAD_CHARS = 4096

    fun encode(observation: StoppedTargetObservation): String = when (observation) {
        is StoppedTargetObservation.Unknown -> "1|${observation.requestId}|U|${observation.reason.name}"
        is StoppedTargetObservation.Known -> listOf(
            if (observation.qualifiedRecipient) "2" else "1", observation.requestId, "K", observation.observedAtMillis, observation.fingerprint,
            observation.currentUser, observation.serviceUid, observation.identity.packageName,
            observation.identity.uid, observation.identity.firstInstallTime,
            observation.identity.signerSha256.joinToString(","),
            if (observation.stopped) "1" else "0", if (observation.exclusiveUidOwner) "1" else "0",
        ).let { if (observation.qualifiedRecipient) it + "1" else it }.joinToString("|")
    }

    fun decode(raw: String?, expectedRequestId: Long, expectedIdentity: SelectedTargetIdentity? = null): StoppedTargetObservation {
        fun malformed() = StoppedTargetObservation.Unknown(expectedRequestId, StoppedObservationUnknown.MALFORMED_RESPONSE)
        if (expectedRequestId <= 0 || raw == null || raw.length > MAX_PAYLOAD_CHARS) return malformed()
        return runCatching {
            val fields = raw.split('|')
            require(fields.size >= 4 && fields[0] in setOf("1", "2") && fields[1] == expectedRequestId.toString())
            val recipient = fields[0] == "2"
            if (fields[2] == "U") {
                require(fields.size == 4)
                StoppedTargetObservation.Unknown(expectedRequestId, StoppedObservationUnknown.valueOf(fields[3]))
            } else {
                require(fields.size == (if (recipient) 14 else 13) && fields[2] == "K")
                require(expectedIdentity == null || recipient)
                require(!recipient || (expectedIdentity != null && fields[13] == "1"))
                fun flag(value: String): Boolean = when (value) { "1" -> true; "0" -> false; else -> error("Invalid flag") }
                val identity = SelectedTargetIdentity(fields[7], fields[8].toInt(), fields[9].toLong(), fields[10].split(','))
                require(if (recipient) identity == expectedIdentity else identity.packageName == STOPPED_TARGET_PACKAGE)
                StoppedTargetObservation.Known(
                    expectedRequestId,
                    identity,
                    flag(fields[11]), flag(fields[12]), fields[3].toLong(), fields[4], fields[5].toInt(), fields[6].toInt(), recipient,
                )
            }
        }.getOrElse { malformed() }
    }
}
