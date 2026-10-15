package io.github.originosfcmguardian.fcm

object GuardianProbeParser {
    private val allowedKeys = setOf(VERSION_KEY, TEST_ID_KEY, SENT_AT_KEY)
    private val testIdPattern = Regex("^[A-Za-z0-9_-]{1,80}$")
    private val positiveDecimalPattern = Regex("^[1-9][0-9]{0,18}$")

    fun parse(data: Map<String, String>): GuardianProbe? {
        if (data.keys != allowedKeys) return null
        if (data[VERSION_KEY] != SUPPORTED_VERSION) return null
        val testId = data[TEST_ID_KEY] ?: return null
        if (!isSafeTestId(testId)) return null
        val encodedSentAt = data[SENT_AT_KEY] ?: return null
        if (!positiveDecimalPattern.matches(encodedSentAt)) return null
        val sentAt = encodedSentAt.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return GuardianProbe(testId, sentAt)
    }

    internal fun isSafeTestId(value: String): Boolean = testIdPattern.matches(value)

    const val VERSION_KEY = "guardian_probe_version"
    const val TEST_ID_KEY = "test_id"
    const val SENT_AT_KEY = "sent_at_epoch_ms"
    const val SUPPORTED_VERSION = "1"
}
