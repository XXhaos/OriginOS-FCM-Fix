package io.github.originosfcmguardian.update

import java.net.URI

internal const val UPDATE_REPOSITORY = "XXhaos/OriginOS-FCM-Fix"
internal const val UPDATE_PACKAGE = "io.github.originosfcmguardian"
internal const val UPDATE_LATEST_URL = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"
internal const val MAX_UPDATE_APK_BYTES = 64L * 1024 * 1024
// Explicit foreground downloads may be slow; per-read timeout and size/cancellation limits still apply.
internal const val UPDATE_DOWNLOAD_TIMEOUT_MILLIS = 10 * 60_000L

/** Only stable three-part release tags participate in the normal update channel. */
internal data class UpdateVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int =
        compareValuesBy(this, other, UpdateVersion::major, UpdateVersion::minor, UpdateVersion::patch)
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): UpdateVersion? {
            if (text.length > 36) return null
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(text) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return UpdateVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class UpdateAsset(val name: String, val url: String, val bytes: Long, val digest: String?)
internal data class UpdateRelease(val tag: String, val draft: Boolean, val prerelease: Boolean,
    val assets: List<UpdateAsset>)
internal data class UpdateManifest(val file: String, val version: String, val code: Long,
    val applicationId: String, val bytes: Long, val sha256: String, val debuggable: Boolean)
internal data class AvailableAppUpdate(val version: UpdateVersion, val code: Long, val asset: UpdateAsset,
    val sha256: String)

internal fun releaseAsset(release: UpdateRelease, name: String): UpdateAsset {
    require(!release.draft && !release.prerelease) { "Unpublished release" }
    require(UpdateVersion.parse(release.tag) != null && release.tag.startsWith("v")) { "Invalid release tag" }
    val asset = release.assets.singleOrNull { it.name == name } ?: error("Missing release asset")
    val expected = "https://github.com/$UPDATE_REPOSITORY/releases/download/${release.tag}/$name"
    require(asset.url == expected && asset.bytes > 0) { "Invalid release asset" }
    asset.digest?.let { require(it.matches(Regex("sha256:[a-fA-F0-9]{64}"))) { "Invalid digest" } }
    return asset
}

internal fun qualifyAppUpdate(release: UpdateRelease, manifest: UpdateManifest,
    installedVersion: String, installedCode: Long): AvailableAppUpdate {
    val version = requireNotNull(UpdateVersion.parse(release.tag))
    require(version > requireNotNull(UpdateVersion.parse(installedVersion))) { "Not a newer release" }
    val asset = releaseAsset(release, "OriginOS-FCM-Guardian-$version.apk")
    require(asset.bytes <= MAX_UPDATE_APK_BYTES && manifest.bytes == asset.bytes) { "Invalid APK size" }
    require(manifest.file == asset.name && manifest.version == version.toString() && manifest.code > installedCode &&
        manifest.applicationId == UPDATE_PACKAGE && !manifest.debuggable) { "Invalid update identity" }
    require(manifest.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid APK checksum" }
    asset.digest?.let { require(it.substringAfter(":").equals(manifest.sha256, ignoreCase = true)) { "Digest mismatch" } }
    return AvailableAppUpdate(version, manifest.code, asset, manifest.sha256.lowercase())
}

/** Redirects may reach GitHub's release CDN, never arbitrary hosts, plaintext or user-info URLs. */
internal fun trustedUpdateUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.rawUserInfo == null && uri.fragment == null && uri.port == -1 &&
        uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com")
}.getOrDefault(false)

internal data class UpdateApkIdentity(val packageName: String, val versionName: String, val code: Long,
    val signers: Set<String>, val signingHistory: Set<String>, val debuggable: Boolean, val minSdk: Int)

internal fun validUpdateApk(installed: UpdateApkIdentity, candidate: UpdateApkIdentity,
    update: AvailableAppUpdate, sdk: Int): Boolean =
    installed.packageName == UPDATE_PACKAGE && candidate.packageName == installed.packageName &&
        candidate.versionName == update.version.toString() && candidate.code == update.code &&
        candidate.code > installed.code && !candidate.debuggable && candidate.minSdk <= sdk &&
        installed.signers.size == 1 && candidate.signers.size == 1 &&
        (candidate.signers == installed.signers || candidate.signingHistory.containsAll(installed.signers))
