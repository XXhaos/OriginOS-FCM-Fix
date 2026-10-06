package io.github.originosfcmguardian.recovery

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A revoked request never becomes live again, even if a later runtime session is enabled. */
internal class AutomaticRecoveryAuthorization(private val authorized: () -> Boolean) {
    private val revoked = AtomicBoolean(false)

    fun active(): Boolean {
        if (revoked.get()) return false
        val allowed = runCatching(authorized).getOrDefault(false)
        if (!allowed) revoked.set(true)
        return allowed && !revoked.get()
    }
}

internal data class AutomaticRecoveryConsent(
    val id: String,
    val consentedAtMillis: Long,
    val version: Int,
) {
    fun validate() {
        require(id.length == 36 && UUID.fromString(id).toString() == id) { "自动恢复同意标识无效" }
        // v1 session/saved authority remains historical; v2 explicitly includes restart scope.
        require(consentedAtMillis > 0 && version in setOf(1, 2, 4)) { "自动恢复同意时间或版本无效" }
    }
}
