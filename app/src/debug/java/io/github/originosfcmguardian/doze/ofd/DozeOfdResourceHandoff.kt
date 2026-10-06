package io.github.originosfcmguardian.doze.ofd

/** Owns a produced resource independently of Future delivery, through final caller cleanup. */
internal class DozeOfdResourceHandoff<T : AutoCloseable> {
    private val monitor = Any()
    private var terminal = false
    private var started = false
    private var producing = false
    private var borrowers = 0
    private var closing = false
    private var closeFailed = false
    private var resource: T? = null

    fun produce(factory: () -> T) {
        synchronized(monitor) {
            check(!terminal && !started)
            started = true
            producing = true
        }
        var unpublished: T? = null
        try {
            unpublished = factory()
            synchronized(monitor) {
                if (!terminal) {
                    resource = unpublished
                    unpublished = null
                }
            }
        } catch (failure: Throwable) {
            // A throwing factory cannot establish that its partial resources were closed.
            synchronized(monitor) { closeFailed = true }
            throw failure
        } finally {
            // If caller cleanup won publication, only the producer owns this late resource.
            unpublished?.let(::closeResource)
            val closeAfterPublication = synchronized(monitor) {
                producing = false
                takeForClose()
            }
            closeClaimed(closeAfterPublication)
        }
    }

    fun borrow(): T = synchronized(monitor) {
        check(!terminal && !producing)
        requireNotNull(resource)
    }

    /** Retain ownership until the real asynchronous action and all its exported-FD finallys exit. */
    fun <R> withBorrow(action: (T) -> R): R {
        val borrowed = synchronized(monitor) {
            check(!terminal && !producing)
            requireNotNull(resource).also { borrowers++ }
        }
        try { return action(borrowed) }
        finally {
            val closeAfterBorrow = synchronized(monitor) {
                borrowers--
                takeForClose()
            }
            closeClaimed(closeAfterBorrow)
        }
    }

    /** A producer still creating/closing cannot be counted as completed cleanup. */
    fun close(): Boolean {
        val owned = synchronized(monitor) {
            terminal = true
            takeForClose()
        }
        closeClaimed(owned)
        return synchronized(monitor) {
            !producing && borrowers == 0 && !closing && resource == null && !closeFailed
        }
    }

    /** monitor held; ownership moves to exactly one closing lane. */
    private fun takeForClose(): T? {
        if (!terminal || producing || borrowers != 0 || closing) return null
        return resource.also {
            resource = null
            if (it != null) closing = true
        }
    }

    private fun closeClaimed(owned: T?) {
        if (owned == null) return
        try { closeResource(owned) }
        finally { synchronized(monitor) { closing = false } }
    }

    private fun closeResource(owned: T) {
        try { owned.close() }
        catch (_: Throwable) { synchronized(monitor) { closeFailed = true } }
    }
}
