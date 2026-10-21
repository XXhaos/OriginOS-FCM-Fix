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
    @Test fun guardianClasspathRequiresOneCompleteUnambiguousPmApkLine() {
        val path = "package:/data/app/guardian/base.apk\n"
        assertEquals("/data/app/guardian/base.apk", PackageUnstopCapability.guardianApkOutput(path.toByteArray()))
        for (text in listOf("", path.trimEnd(), path + path, path + "package:/data/app/guardian/split.apk\n",
            "other:/data/app/base.apk\n", "package:relative.apk\n", "package:/data/app/base.dex\n",
            "package:/data/app/guardian/other.apk\n",
            "package:/data/app/a b.apk\n", "package:/data/app/a:b.apk\n", path + "error\n", "x".repeat(4097)))
            assertNull(PackageUnstopCapability.guardianApkOutput(text.toByteArray()))
    }
}
