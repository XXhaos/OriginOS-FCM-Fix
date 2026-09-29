package io.github.originosfcmguardian.doze

import android.content.Context
import android.content.ContextWrapper
import io.github.originosfcmguardian.core.doze.DozeJournalPort
import io.github.originosfcmguardian.core.doze.DozeOutcome
import java.io.File

/** Fixed debug endpoint with a real, isolated schema-v2 AtomicFile journal. */
internal class OwnedDozeTransactionClient(
    context: Context,
    onDurableCommit: (ByteArray) -> Unit,
) {
    private val storageContext = OwnedDozeStorageContext(context.applicationContext)
    private val realStore = DozeAtomicJournalStore(storageContext)
    private val observingStore = object : DozeJournalPort {
        override fun load(): ByteArray? = realStore.load()

        override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
            if (!realStore.compareAndSet(expected, replacement)) return false
            check(realStore.load()?.contentEquals(replacement) == true) { "Owned journal commit read-back changed" }
            // A failed evidence copy refuses ACK. The real intent remains for parent review.
            onDurableCommit(replacement.copyOf())
            return true
        }
    }
    private val delegate = DozeTransactionClient(context.applicationContext, observingStore, DozeEndpoint.OWNED_DEBUG)

    val journalDirectory: File get() = storageContext.noBackupFilesDir
    fun journalBytes(): ByteArray? = realStore.load()
    fun inspect(): DozeOutcome = delegate.inspect()
    fun apply(): DozeOutcome = delegate.apply()
    fun restore(): DozeOutcome = delegate.restore()
}

/** The overridden application context keeps the existing store implementation on a fixed path. */
internal class OwnedDozeStorageContext(base: Context) : ContextWrapper(base.applicationContext) {
    private val ownedDirectory = File(base.applicationContext.noBackupFilesDir, "owned-doze-production-v2")

    override fun getApplicationContext(): Context = this

    override fun getNoBackupFilesDir(): File {
        check(ownedDirectory.canonicalFile.parentFile == baseContext.noBackupFilesDir.canonicalFile) {
            "Owned Doze journal escaped the no-backup directory"
        }
        check(ownedDirectory.isDirectory || ownedDirectory.mkdirs()) { "Cannot create owned Doze journal directory" }
        return ownedDirectory
    }
}
