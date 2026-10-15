package io.github.originosfcmguardian.privileged

import androidx.test.filters.SmallTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fixture test: Android ICU compilation/parsing only, no Shizuku, UI or device reads. */
@SmallTest
class FcmMcsReceiverParserDeviceTest {
    @Test(timeout = 5_000)
    fun androidIcuCompilesAndQualifiesExactReceiverFixture() {
        val fixture = """
ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)
  Registered Receivers:
  * ReceiverList{99fc1e2 19441 com.google.android.gms/10208/u0 remote:7cb9aad}
    app=19441:com.google.android.gms/u0a208 pid=19441 uid=10208 user=0 #receivers=82
    Filter #0: BroadcastFilter{ce49973}
      Action: "com.google.android.intent.action.MCS_HEARTBEAT"
      Action: "com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW"
    Filter #1: BroadcastFilter{6089030}
      Action: "com.google.android.gms.gcm.HEARTBEAT_ALARM"
      requiredPermission=com.google.android.gms.permission.INTERNAL_BROADCAST
  Receiver Resolver Table:
    com.google.android.intent.action.MCS_HEARTBEAT:
      BroadcastFilter{ce49973}
""".trimIndent() + "\n"
        assertTrue(FcmMcsReceiverReader.parse(fixture.toByteArray(), 10208, allowReviewedMissingExported = true))
        assertFalse(FcmMcsReceiverReader.parse(fixture.replace("/10208/u0", "/10208/u10").toByteArray(), 10208))
        assertFalse(FcmMcsReceiverReader.parse(fixture.replace("    Filter #1", "      requiredPermission=other.permission\n    Filter #1").toByteArray(), 10208))
        assertFalse(FcmMcsReceiverReader.parse(fixture.substringBefore("  Receiver Resolver Table:").toByteArray(), 10208))
    }
}
