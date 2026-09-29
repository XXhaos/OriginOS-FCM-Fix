package io.github.originosfcmguardian.fcm

import kotlinx.coroutines.withTimeoutOrNull

/** A local wait deadline is not a completed SDK failure and does not cancel SDK transport. */
internal suspend fun awaitRegistrationResult(
    timeoutMillis: Long,
    request: suspend () -> RegistrationResult,
): RegistrationResult = withTimeoutOrNull(timeoutMillis) { request() } ?: RegistrationResult.TimedOut
