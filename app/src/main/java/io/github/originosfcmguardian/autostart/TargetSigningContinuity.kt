package io.github.originosfcmguardian.autostart

import java.security.MessageDigest

internal const val MAX_TARGET_CURRENT_SIGNERS = 16
internal const val MAX_TARGET_SIGNING_HISTORY = 32
internal const val MAX_TARGET_CERTIFICATE_BYTES = 64 * 1024
private val targetHistoryDigestPattern = Regex("[0-9a-f]{64}")

/** Evidence belongs to the same fresh PackageInfo as the raw current identity. Never persisted. */
internal data class TargetSigningEvidence(
    val hasMultipleSigners: Boolean?,
    val historySha256: List<String>?,
)

internal fun targetCertificateDigests(certificates: List<ByteArray>?, maximum: Int): List<String>? =
    runCatching {
        requireNotNull(certificates)
        require(certificates.size in 1..maximum)
        require(certificates.all { it.isNotEmpty() && it.size <= MAX_TARGET_CERTIFICATE_BYTES })
        certificates.map { lowerHexDigest(MessageDigest.getInstance("SHA-256").digest(it)) }
            .also { require(it.distinct().size == it.size) }.sorted()
    }.getOrNull()

/** Exact installations remain exact; only verified single-signer lineage can retain a baseline. */
internal fun targetSigningContinues(
    expected: SelectedTargetIdentity,
    current: SelectedTargetIdentity,
    signing: TargetSigningEvidence?,
): Boolean {
    if (expected == current) return true
    if (!isRecoveryTargetPackage(expected.packageName) ||
        expected.packageName != current.packageName || expected.uid != current.uid ||
        expected.firstInstallTime != current.firstInstallTime || expected.signerSha256.size != 1 ||
        current.signerSha256.size != 1 || signing?.hasMultipleSigners != false) return false
    val history = signing.historySha256 ?: return false
    if (history.size !in 1..MAX_TARGET_SIGNING_HISTORY || history.distinct().size != history.size ||
        history.any { !targetHistoryDigestPattern.matches(it) }) return false
    return expected.signerSha256.single() in history && current.signerSha256.single() in history
}

/** Used only for a frozen existing selection/authority, never when selecting a new app. */
internal fun TargetPackageSnapshot.identityFor(expected: SelectedTargetIdentity): SelectedTargetIdentity =
    if (targetSigningContinues(expected, identity, signing)) expected else identity

/** Conflicting stored baselines must not silently select one identity for an inventory row. */
internal fun uniqueTargetBaselines(identities: List<SelectedTargetIdentity>): Map<String, SelectedTargetIdentity> =
    identities.groupBy { it.packageName }.mapNotNull { (name, values) ->
        values.distinct().singleOrNull()?.let { name to it }
    }.toMap()
