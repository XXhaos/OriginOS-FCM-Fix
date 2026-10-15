package io.github.originosfcmguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SafeCommandTest {
    @Test
    fun `commands use fixed argv and explicit user zero`() {
        assertEquals(
            listOf("pm", "list", "packages", "--user", "0", "-U", GMS_PACKAGE_NAME),
            SafeCommand.GetGmsPackage.argv(),
        )
        assertEquals(
            listOf("pm", "list", "packages", "--user", "0", "-U", GUARDIAN_PACKAGE_NAME),
            SafeCommand.GetGuardianPackage.argv(),
        )
        assertEquals(
            listOf(
                "content", "query", "--user", "0", "--uri", VIVO_AUTOSTART_URI,
                "--where", "pkgname='$GUARDIAN_PACKAGE_NAME'",
            ),
            SafeCommand.GetGuardianAutostartRow.argv(),
        )
        assertEquals(
            listOf(
                "content", "query", "--user", "0", "--uri", VIVO_AUTOSTART_URI,
                "--where", "pkgname='org.telegram.messenger'",
            ),
            SafeCommand.GetTargetAutostartRow("org.telegram.messenger").argv(),
        )
    }

    @Test
    fun `factory reconstructs only exact allowlisted operations`() {
        assertNull(SafeCommandFactory.fromIpc("get_selected_target_process_events_user_0",
            uid = 10_434, packageName = "org.telegram.messenger"))
        assertNull(SafeCommandFactory.fromIpc("run_arbitrary_shell", 10_208))
        assertNull(SafeCommandFactory.fromIpc(SafeCommand.GetGuardianAutostartRow.operationId, 10_362))
        assertNull(
            SafeCommandFactory.fromIpc(
                SafeCommand.GetTargetAutostartRow("example.valid").operationId,
                packageName = "org.telegram.messenger;id",
            ),
        )
        assertNull(
            SafeCommandFactory.fromIpc(
                "get_selected_target_process_events_user_0",
                uid = 10_434,
                packageName = "org.telegram.messenger;id",
            ),
        )
        assertNull(
            SafeCommandFactory.fromIpc(
                "get_selected_target_process_events_user_0",
                uid = 110_434,
                packageName = "org.telegram.messenger",
            ),
        )
        assertNull(
            SafeCommandFactory.fromIpc(
                SafeCommand.GetTargetAutostartRow("example.valid").operationId,
                10_434,
                "org.telegram.messenger",
            ),
        )
        assertNull(
            SafeCommandFactory.fromIpc(
                SafeCommand.GetCurrentUser.operationId,
                packageName = "org.telegram.messenger",
            ),
        )
    }

    @Test
    fun `retired OEM autostart read operations cannot be dispatched over IPC`() {
        assertNull(SafeCommandFactory.fromIpc(SafeCommand.GetGuardianAutostartRow.operationId))
        assertNull(
            SafeCommandFactory.fromIpc(
                SafeCommand.GetTargetAutostartRow("example.valid").operationId,
                packageName = "org.telegram.messenger",
            ),
        )
    }

    @Test
    fun `retired standalone GMS policy commands cannot be dispatched`() {
        listOf("get_gms_enabled_package_user_0", "get_deviceidle_whitelist",
            "get_vivo_pem_list", "get_vivo_pem_package_path",
            "get_gms_appop_package_wake_lock", "get_gms_appop_uid_run_any_in_background"
        ).forEach { id ->
            assertNull(SafeCommandFactory.fromIpc(id), id)
            assertNull(SafeCommandFactory.fromIpc(id, 10208), id)
        }
    }

    @Test
    fun `package argument grammar rejects shell sql quotes whitespace and oversized input`() {
        listOf(
            "org.telegram.messenger",
            "io.github.app_2",
            "Com.Example.App",
        ).forEach { assertEquals(true, isSafeAndroidPackageName(it)) }

        listOf(
            "telegram",
            "org.telegram messenger",
            "org.telegram';drop",
            "org.telegram/messenger",
            "org..telegram",
            ".org.telegram",
            "a.${"b".repeat(254)}",
        ).forEach { assertEquals(false, isSafeAndroidPackageName(it), it) }
    }

    @Test
    fun `selected target uid range excludes system shared and cross user identities`() {
        assertEquals(false, isUserZeroTargetAppUid(9_999))
        assertEquals(true, isUserZeroTargetAppUid(10_000))
        assertEquals(true, isUserZeroTargetAppUid(99_999))
        assertEquals(false, isUserZeroTargetAppUid(100_000))
    }
}
