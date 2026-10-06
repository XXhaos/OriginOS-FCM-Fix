package io.github.originosfcmguardian.recovery

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.originosfcmguardian.BuildConfig
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable per-controller factory. Production has exactly this external backend. */
internal fun interface RecoveryTransport {
    fun newBinding(): RecoveryBindingPort<IStoppedRecovery>
}

internal class ExternalShizukuRecoveryTransport(private val app: Context) : RecoveryTransport {
    override fun newBinding(): RecoveryBindingPort<IStoppedRecovery> = ExternalBinding(app)

    private class ExternalBinding(app: Context) : RecoveryBindingPort<IStoppedRecovery> {
        private val token = UUID.randomUUID().toString().replace("-", "").take(16)
        private val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, StoppedRecoveryUserService::class.java.name))
            .daemon(false).tag("guardian-recovery-$token").processNameSuffix("guardian_recovery_$token")
            .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        private val linked = ConcurrentHashMap<IStoppedRecovery, IBinder.DeathRecipient>()
        private val stopped = AtomicBoolean(false)
        private var connection: ServiceConnection? = null

        override fun checkReady() {
            check(Shizuku.pingBinder() && Shizuku.getUid() == 2000 &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        }

        override fun start(connected: (IStoppedRecovery) -> Unit, disconnected: () -> Unit) {
            val callback = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    try {
                        val remote = requireNotNull(IStoppedRecovery.Stub.asInterface(binder))
                        val death = IBinder.DeathRecipient { disconnected() }
                        binder.linkToDeath(death, 0)
                        linked[remote] = death
                        // Lifecycle checks only local memory here; disk authority remains on IO.
                        connected(remote)
                    } catch (_: Throwable) { disconnected() }
                }
                override fun onServiceDisconnected(name: ComponentName) { disconnected() }
            }
            connection = callback
            completeRecoveryRegistration(stopped::get,
                { Shizuku.bindUserService(args, callback) },
                { Shizuku.unbindUserService(args, callback, true) })
        }

        override fun disposeEndpoint(endpoint: IStoppedRecovery) {
            runCatching { endpoint.cancel() }
            linked.remove(endpoint)?.let { runCatching { endpoint.asBinder().unlinkToDeath(it, 0) } }
        }

        override fun invalidate() { stopped.set(true) }

        override fun unbind() {
            invalidate()
            connection?.let { Shizuku.unbindUserService(args, it, true) }
        }
    }
}
