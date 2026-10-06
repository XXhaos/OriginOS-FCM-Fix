package io.github.originosfcmguardian.compatprobe

import priv.kit.core.Privilege

/** Compile the real Kotlin API; dependency resolution alone is not compatibility. */
internal fun connectedServerSnapshot() = Privilege.serverState.value
