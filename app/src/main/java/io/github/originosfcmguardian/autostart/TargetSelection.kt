package io.github.originosfcmguardian.autostart

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.originosfcmguardian.core.GuardianAutostartRead
import io.github.originosfcmguardian.core.isSafeAndroidPackageName
import io.github.originosfcmguardian.core.isUserZeroTargetAppUid

internal const val MAX_TARGET_SELECTION_BYTES = 256 * 1024
private val signerSha256Pattern = Regex("[0-9a-f]{64}")
private const val LOWER_HEX_DIGITS = "0123456789abcdef"

internal fun lowerHexDigest(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = LOWER_HEX_DIGITS[value ushr 4]
        chars[index * 2 + 1] = LOWER_HEX_DIGITS[value and 15]
    }
    return String(chars)
}

data class SelectedTargetIdentity(
    val packageName: String,
    val uid: Int,
    val firstInstallTime: Long,
    val signerSha256: List<String>,
) {
    init {
        require(isSafeAndroidPackageName(packageName))
        require(isUserZeroTargetAppUid(uid))
        require(firstInstallTime > 0)
        require(signerSha256.isNotEmpty())
        require(signerSha256 == signerSha256.distinct().sorted())
        require(signerSha256.all(signerSha256Pattern::matches))
    }
}

data class LauncherAppCandidate(
    val packageName: String,
    val label: String,
    val selected: Boolean,
)

data class UserInstalledAppInventory(
    val candidates: List<LauncherAppCandidate>,
    /** Current PackageManager evidence, used only to hide preinstalled rows from the app list. */
    val hiddenPreinstalledPackages: Set<String>,
)

internal fun isPreinstalledApplication(flags: Int): Boolean =
    flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

internal fun isUserInstalledApplication(flags: Int): Boolean =
    flags and ApplicationInfo.FLAG_INSTALLED != 0 && !isPreinstalledApplication(flags)

/** Bounded current UID membership evidence; shared ownership never implies target qualification. */
internal fun validTargetUidMembers(packageName: String, owners: List<String>?): Boolean =
    owners != null && owners.size in 1..64 && owners.distinct().size == owners.size &&
        owners.all(::isSafeAndroidPackageName) && packageName in owners

enum class TargetIdentityStatus {
    MATCH,
    MISSING,
    CHANGED,
}

enum class TargetAutostartStatus {
    NOT_CHECKED,
    CHECKING,
    ALLOWED,
    NEEDS_USER_ACTION,
    UNKNOWN,
    IDENTITY_CHANGED,
    UNAVAILABLE,
}

data class SelectedTargetUiState(
    val identity: SelectedTargetIdentity,
    val label: String,
    val status: TargetAutostartStatus = TargetAutostartStatus.NOT_CHECKED,
    val evidence: GuardianAutostartRead? = null,
    val detail: String = "尚未检查自启动权限",
    val stoppedSnapshot: TargetStoppedSnapshot = TargetStoppedSnapshot(),
)

data class TargetSelectionUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val candidates: List<LauncherAppCandidate> = emptyList(),
    val message: String? = null,
)

internal object TargetSelectionCodec {
    fun encode(values: List<SelectedTargetIdentity>): String {
        require(values.map { it.packageName }.distinct().size == values.size)
        val encoded = values.sortedBy { it.packageName }.joinToString("\n") { value ->
            listOf(
                value.packageName,
                value.uid.toString(),
                value.firstInstallTime.toString(),
                value.signerSha256.joinToString(","),
            ).joinToString("|")
        }
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_TARGET_SELECTION_BYTES) {
            "Target selection exceeds storage safety bound"
        }
        return encoded
    }

    fun decode(raw: String): TargetSelectionDecodeResult {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_TARGET_SELECTION_BYTES) {
            return TargetSelectionDecodeResult.Corrupt
        }
        if (raw.isBlank()) return TargetSelectionDecodeResult.Valid(emptyList())
        val lines = raw.lineSequence().toList()
        val decoded = runCatching {
            lines.map { line ->
                val fields = line.split('|')
                require(fields.size == 4)
                SelectedTargetIdentity(
                    packageName = fields[0],
                    uid = fields[1].toInt(),
                    firstInstallTime = fields[2].toLong(),
                    signerSha256 = fields[3].split(',').sorted(),
                )
            }
        }.getOrNull() ?: return TargetSelectionDecodeResult.Corrupt
        if (decoded.map(SelectedTargetIdentity::packageName).distinct().size != decoded.size) {
            return TargetSelectionDecodeResult.Corrupt
        }
        return TargetSelectionDecodeResult.Valid(decoded)
    }
}

internal sealed interface TargetSelectionDecodeResult {
    data class Valid(val values: List<SelectedTargetIdentity>) : TargetSelectionDecodeResult
    data object Corrupt : TargetSelectionDecodeResult
}

