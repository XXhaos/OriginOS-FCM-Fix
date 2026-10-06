package io.github.originosfcmguardian.fcmrecovery

import org.junit.Assert.*
import org.junit.Test

class FcmPowerReferenceTest {
    private open class Port : FcmPowerReadPort {
        override fun batteryPercent(): Int? = 80
        override fun chargingStatus() = FcmChargingStatus.CHARGING
        override fun pluggedSource() = FcmPluggedSource.USB
        override fun interactive() = false
        override fun deviceIdle() = true
        override fun mainProcessCpuMillis(): Long? = 25
        override fun processAgeMillis(): Long? = 100
        override fun processInstanceId() = "12345678-1234-1234-1234-123456789abc"
    }
    @Test fun `optional failures remain null independently without losing other fields`() {
        val reference = captureFcmPowerReference(object : Port() {
            override fun batteryPercent(): Int? = error("optional read failed")
            override fun interactive(): Boolean = error("optional read failed")
            override fun mainProcessCpuMillis(): Long? = error("optional read failed")
        })
        assertNull(reference.batteryPercent); assertNull(reference.interactive); assertNull(reference.mainProcessCpuMillis)
        assertEquals(FcmChargingStatus.CHARGING, reference.chargingStatus)
        assertEquals(FcmPluggedSource.USB, reference.pluggedSource)
        assertEquals(true, reference.deviceIdle); assertEquals(100L, reference.processAgeMillis)
        assertNotNull(reference.processInstanceId)
    }
    @Test fun `invalid optional values are not fabricated as zero`() {
        val reference = captureFcmPowerReference(object : Port() {
            override fun batteryPercent() = 101
            override fun mainProcessCpuMillis() = -1L
            override fun processAgeMillis() = -1L
            override fun processInstanceId() = "private arbitrary text"
        })
        assertNull(reference.batteryPercent); assertNull(reference.mainProcessCpuMillis)
        assertNull(reference.processAgeMillis); assertNull(reference.processInstanceId)
        listOf(-1 to 100, 0 to -1, 1 to 0, 101 to 100).forEach { (level, scale) ->
            assertNull(fcmBatteryPercent(level, scale))
        }
        assertEquals(0, fcmBatteryPercent(0, 100)); assertEquals(50, fcmBatteryPercent(1, 2))
    }
    @Test fun `process reset context remains explicit rather than calculating cross process deltas`() {
        val before = captureFcmPowerReference(Port())
        val after = captureFcmPowerReference(object : Port() {
            override fun mainProcessCpuMillis() = 0L
            override fun processAgeMillis() = 0L
            override fun processInstanceId() = "22345678-1234-1234-1234-123456789abc"
        })
        assertNotEquals(before.processInstanceId, after.processInstanceId)
        assertEquals(0L, after.mainProcessCpuMillis); assertEquals(0L, after.processAgeMillis)
    }
}
