package io.github.originosfcmguardian.autoprotection

import kotlinx.coroutines.CancellationException

/** Subsequent display refresh only; mandatory foreground entry uses its original failure path. */
internal fun refreshProtectionStatusNotification(authorized: () -> Boolean, refresh: () -> Unit) {
    if (!authorized()) return
    try {
        refresh()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Display failure must not retire durable consent or trigger an immediate retry.
    }
}
