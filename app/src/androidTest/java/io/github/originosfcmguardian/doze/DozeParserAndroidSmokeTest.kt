package io.github.originosfcmguardian.doze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Synthetic-only Android regex and projection check; performs no device or Shizuku command. */
class DozeParserAndroidSmokeTest {
    @Test fun exactGmsIdentityParsersInitializeAndReadSyntheticInput() {
        val runner = DozeCommandRunner({ true }, { 1_000L }, {})
        val projected = runner.projectPackageLines(ByteArrayInputStream(DUMP.toByteArray()))
        val packageState = parseGmsPackageProjection(projected)
        assertEquals(10_208, packageState.appId)
        assertEquals(-1, packageState.pccId)
        assertEquals(setOf(0, 666, 999), packageState.profileUserIds)
        assertEquals(setOf(0, 999), packageState.installedUserIds)
        assertTrue(packageState.userZeroEnabled)
        assertEquals(setOf(0, 666, 999), parseAndroidUsers(USERS))
        assertEquals(setOf("com.google.android.gms"),
            parseUidOwners("package:com.google.android.gms uid:10208\n", 10_208))
        assertTrue(verifiedEnabledList("package:com.google.android.gms uid:10208\n", 10_208))
    }

    private companion object {
        const val DUMP = "Package [com.google.android.gms] (a9bfb3e):\n" +
            "appId=10208\npccId=-1\n" +
            "User 0: installed=true hidden=false enabled=1\n" +
            "User 666: installed=false hidden=false enabled=0\n" +
            "User 999: installed=true hidden=false enabled=1\n" +
            "User 0:\nUser 666:\nUser 999:\n"
        const val USERS = "Users:\nUserInfo{0:Owner:13} running\n" +
            "UserInfo{666:Private:30}\nUserInfo{999:Clone:30}\n"
    }
}
