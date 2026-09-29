package io.github.originosfcmguardian.autostart

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import io.github.originosfcmguardian.core.GuardianAutostartRead
import io.github.originosfcmguardian.core.isSafeAndroidPackageName
import io.github.originosfcmguardian.core.isUserZeroTargetAppUid
import java.security.MessageDigest

const val MAX_SELECTED_TARGETS = 10

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
        require(signerSha256.all { it.matches(Regex("[0-9a-f]{64}")) })
    }
}

data class LauncherAppCandidate(
    val packageName: String,
    val label: String,
    val selected: Boolean,
)

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
)

data class TargetSelectionUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val candidates: List<LauncherAppCandidate> = emptyList(),
    val message: String? = null,
)

internal object TargetSelectionCodec {
    fun encode(values: List<SelectedTargetIdentity>): String {
        require(values.size <= MAX_SELECTED_TARGETS)
        require(values.map { it.packageName }.distinct().size == values.size)
        return values.sortedBy { it.packageName }.joinToString("\n") { value ->
            listOf(
                value.packageName,
                value.uid.toString(),
                value.firstInstallTime.toString(),
                value.signerSha256.joinToString(","),
            ).joinToString("|")
        }
    }

    fun decode(raw: String): TargetSelectionDecodeResult {
        if (raw.isBlank()) return TargetSelectionDecodeResult.Valid(emptyList())
        val lines = raw.lineSequence().toList()
        if (lines.size !in 1..MAX_SELECTED_TARGETS) return TargetSelectionDecodeResult.Corrupt
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
        if (current.size >= MAX_SELECTED_TARGETS) return false
        return preferences.write(TargetSelectionCodec.encode(current + identity))
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

class TargetPackageProbe(private val context: Context) {
    @Suppress("DEPRECATION")
    fun launcherCandidates(selectedPackages: Set<String>): List<LauncherAppCandidate> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val uid = info.activityInfo?.applicationInfo?.uid ?: return@mapNotNull null
                if (
                    packageName == context.packageName ||
                    !isSafeAndroidPackageName(packageName) ||
                    !isUserZeroTargetAppUid(uid)
                ) {
                    return@mapNotNull null
                }
                val label = info.loadLabel(context.packageManager).toString().trim()
                    .ifEmpty { packageName }
                    .take(80)
                LauncherAppCandidate(packageName, label, packageName in selectedPackages)
            }
            .distinctBy(LauncherAppCandidate::packageName)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    @Suppress("DEPRECATION")
    fun identity(packageName: String): SelectedTargetIdentity? {
        if (!isSafeAndroidPackageName(packageName)) return null
        return runCatching {
            val info = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val uid = requireNotNull(info.applicationInfo).uid
            if (!isUserZeroTargetAppUid(uid) || info.firstInstallTime <= 0) return null
            val signingInfo = info.signingInfo ?: return null
            val signers = signingInfo.apkContentsSigners.orEmpty()
            val digests = signers.map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }.distinct().sorted()
            SelectedTargetIdentity(packageName, uid, info.firstInstallTime, digests)
        }.getOrNull()
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
