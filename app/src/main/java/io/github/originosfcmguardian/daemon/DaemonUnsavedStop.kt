package io.github.originosfcmguardian.daemon

/** Failed-before-file-creation is still a pending OFF in this process, never permission to resume ON. */
internal class DaemonUnsavedStop {
    private var pending: DaemonAppStopIntent? = null
    @Synchronized fun current(): DaemonAppStopIntent? = pending
    @Synchronized fun retain(value: DaemonAppStopIntent) {
        check(pending == null || pending == value)
        pending = value
    }
    fun persist(save: (DaemonAppStopIntent) -> Boolean): DaemonAppStopIntent? {
        val value = current() ?: return null
        check(save(value)) { "OFF intent persistence is unconfirmed" }
        synchronized(this) { check(pending == value); pending = null }
        return value
    }
}
