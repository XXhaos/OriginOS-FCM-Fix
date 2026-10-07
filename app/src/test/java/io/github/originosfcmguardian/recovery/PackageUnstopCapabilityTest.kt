package io.github.originosfcmguardian.recovery

import org.junit.Assert.*
import org.junit.Test

class PackageUnstopCapabilityTest {
    private val help = "Package manager (package) commands:\n  unstop [--user USER_ID] PACKAGE\n"
    @Test fun acceptsOnlyCompleteActualCommandAdvertisement() {
        assertTrue(PackageUnstopCapability.advertised(help.toByteArray()))
        for (text in listOf("", help.trimEnd(), help.replace("unstop", "unknown"),
            help + "  unstop [--user USER_ID] PACKAGE\n", "arbitrary\n  unstop [--user USER_ID] PACKAGE\n"))
            assertFalse(PackageUnstopCapability.advertised(text.toByteArray()))
    }
}