class CorruptTargetSelectionException : IllegalStateException(
    "Stored target selection is corrupt; explicit user recovery is required",
)

internal interface TargetSelectionPreferences {
    fun read(): String
    fun write(value: String): Boolean
    fun clear(): Boolean
    fun registerListener(listener: () -> Unit): AutoCloseable = AutoCloseable {}
}

private class AndroidTargetSelectionPreferences(context: Context) : TargetSelectionPreferences {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String = preferences.getString(KEY_TARGETS, "").orEmpty()
    override fun write(value: String): Boolean = preferences.edit().putString(KEY_TARGETS, value).commit()
    override fun clear(): Boolean = preferences.edit().remove(KEY_TARGETS).commit()

    override fun registerListener(listener: () -> Unit): AutoCloseable {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TARGETS) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        return AutoCloseable {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "autostart_target_selection_v1"
        const val KEY_TARGETS = "selected_target_identities"
    }
}

class TargetSelectionStore internal constructor(
    private val preferences: TargetSelectionPreferences,
) {
    constructor(context: Context) : this(AndroidTargetSelectionPreferences(context))

    @Synchronized
    fun load(): List<SelectedTargetIdentity> = when (
        val decoded = TargetSelectionCodec.decode(preferences.read())
    ) {
        is TargetSelectionDecodeResult.Valid -> decoded.values
        TargetSelectionDecodeResult.Corrupt -> throw CorruptTargetSelectionException()
    }

    @Synchronized
    fun replace(identity: SelectedTargetIdentity): Boolean {
        val current = load().filterNot { it.packageName == identity.packageName }
        val encoded = try {
            TargetSelectionCodec.encode(current + identity)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return preferences.write(encoded)
    }

    @Synchronized
    fun remove(packageName: String): Boolean {
        val current = load()
        val remaining = current.filterNot { it.packageName == packageName }
        if (remaining.size == current.size) return true
        return preferences.write(TargetSelectionCodec.encode(remaining))
    }

    /** Explicit UI recovery only; normal load/replace/remove never overwrite corrupt bytes. */
    @Synchronized
    fun clearCorruptSelection(): Boolean {
        val raw = preferences.read()
        if (TargetSelectionCodec.decode(raw) != TargetSelectionDecodeResult.Corrupt) return false
        return preferences.clear()
    }

    /** Observes committed selection changes so callback-side allowlists can invalidate old work. */
    fun registerListener(listener: () -> Unit): AutoCloseable = preferences.registerListener(listener)
}

internal data class RecoveryCandidatePackageEvidence(
    val identity: SelectedTargetIdentity?,
    val qualifications: RecoveryTargetQualifications,
    val signing: TargetSigningEvidence? = null,
)

/** One fresh signed package read supplies both qualification and identity; no retained cache. */
internal fun readRecoveryCandidate(
    expected: SelectedTargetIdentity,
    readPackage: () -> RecoveryCandidatePackageEvidence?,
    readUidMembers: (Int) -> List<String>?,
): Boolean = runCatching {
    if (!isRecoveryTargetPackage(expected.packageName)) return@runCatching false
    val evidence = readPackage() ?: return@runCatching false
    evidence.qualifications.eligible && evidence.identity?.let {
        targetSigningContinues(expected, it, evidence.signing)
    } == true &&
        validTargetUidMembers(expected.packageName, readUidMembers(expected.uid))
}.getOrDefault(false)

/** Same package/UID/install/signer validation for qualification and stopped-flag observations. */
internal fun targetPackageIdentity(
    requestedPackage: String, packageName: String?, applicationPackageName: String?, uid: Int,
    firstInstallTime: Long, certificates: List<ByteArray>?,
): SelectedTargetIdentity? = runCatching {
    require(isSafeAndroidPackageName(requestedPackage) && packageName == requestedPackage &&
        applicationPackageName == requestedPackage)
    require(isUserZeroTargetAppUid(uid) && firstInstallTime > 0)
    val digests = requireNotNull(targetCertificateDigests(certificates, MAX_TARGET_CURRENT_SIGNERS))
    SelectedTargetIdentity(requestedPackage, uid, firstInstallTime, digests)
}.getOrNull()

class TargetPackageProbe(private val context: Context) {
    @Suppress("DEPRECATION")
    fun isRecoveryCandidate(expected: SelectedTargetIdentity): Boolean = readRecoveryCandidate(expected, {
        val info = context.packageManager.getPackageInfo(expected.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val app = requireNotNull(info.applicationInfo)
        val qualifications = RecoveryTargetQualifications(
            app.flags and ApplicationInfo.FLAG_INSTALLED != 0, app.enabled,
            app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            app.flags and ApplicationInfo.FLAG_PERSISTENT != 0,
        )
        val snapshot = if (qualifications.eligible) packageSnapshot(expected.packageName, info) else null
        RecoveryCandidatePackageEvidence(snapshot?.identity, qualifications, snapshot?.signing)
    }, { uid -> context.packageManager.getPackagesForUid(uid)?.toList() })

    /** Kept for existing selection callers; candidates now include apps without a launcher. */
    fun launcherCandidates(selectedPackages: Set<String>): List<LauncherAppCandidate> =
        userInstalledApps(selectedPackages).candidates

    @Suppress("DEPRECATION")
    fun userInstalledApps(selectedPackages: Set<String>): UserInstalledAppInventory {
        val applications = context.packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        val hiddenPreinstalledPackages = applications.filter { isPreinstalledApplication(it.flags) }
            .mapTo(mutableSetOf()) { it.packageName }
        val candidates = applications
            .asSequence()
            .mapNotNull { app ->
                val packageName = app.packageName
                val uid = app.uid
                if (
                    !isUserInstalledApplication(app.flags) ||
                    packageName == context.packageName ||
                    !isSafeAndroidPackageName(packageName) ||
                    !isUserZeroTargetAppUid(uid)
                ) {
                    return@mapNotNull null
                }
                val label = context.packageManager.getApplicationLabel(app).toString().trim()
                    .ifEmpty { packageName }
                    .take(80)
                LauncherAppCandidate(packageName, label, packageName in selectedPackages)
            }
            .distinctBy(LauncherAppCandidate::packageName)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
        return UserInstalledAppInventory(candidates, hiddenPreinstalledPackages)
    }

    @Suppress("DEPRECATION")
    fun identity(packageName: String): SelectedTargetIdentity? {
        return packageSnapshot(packageName)?.identity
    }

    fun identity(expected: SelectedTargetIdentity): SelectedTargetIdentity? =
        packageSnapshot(expected.packageName)?.identityFor(expected)

    fun stoppedSnapshot(expected: SelectedTargetIdentity): TargetStoppedSnapshot =
        readTargetStoppedSnapshot(expected, { packageSnapshot(expected.packageName) })

    @Suppress("DEPRECATION")
    internal fun packageSnapshot(packageName: String): TargetPackageSnapshot? {
        if (!isSafeAndroidPackageName(packageName)) return null
        return runCatching {
            val info = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            packageSnapshot(packageName, info)
        }.getOrNull()
    }

    private fun packageSnapshot(packageName: String, info: PackageInfo): TargetPackageSnapshot? {
        val app = info.applicationInfo ?: return null
        if (info.packageName != packageName || app.packageName != packageName ||
            !isUserZeroTargetAppUid(app.uid) || info.firstInstallTime <= 0) return null
        val signing = info.signingInfo ?: return null
        val currentSigners = signing.apkContentsSigners ?: return null
        if (currentSigners.size !in 1..MAX_TARGET_CURRENT_SIGNERS) return null
        val identity = targetPackageIdentity(packageName, info.packageName, app.packageName, app.uid,
            info.firstInstallTime, currentSigners.map { it.toByteArray() }) ?: return null
        val multiple = signing.hasMultipleSigners()
        val history = if (!multiple) signing.signingCertificateHistory?.takeIf {
            it.size in 1..MAX_TARGET_SIGNING_HISTORY
        }?.map { it.toByteArray() } else null
        return TargetPackageSnapshot(identity, app.flags and ApplicationInfo.FLAG_STOPPED != 0,
            TargetSigningEvidence(multiple, targetCertificateDigests(history, MAX_TARGET_SIGNING_HISTORY)))
    }

    @Suppress("DEPRECATION")
    fun label(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString().trim().ifEmpty { packageName }.take(80)
    }.getOrDefault(packageName)
}

internal fun identityStatus(
    stored: SelectedTargetIdentity,
    current: SelectedTargetIdentity?,
): TargetIdentityStatus = when {
    current == null -> TargetIdentityStatus.MISSING
    current == stored -> TargetIdentityStatus.MATCH
    else -> TargetIdentityStatus.CHANGED
}

internal class TargetAutostartReturnTracker {
    private enum class Phase { IDLE, AWAITING_DEPARTURE, AWAITING_RETURN }

    private var phase = Phase.IDLE
    private var target: SelectedTargetIdentity? = null

    @Synchronized
    fun markSuccessfulLaunch(identity: SelectedTargetIdentity) {
        target = identity
        phase = Phase.AWAITING_DEPARTURE
    }

    @Synchronized
    fun markDeparture() {
        if (phase == Phase.AWAITING_DEPARTURE) phase = Phase.AWAITING_RETURN
    }

    @Synchronized
    fun consumeReturn(): SelectedTargetIdentity? {
        if (phase != Phase.AWAITING_RETURN) return null
        val captured = target
        target = null
        phase = Phase.IDLE
        return captured
    }

    @Synchronized
    fun clear() {
        target = null
        phase = Phase.IDLE
    }
}
