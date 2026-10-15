package io.github.originosfcmguardian.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCountsSelectionGateTest {
    @Test fun loadingAndFailedSelectionsCannotPublishAnEmptyZero() {
        assertFalse(recoveryCountsSelectionReady(HomeSourceLoadState.LOADING, false))
        assertFalse(recoveryCountsSelectionReady(HomeSourceLoadState.FAILED, false))
        assertFalse(recoveryCountsSelectionReady(HomeSourceLoadState.LOADED, true))
    }

    @Test fun successfullyLoadedEmptySelectionCanShowZero() {
        assertTrue(recoveryCountsSelectionReady(HomeSourceLoadState.LOADED, false))
    }
}
