package io.github.originosfcmguardian.privileged

/**
 * Single-lock lifecycle state for one short-lived privileged session.
 * Generation tokens prevent a delayed callback from an older bind becoming current.
 */
internal class PrivilegedSessionState<Remote : Any, Connection : Any> {
    class BindTicket internal constructor(val generation: Long)

    data class Bound<Remote : Any, Connection : Any>(
        val ticket: BindTicket,
        val remote: Remote,
        val connection: Connection,
    )

    data class Teardown<Remote : Any, Connection : Any>(
        val bound: Bound<Remote, Connection>?,
        val pendingConnection: Connection?,
        val activeRequestId: Long?,
    )

    private var closed = true
    private var generation = 0L
    private var pendingTicket: BindTicket? = null
    private var pendingConnection: Connection? = null
    private var bound: Bound<Remote, Connection>? = null
    private var activeRequestId: Long? = null

    @Synchronized
    fun open() {
        if (!closed) return
        closed = false
        generation++
    }

    @Synchronized
    fun isOpen(): Boolean = !closed

    @Synchronized
    fun currentBound(): Bound<Remote, Connection>? = bound.takeUnless { closed }

    @Synchronized
    fun beginBind(): BindTicket? {
        if (closed) return null
        generation++
        return BindTicket(generation).also {
            pendingTicket = it
            pendingConnection = null
            bound = null
        }
    }

    @Synchronized
    fun attachConnection(ticket: BindTicket, connection: Connection): Boolean {
        if (closed || pendingTicket != ticket || generation != ticket.generation) return false
        pendingConnection = connection
        return true
    }

    @Synchronized
    fun isCurrentBind(ticket: BindTicket, connection: Connection): Boolean = isCurrentPending(ticket, connection)

    @Synchronized
    fun runIfCurrentBind(
        ticket: BindTicket,
        connection: Connection,
        block: () -> Unit,
    ): Boolean {
        if (!isCurrentPending(ticket, connection)) return false
        block()
        return true
    }

    @Synchronized
    fun acceptBind(ticket: BindTicket, connection: Connection, remote: Remote): Boolean {
        if (!isCurrentPending(ticket, connection)) return false
        bound = Bound(ticket, remote, connection)
        pendingTicket = null
        pendingConnection = null
        return true
    }

    @Synchronized
    fun retireBind(ticket: BindTicket): Connection? {
        if (pendingTicket != ticket) return null
        val connection = pendingConnection
        pendingTicket = null
        pendingConnection = null
        generation++
        return connection
    }

    @Synchronized
    fun beginCall(bound: Bound<Remote, Connection>, requestId: Long): Boolean {
        if (closed || this.bound != bound || activeRequestId != null) return false
        activeRequestId = requestId
        return true
    }

    @Synchronized
    fun finishCall(requestId: Long) {
        if (activeRequestId == requestId) activeRequestId = null
    }

    @Synchronized
    fun disconnect(ticket: BindTicket): Teardown<Remote, Connection>? {
        val applies = bound?.ticket == ticket || pendingTicket == ticket
        if (!applies) return null
        return teardown(close = false)
    }

    @Synchronized
    fun cleanup(): Teardown<Remote, Connection> = teardown(close = false)

    @Synchronized
    fun close(): Teardown<Remote, Connection> = teardown(close = true)

    private fun isCurrentPending(ticket: BindTicket, connection: Connection): Boolean =
        !closed &&
            generation == ticket.generation &&
            pendingTicket == ticket &&
            pendingConnection === connection

    private fun teardown(close: Boolean): Teardown<Remote, Connection> {
        val result = Teardown(bound, pendingConnection, activeRequestId)
        generation++
        pendingTicket = null
        pendingConnection = null
        bound = null
        activeRequestId = null
        if (close) closed = true
        return result
    }
}
