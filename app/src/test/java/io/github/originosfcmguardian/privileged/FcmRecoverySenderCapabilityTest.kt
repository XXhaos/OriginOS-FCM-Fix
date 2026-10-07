package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.autostart.effectiveRecoveryComponentEnabled
import io.github.originosfcmguardian.core.gms.FcmRecoveryPolicy
import org.junit.Assert.*
import org.junit.Test

class FcmRecoverySenderCapabilityTest {
    private fun qualified(permission: String? = null, grant: (String) -> Boolean = { false },
        actionProtected: () -> Boolean? = { false }, exported: Boolean = true,
        componentEnabled: Boolean = true, applicationEnabled: Boolean = true, uid: Int = 10208,
        installed: Boolean = true, stopped: Boolean = false) = qualifiedFcmRecoverySender(
        GMS_OBSERVATION_PACKAGE, FcmRecoveryPolicy.COMPONENT, GMS_OBSERVATION_PACKAGE, uid, 10208,
        exported, componentEnabled, applicationEnabled, installed, stopped, permission, grant, actionProtected)

    @Test fun `receiver permission requires positive permission evidence for shell`() {
        assertTrue(qualified())
        assertTrue(qualified("example.PERMISSION", { it == "example.PERMISSION" }))
        assertFalse(qualified("example.PERMISSION"))
        assertFalse(qualified("example.PERMISSION", { error("permission API unavailable") }))
        assertFalse(qualified(""))
        assertFalse(qualified(actionProtected = { true }))
        assertFalse(qualified(actionProtected = { null }))
        assertFalse(qualified(actionProtected = { error("action capability unreadable") }))
    }
    @Test fun `effective app and component settings and identity constrain sender`() {
        assertTrue(qualified(componentEnabled = effectiveRecoveryComponentEnabled(false, 1)))
        assertTrue(qualified(applicationEnabled = effectiveRecoveryComponentEnabled(false, 1)))
        assertFalse(qualified(componentEnabled = effectiveRecoveryComponentEnabled(true, 2)))
        assertFalse(qualified(applicationEnabled = effectiveRecoveryComponentEnabled(true, null)))
        assertFalse(qualified(exported = false))
        assertFalse(qualified(uid = 10209))
        assertFalse(qualified(installed = false))
        assertFalse(qualified(stopped = true))
    }
}
