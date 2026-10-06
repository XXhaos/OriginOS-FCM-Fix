package io.github.originosfcmguardian.privileged

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Payload-free signal: app scheduling restarts a complete interval for explicit manual activity. */
object GmsManualHeartbeatEvents {
    private val value = MutableStateFlow(0L)
    private val unknownValue = MutableStateFlow(0L)
    val serial: StateFlow<Long> = value.asStateFlow()
    /** Initial value is a listener baseline, not an event to replay after recreation. */
    val unknownSerial: StateFlow<Long> = unknownValue.asStateFlow()
    @Synchronized internal fun happened() { value.value = Math.addExact(value.value, 1) }
    @Synchronized internal fun unknown() { unknownValue.value = Math.addExact(unknownValue.value, 1) }
}
