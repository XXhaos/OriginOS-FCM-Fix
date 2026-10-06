package io.github.originosfcmguardian.protection

import android.content.Context
import android.content.SharedPreferences

class DailyProtectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(report: DailyProtectionReport) {
        val targets = DailyProtectionTargetsCodec.encode(report.targets)
        preferences.edit()
            .putLong(KEY_CHECKED_AT, report.checkedAtEpochMillis)
            .putString(KEY_REASON, report.reason.name)
            .putString(KEY_FINGERPRINT, report.fingerprint)
            .putString(KEY_STATUS, report.status.name)
            .putString(KEY_SUMMARY, report.summary.take(DailyProtectionReport.MAX_SUMMARY_CHARS))
            .putString(KEY_TARGETS, targets)
            .commit()
    }

    @Synchronized
    fun load(): DailyProtectionReport? {
        val checkedAt = preferences.getLong(KEY_CHECKED_AT, -1L)
        val reason = enumValueOrNull<ProtectionCheckReason>(preferences.getString(KEY_REASON, null))
        val status = enumValueOrNull<ProtectionCheckStatus>(preferences.getString(KEY_STATUS, null))
        val fingerprint = preferences.getString(KEY_FINGERPRINT, null)
        val summary = preferences.getString(KEY_SUMMARY, null)
        if (checkedAt < 0 || reason == null || status == null || fingerprint == null || summary == null) return null
        val targets = DailyProtectionTargetsCodec.decode(
            preferences.getString(KEY_TARGETS, "").orEmpty(),
        ) ?: return null
        return runCatching {
            DailyProtectionReport(checkedAt, reason, fingerprint, status, summary, targets)
        }.getOrNull()
    }

    /** Emits once per completed report commit. The caller must close the returned subscription. */
    fun registerReportListener(listener: () -> Unit): AutoCloseable {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CHECKED_AT) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        return object : AutoCloseable {
            override fun close() {
                preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            }
        }
    }

    @Synchronized
    fun invalidateForBuildChange(now: Long, fingerprint: String) {
        save(
            DailyProtectionReport(
                checkedAtEpochMillis = now,
                reason = ProtectionCheckReason.BUILD_CHANGED,
                fingerprint = fingerprint.take(512),
                status = ProtectionCheckStatus.BUILD_CHANGED,
                summary = "系统构建已变化；旧检测结论不可沿用，需重新验证当前构建语义",
            ),
        )
    }

    @Synchronized
    internal fun eventStamps(): ProtectionEventStamps = ProtectionEventStamps(
        fingerprint = preferences.getString(KEY_EVENT_FINGERPRINT, "").orEmpty(),
        guardianVersion = preferences.getLong(KEY_EVENT_GUARDIAN_VERSION, -1L),
        gmsVersion = preferences.getLong(KEY_EVENT_GMS_VERSION, -1L),
        shizukuAvailable = if (preferences.contains(KEY_SHIZUKU_AVAILABLE)) {
            preferences.getBoolean(KEY_SHIZUKU_AVAILABLE, false)
        } else {
            null
        },
    )

    @Synchronized
    internal fun saveEventStamps(stamps: ProtectionEventStamps) {
        val editor = preferences.edit()
            .putString(KEY_EVENT_FINGERPRINT, stamps.fingerprint)
            .putLong(KEY_EVENT_GUARDIAN_VERSION, stamps.guardianVersion)
            .putLong(KEY_EVENT_GMS_VERSION, stamps.gmsVersion)
        stamps.shizukuAvailable?.let { editor.putBoolean(KEY_SHIZUKU_AVAILABLE, it) }
        editor.commit()
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private companion object {
        const val PREFERENCES_NAME = "daily_protection_report_v1"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_REASON = "reason"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_STATUS = "status"
        const val KEY_SUMMARY = "summary"
        const val KEY_TARGETS = "targets"
        const val KEY_EVENT_FINGERPRINT = "event_fingerprint"
        const val KEY_EVENT_GUARDIAN_VERSION = "event_guardian_version"
        const val KEY_EVENT_GMS_VERSION = "event_gms_version"
        const val KEY_SHIZUKU_AVAILABLE = "event_shizuku_available"
    }
}

internal data class ProtectionEventStamps(
    val fingerprint: String,
    val guardianVersion: Long,
    val gmsVersion: Long,
    val shizukuAvailable: Boolean?,
)

internal object DailyProtectionTargetsCodec {
    const val MAX_BYTES = 256 * 1024

    fun encode(targets: List<TargetCheckResult>): String {
        require(targets.map(TargetCheckResult::packageName).distinct().size == targets.size) {
            "Daily protection target packages must be unique"
        }
        val encoded = targets.joinToString(",") { "${it.packageName}=${it.status.name}" }
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Daily protection targets exceed storage safety bound"
        }
        return encoded
    }

    fun decode(raw: String): List<TargetCheckResult>? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        if (raw.isBlank()) return emptyList()
        val decoded = raw.split(',').map { encoded ->
            val separator = encoded.lastIndexOf('=')
            if (separator <= 0) return null
            val status = runCatching {
                enumValueOf<TargetCheckStatus>(encoded.substring(separator + 1))
            }.getOrNull() ?: return null
            runCatching { TargetCheckResult(encoded.substring(0, separator), status) }.getOrNull()
                ?: return null
        }
        if (decoded.map(TargetCheckResult::packageName).distinct().size != decoded.size) return null
        return decoded
    }
}
