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
            listOf("pm", "list", "packages", "--user", "0", "-e", "-U", GMS_PACKAGE_NAME),
            SafeCommand.GetGmsEnabledPackage.argv(),
        )
        assertEquals(
            listOf("cmd", "appops", "get", "--user", "0", GMS_PACKAGE_NAME, "WAKE_LOCK"),
            SafeCommand.GetGmsAppOpByPackage(AppOp.WAKE_LOCK).argv(),
        )
        assertEquals(
            listOf("cmd", "appops", "get", "--user", "0", "10208", "WAKE_LOCK"),
            SafeCommand.GetAppOpByUid(10_208, AppOp.WAKE_LOCK).argv(),
        )
        assertEquals(
            listOf("pm", "path", "--user", "0", "com.vivo.pem"),
            SafeCommand.GetVivoPemPackagePath.argv(),
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
        assertEquals(
            listOf(
                "cmd", "package", "list", "packages", "--user", "0", "--uid", "10434", "-U",
            ),
            SafeCommand.GetTargetPackage("org.telegram.messenger").uidOwnersArgv(10_434),
        )
        assertEquals(
            listOf(
                "logcat", "-b", "events", "-d", "-t", "256", "-v", "epoch",
                "am_proc_start:I", "am_uid_active:I", "am_uid_idle:I", "*:S",
            ),
            SafeCommand.GetTargetProcessEvents(10_434, "org.telegram.messenger").argv(),
        )
    }

    @Test
    fun `factory reconstructs only exact allowlisted operations`() {
        val command = SafeCommand.GetAppOpByUid(10_208, AppOp.RUN_ANY_IN_BACKGROUND)
        assertEquals(command, SafeCommandFactory.fromIpc(command.operationId, 10_208))
        assertEquals(
            SafeCommand.GetDeviceIdleWhitelist,
            SafeCommandFactory.fromIpc(SafeCommand.GetDeviceIdleWhitelist.operationId),
        )
        assertEquals(
            SafeCommand.GetTargetAutostartRow("org.telegram.messenger"),
            SafeCommandFactory.fromIpc(
                SafeCommand.GetTargetAutostartRow("example.valid").operationId,
                packageName = "org.telegram.messenger",
            ),
        )
        assertEquals(
            SafeCommand.GetGuardianAutostartRow,
            SafeCommandFactory.fromIpc(SafeCommand.GetGuardianAutostartRow.operationId),
        )
        assertEquals(
            SafeCommand.GetTargetProcessEvents(10_434, "org.telegram.messenger"),
            SafeCommandFactory.fromIpc(
                SafeCommand.GetTargetProcessEvents(10_000, "example.valid").operationId,
                uid = 10_434,
                packageName = "org.telegram.messenger",
            ),
        )

        assertNull(SafeCommandFactory.fromIpc("run_arbitrary_shell", 10_208))
        assertNull(SafeCommandFactory.fromIpc(command.operationId, null))
        assertNull(SafeCommandFactory.fromIpc(command.operationId, 100_000))
        assertNull(SafeCommandFactory.fromIpc(SafeCommand.GetDeviceIdleWhitelist.operationId, 10_208))
        assertNull(SafeCommandFactory.fromIpc(SafeCommand.GetGuardianAutostartRow.operationId, 10_362))
        assertNull(
            SafeCommandFactory.fromIpc(
                SafeCommand.GetTargetAutostartRow("example.valid").operationId,
                packageName = "org.telegram.messenger;id",
            ),
        )
        assertNull(
            SafeCommandFactory.fromIpc(
                SafeCommand.GetTargetProcessEvents(10_000, "example.valid").operationId,
                uid = 10_434,
                packageName = "org.telegram.messenger;id",
            ),
        )
        assertNull(
            SafeCommandFactory.fromIpc(
                SafeCommand.GetTargetProcessEvents(10_000, "example.valid").operationId,
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
    fun `uid command rejects root cross-user and invalid values`() {
        for (uid in listOf(Int.MIN_VALUE, -1, 0, 100_000, 999_102_08)) {
            assertFailsWith<IllegalArgumentException> {
                SafeCommand.GetAppOpByUid(uid, AppOp.WAKE_LOCK)
            }
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
