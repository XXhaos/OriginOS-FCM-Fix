package io.github.originosfcmguardian.core.gms

/** Only current capability facts, no addresses. A stale validated VPN alone is insufficient. */
data class FcmMcsNetwork(val internet: Boolean, val validated: Boolean, val notSuspended: Boolean, val vpn: Boolean) {
    val usable: Boolean get() = internet && validated && notSuspended
}
fun usableFcmMcsNetwork(active: FcmMcsNetwork?, networks: List<FcmMcsNetwork>): Boolean =
    active?.usable == true && (!active.vpn || networks.any { !it.vpn && it.usable })
