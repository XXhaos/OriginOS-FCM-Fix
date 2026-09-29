package io.github.originosfcmguardian.fcm

/** Invalidates late token results after an explicit lifecycle or privacy boundary. */
internal class FcmRegistrationRequestGate {
    private var generation = 0L
    private var activeGeneration: Long? = null

    @Synchronized
    fun tryStart(): Long? {
        if (activeGeneration != null) return null
        generation += 1
        return generation.also { activeGeneration = it }
    }

    @Synchronized
    fun completeIfCurrent(requestGeneration: Long): Boolean {
        if (activeGeneration != requestGeneration || generation != requestGeneration) return false
        activeGeneration = null
        return true
    }

    @Synchronized
    fun invalidate() {
        generation += 1
        activeGeneration = null
    }
}
