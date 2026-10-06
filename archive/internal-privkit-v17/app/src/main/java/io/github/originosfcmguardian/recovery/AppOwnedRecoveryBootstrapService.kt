package io.github.originosfcmguardian.recovery

import android.os.Binder
import android.os.IBinder
import android.system.Os
import androidx.annotation.Keep
import priv.kit.core.userservice.PrivilegeUserServiceEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Dormant, dedicated child entry. Never construct the exit-process delegate in an embedded host. */
@Keep
class AppOwnedRecoveryBootstrapService @Keep constructor() : IRecoveryBootstrap.Stub() {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var delegate: StoppedRecoveryUserService? = null
    private var owner: IBinder? = null
    private var ownerDeath: IBinder.DeathRecipient? = null

    override fun getRecoveryEndpoint(generation: Long, owner: IBinder?): IBinder {
        AppOwnedRecoveryPolicy.requireEnabled()
        check(Os.getuid() == 2000 && Binder.getCallingUid() == 2000)
        check(!PrivilegeUserServiceEnvironment.isEmbedded)
        require(generation > 0 && owner != null)
        return synchronized(lock) {
            check(!closed.get() && delegate == null && owner.isBinderAlive)
            val death = IBinder.DeathRecipient { closed.set(true); synchronized(lock) { delegate?.cancel() } }
            owner.linkToDeath(death, 0)
            try {
                check(owner.isBinderAlive && !closed.get())
                // Existing direct endpoint verifies fresh Guardian caller ownership on each RPC.
                val service = StoppedRecoveryUserService()
                this.owner = owner; ownerDeath = death; delegate = service
                service.asBinder()
            } catch (failure: Throwable) {
                runCatching { owner.unlinkToDeath(death, 0) }
                throw failure
            }
        }
    }

    override fun destroy() {
        check(Os.getuid() == 2000 && Binder.getCallingUid() == 2000)
        check(!PrivilegeUserServiceEnvironment.isEmbedded)
        closed.set(true)
        synchronized(lock) {
            ownerDeath?.let { death -> runCatching { owner?.unlinkToDeath(death, 0) } }
            delegate?.destroy()
        }
        // This constructor is registered only for embedded=false, never the shared shell server.
        exitProcess(0)
    }
}
