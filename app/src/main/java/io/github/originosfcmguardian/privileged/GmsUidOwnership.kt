package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.FcmRecoveryPolicy
import io.github.originosfcmguardian.core.isSafeAndroidPackageName

/** Shell-local evidence only. Never serialize UID members, certificate history or this snapshot. */
internal data class GmsUidOwnership(val packages: List<GmsPackageIdentity>)

/** Authority for a fixed GMS package/component, independent of a peer's socket/signing identity. */
internal data class GmsPackageOwnership(val gms: GmsPackageIdentity, val members: List<String>)

private fun canonicalGmsUidOwners(owners: List<String>?): List<String>? = owners?.takeIf {
    it.size in 1..64 && it.distinct().size == it.size && GMS_OBSERVATION_PACKAGE in it &&
        it.all(::isSafeAndroidPackageName)
}?.sorted()

internal fun qualifiedGmsPackageOwnership(gms: GmsPackageIdentity?, owners: List<String>?): GmsPackageOwnership? {
    val identity = validGmsPackageIdentity(gms) ?: return null
    if (gmsGoogleTrustAnchor(identity) != FcmRecoveryPolicy.SIGNER) return null
    return canonicalGmsUidOwners(owners)?.let { GmsPackageOwnership(identity, it) }
}

/** Fresh PM ownership of the exact signed target. Unknown membership is never an empty/exclusive UID. */
internal fun readGmsPackageOwnership(port: GmsConnectionReadPort, gms: GmsPackageIdentity?,
    interrupted: () -> Boolean = { false }): GmsPackageOwnership? {
    val identity = validGmsPackageIdentity(gms) ?: return null
    if (gmsGoogleTrustAnchor(identity) != FcmRecoveryPolicy.SIGNER || interrupted()) return null
    val ownership = qualifiedGmsPackageOwnership(identity, port.packagesForUid(identity.uid)) ?: return null
    if (interrupted() || canonicalGmsUidOwners(port.packagesForUid(identity.uid)) != ownership.members) return null
    if (interrupted() || port.gmsPackage() != identity) return null
    return ownership.takeUnless { interrupted() }
}

/** UID-wide socket evidence is qualified only when all actual members have Google signing proof.
 * This stricter attribution is separate from the exact signed package/component authority above.
 * A package name or Android's shared-user declaration alone never grants socket attribution.
 */
internal fun qualifiedGmsUidOwnership(gms: GmsPackageIdentity?, owners: List<String>?,
    packageIdentity: (String) -> GmsPackageIdentity?, interrupted: () -> Boolean = { false }): GmsUidOwnership? {
    val identity = validGmsPackageIdentity(gms) ?: return null
    val names = canonicalGmsUidOwners(owners) ?: return null
    val shared = names.size > 1
    val packages = names.map { name ->
        if (interrupted()) return null
        val member = if (name == GMS_OBSERVATION_PACKAGE) identity else packageIdentity(name) ?: return null
        if (validGmsUidPackageIdentity(member, name)?.uid != identity.uid ||
            shared && gmsGoogleTrustAnchor(member) != FcmRecoveryPolicy.SIGNER) return null
        member
    }
    return GmsUidOwnership(packages)
}

/** Membership is fenced around member reads, and callers compare raw snapshots around observations. */
internal fun readGmsUidOwnership(port: GmsConnectionReadPort, gms: GmsPackageIdentity?,
    interrupted: () -> Boolean = { false }): GmsUidOwnership? {
    val identity = validGmsPackageIdentity(gms) ?: return null
    if (interrupted()) return null
    val names = canonicalGmsUidOwners(port.packagesForUid(identity.uid)) ?: return null
    val ownership = qualifiedGmsUidOwnership(identity, names, port::packageIdentity, interrupted) ?: return null
    if (interrupted()) return null
    if (names.size > 1 && canonicalGmsUidOwners(port.packagesForUid(identity.uid)) != names) return null
    // Member Binder reads must not extend a stale target identity into the final dispatch guard.
    if (names.size > 1 && port.gmsPackage() != identity) return null
    return ownership.takeUnless { interrupted() }
}

internal fun validGmsUidPackageIdentity(value: GmsPackageIdentity?, packageName: String): GmsPackageIdentity? = value?.takeIf {
    isSafeAndroidPackageName(packageName) && it.packageName == packageName && it.applicationPackageName == packageName &&
        it.uid in 10_000..99_999 && it.versionCode >= 0 && it.firstInstallTime > 0 && it.lastUpdateTime > 0 &&
        it.signerSha256.matches(Regex("[a-f0-9]{64}"))
}
