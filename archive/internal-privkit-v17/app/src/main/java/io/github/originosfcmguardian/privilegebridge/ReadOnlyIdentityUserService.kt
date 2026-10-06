package io.github.originosfcmguardian.privilegebridge

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.system.Os
import androidx.annotation.Keep
import io.github.originosfcmguardian.privileged.SystemStoppedIdentityReadPort
import priv.kit.core.userservice.PrivilegeUserServiceEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** Dedicated, single-use Priv Kit UserService. No shell commands or shared-server termination. */
@Keep
class ReadOnlyIdentityUserService @Keep constructor() : IReadOnlyPrivilegeIdentity.Stub() {
    private val closed = AtomicBoolean(false)
    private val admitted = AtomicBoolean(false)
    private val caller = AtomicInteger(-1)

    override fun getReadEndpoint(): IBinder {
        // The library's gate forwards as shell and does not preserve app Binder identity.
        // Bootstrap returns no data; direct reads below authenticate the original app caller.
        check(Os.getuid() == 2000 && Binder.getCallingUid() == 2000)
        check(!PrivilegeUserServiceEnvironment.isEmbedded && !closed.get())
        return asBinder()
    }

    override fun readIdentity(generation: Long, owner: IBinder?): Bundle {
        val callingUid = Binder.getCallingUid()
        check(Os.getuid() == 2000 && !PrivilegeUserServiceEnvironment.isEmbedded)
        require(generation > 0 && owner != null && callingUid in 10_000..99_999)
        check(!closed.get() && admitted.compareAndSet(false, true))
        val death = IBinder.DeathRecipient { closed.set(true) }
        var linked = false
        return try {
            owner.linkToDeath(death, 0); linked = true
            check(owner.isBinderAlive && !closed.get())
            val reads = SystemStoppedIdentityReadPort()
            // Capture Binder caller before leaving the Binder thread; re-read system ownership.
            check(authenticatedGuardianCaller(callingUid, reads.packagesForUid(callingUid)))
            caller.set(callingUid)
            val environment = reads.environment()
            check(environment.serviceUid == 2000 && environment.currentUser == 0)
            check(owner.isBinderAlive && !closed.get())
            Bundle().apply {
                putLong(KEY_GENERATION, generation)
                putInt(KEY_UID, requireNotNull(environment.serviceUid))
                putInt(KEY_USER, requireNotNull(environment.currentUser))
            }
        } finally {
            if (linked) runCatching { owner.unlinkToDeath(death, 0) }
        }
    }

    override fun cancel() {
        // Only the freshly authenticated caller can revoke this already admitted read.
        check(Binder.getCallingUid() == caller.get() && caller.get() >= 10_000)
        closed.set(true)
    }

    override fun destroy() {
        // Priv Kit's dedicated host invokes the reserved destroy transaction as shell.
        check(Os.getuid() == 2000 && Binder.getCallingUid() == 2000)
        check(!PrivilegeUserServiceEnvironment.isEmbedded)
        closed.set(true)
        // This entry is only in a dedicated child. The shared Priv Kit server remains alive.
        exitProcess(0)
    }

    internal companion object {
        const val KEY_GENERATION = "generation"
        const val KEY_UID = "uid"
        const val KEY_USER = "user"
    }
}
