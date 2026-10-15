package io.github.originosfcmguardian.ui

/** An empty list is a real zero only after the saved selection was successfully loaded. */
internal fun recoveryCountsSelectionReady(loadState: HomeSourceLoadState, corrupt: Boolean): Boolean =
    loadState == HomeSourceLoadState.LOADED && !corrupt
