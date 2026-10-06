package io.github.originosfcmguardian.privileged

import android.os.DeadObjectException
import android.os.IBinder
import io.github.originosfcmguardian.core.gms.GmsAutoSession
import java.util.concurrent.atomic.AtomicBoolean

/** Strong original endpoint reference. A failed ping or a new bind is never proof of death. */
internal class GmsAutoRemoteOwner(val remote: IGuardianUserService, val generation: Long) {
    val dead = AtomicBoolean(false)
    val closed = AtomicBoolean(false)
    val invoked = AtomicBoolean(false)
    val disabled = AtomicBoolean(false)
    @Volatile var session: GmsAutoSession? = null
    private val death = IBinder.DeathRecipient { dead.set(true) }
    init {
        try { remote.asBinder().linkToDeath(death, 0) }
        catch (_: DeadObjectException) { dead.set(true) }
    }
    fun detach() { remote.asBinder().unlinkToDeath(death, 0) }
    fun unavailable(): Boolean = closed.get() || dead.get() || disabled.get()
    fun confirmRevoked(acknowledged: Boolean): Boolean {
        if (acknowledged) closed.set(true)
        return closed.get() || dead.get()
    }
}

internal fun mayArmGmsHeartbeat(owners: Collection<GmsAutoRemoteOwner>): Boolean =
    owners.all { it.closed.get() || it.dead.get() }

/** A manual call must not create a second server owner while an old auto gate is unresolved. */
internal fun mayBindManualGmsHeartbeat(owners: Collection<GmsAutoRemoteOwner>): Boolean =
    mayArmGmsHeartbeat(owners)
