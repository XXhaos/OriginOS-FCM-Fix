package io.github.originosfcmguardian.doze

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One explicit independent read. A mapping result is not production ownership or write authority. */
@LargeTest
class DozeEffectiveOwnerReadOnlyDeviceTest {
    @Test(timeout = 50_000)
    fun fixedEffectiveOwnerReadReturnsSmallCurrentMappingOrUnknownSummary() {
        assumeTrue("Explicit guardianRunDozeEffectiveOwnerReadOnly=true is required",
            InstrumentationRegistry.getArguments().getString("guardianRunDozeEffectiveOwnerReadOnly") == "true")
        assumeTrue("Matching debug build/user0 is required", BuildConfig.DEBUG && Process.myUid() in 10_000..99_999 &&
            Build.FINGERPRINT == DOZE_READ_ONLY_FINGERPRINT)
        val value = DozeOwnerReadOnlyClient(InstrumentationRegistry.getInstrumentation().targetContext).readEffectiveOwners()
        val s = value.summary
        val r = s.result
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("guardian.doze.effectiveOwner.readOnly", "code=${r.code};rejection=${r.rejection};records=${r.records};" +
                "canonicalPackages=${r.canonicalPackages};processedNames=${r.processedNames};apexPackages=${r.apexPackages};applicationPackages=${r.applicationPackages};" +
                "gmsAppId=${r.gmsAppId};ownerCount=${r.ownerCount};structureDigest=${r.structureDigest};mappingDigest=${r.mappingDigest};" +
                "requestedAtMillis=${s.requestedAtMillis};finishedAtMillis=${s.finishedAtMillis};elapsedMillis=${s.elapsedMillis};" +
                "uncertainBinderExit=${s.uncertainBinderExit};cleanupCompleted=${value.cleanupCompleted};" +
                "atomicSnapshot=false;productionOwnerProof=false;write=false")
        })
        assertTrue("Independent shell cleanup must complete", value.cleanupCompleted)
        assertTrue("The service must return a valid bounded summary", s.isValidEffectiveSummary())
        // UNKNOWN is an observed qualification rejection; never turn it into an owner claim.
    }
}
