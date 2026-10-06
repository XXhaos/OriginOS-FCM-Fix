package io.github.originosfcmguardian.privileged

import android.os.IBinder

internal class GmsHeartbeatBinderLifetime(private val binder: IBinder) : GmsHeartbeatLifetime {
    private var recipient: IBinder.DeathRecipient? = null
    override fun alive() = binder.isBinderAlive
    override fun link(death: () -> Unit) {
        check(recipient == null)
        val next = IBinder.DeathRecipient { death() }
        recipient = next
        binder.linkToDeath(next, 0)
    }
    override fun unlink() { recipient?.let { binder.unlinkToDeath(it, 0) }; recipient = null }
}
