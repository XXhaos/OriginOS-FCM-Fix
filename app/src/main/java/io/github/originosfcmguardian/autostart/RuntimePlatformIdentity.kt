package io.github.originosfcmguardian.autostart

/** A diagnostic/runtime binding, not an allowlist of devices tested during development. */
internal fun validRuntimeFingerprint(value: String?): Boolean =
    value != null && value.length in 1..256 && value.all { it.code in 33..126 && it != '|' }
