package io.github.originosfcmguardian.core.doze

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One fixed production storage path must share this control across all same-process adapters.
 * Local busy only prevents overlapping business operations; it is not remote cleanup evidence.
 * OFF does not acquire busy and closes ADD preparation permanently for this finite session.
 */
class GmsManualSessionControl {
    private val addClosed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    fun closeAdds() { addClosed.set(true) }
    fun addsLocallyAllowed(): Boolean = !addClosed.get()
    fun enterBusinessOperation(): Boolean = busy.compareAndSet(false, true)
    fun leaveBusinessOperation() { busy.set(false) }
}

/**
 * Global business bytes and V2 broker bytes share one pre-authority permanent session fence,
 * process-local permit, short App-UID OS storage lock and terminal-failure boundary.
 * Implementations must reject old/unknown production material, never silently migrate it.
 */
interface GmsManualSessionPort {
    val control: GmsManualSessionControl

    /** Only bounded local load/encode/CAS/fsync/readback; no probe, RPC or child wait here. */
    fun <T> withStorage(action: (GmsManualLockedDocuments) -> T): T

    /** Closes both documents and local ADD preparation after uncertain session/commit evidence. */
    fun fenceUncertainSession()
}

/** Both facades expire when their withStorage callback returns; never retain them for RPC work. */
interface GmsManualLockedDocuments {
    val global: GmsGlobalDozeDocumentPort
    val broker: GmsDozeBrokerV2DurablePort
}
