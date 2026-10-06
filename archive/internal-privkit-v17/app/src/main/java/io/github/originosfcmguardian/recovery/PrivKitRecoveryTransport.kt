package io.github.originosfcmguardian.recovery

import android.os.Binder
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.runBlocking
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** DORMANT candidate. Not referenced by the production controller factory or readiness paths. */
internal class PrivKitRecoveryTransport : RecoveryTransport {
    override fun newBinding(): RecoveryBindingPort<IStoppedRecovery> {
        AppOwnedRecoveryPolicy.requireEnabled() // Before touching Privilege or binding a service.
        return CandidateBinding()
    }

    private class CandidateBinding : RecoveryBindingPort<IStoppedRecovery> {
        private val stopped = AtomicBoolean(false)
        private val connection = AtomicReference<PrivilegeUserServiceConnection?>()
        private val endpoint = AtomicReference<IStoppedRecovery?>()
        private val owner = Binder()
        private var server: IBinder? = null
        private var serverDeath: IBinder.DeathRecipient? = null
        private var endpointDeath: IBinder.DeathRecipient? = null

        override fun checkReady() {
            AppOwnedRecoveryPolicy.requireEnabled()
            val current = requireNotNull(Privilege.serverState.value)
            check(current.uid == 2000 && Process.myUid() in 10_000..99_999 && current.lifecycleBinder.isBinderAlive)
            server = current.lifecycleBinder
        }

        override fun isCurrent(): Boolean {
            if (!AppOwnedRecoveryPolicy.ENABLED || stopped.get()) return false
            val current = Privilege.serverState.value ?: return false
            if (server == null) return current.uid == 2000 && current.lifecycleBinder.isBinderAlive
            return current.uid == 2000 && current.lifecycleBinder === server && current.lifecycleBinder.isBinderAlive
        }

        override fun start(connected: (IStoppedRecovery) -> Unit, disconnected: () -> Unit) {
            AppOwnedRecoveryPolicy.requireEnabled()
            check(admission.acquire()) { "Earlier app-owned bind/teardown has not completed" }
            try {
                bindingWorker.execute {
                    try {
                        check(isCurrent())
                        val lifecycle = requireNotNull(server)
                        val death = IBinder.DeathRecipient { stopped.set(true); disconnected() }
                        lifecycle.linkToDeath(death, 0); serverDeath = death
                        val generation = generations.incrementAndGet().also { check(it > 0) }
                        val spec = PrivilegeUserServiceSpec(
                            serviceClassName = AppOwnedRecoveryBootstrapService::class.java.name,
                            tag = "guardian_recovery_${UUID.randomUUID()}", embedded = false, daemon = false)
                        val bound = runBlocking { Privilege.bindUserService(spec) }
                        connection.set(bound)
                        if (!isCurrent()) { unbind(); disconnected(); return@execute }
                        val bootstrap = requireNotNull(IRecoveryBootstrap.Stub.asInterface(bound.binder))
                        val direct = requireNotNull(bootstrap.getRecoveryEndpoint(generation, owner))
                        val service = requireNotNull(IStoppedRecovery.Stub.asInterface(direct))
                        check(isCurrent() && direct.isBinderAlive)
                        direct.linkToDeath(death, 0); endpointDeath = death; endpoint.set(service)
                        if (!isCurrent()) { disposeEndpoint(service); unbind(); disconnected() }
                        else connected(service)
                    } catch (_: Throwable) {
                        stopped.set(true)
                        disconnected()
                        // Unknown bind/bootstrap completion retains process-wide admission until
                        // a known connection has really unbound. No retry or substitute backend.
                        runCatching { unbind() }
                    }
                }
            } catch (failure: Throwable) {
                admission.finishTeardown() // Task was not admitted; no library bind was invoked.
                throw failure
            }
        }

        override fun disposeEndpoint(endpoint: IStoppedRecovery) {
            stopped.set(true)
            runCatching { endpoint.cancel() }
            endpointDeath?.let { runCatching { endpoint.asBinder().unlinkToDeath(it, 0) } }
            this.endpoint.compareAndSet(endpoint, null)
        }

        override fun invalidate() { stopped.set(true) }

        override fun unbind() {
            stopped.set(true)
            val bound = connection.getAndSet(null) ?: return
            endpoint.getAndSet(null)?.let { runCatching { disposeEndpoint(it) } }
            serverDeath?.let { death -> runCatching { server?.unlinkToDeath(death, 0) } }
            runBlocking { bound.unbind() }
            admission.finishTeardown()
        }
    }

    private companion object {
        val admission = RecoveryCandidateAdmission()
        val generations = AtomicLong(0)
        val bindingWorker = recoveryWorker("guardian-owned-recovery-bind")
    }
}
