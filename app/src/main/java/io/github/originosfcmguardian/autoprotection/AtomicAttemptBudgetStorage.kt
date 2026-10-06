package io.github.originosfcmguardian.autoprotection

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Cross-process lock spans the entire reservation transaction, not merely AtomicFile.write. */
internal class AtomicAttemptBudgetStorage(context: Context) : AttemptBudgetStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "automatic-attempt-budget-v1.bin"))
    private val lockFile = File(context.noBackupFilesDir, "automatic-attempt-budget-v1.lock")
    private val initialized = File(context.noBackupFilesDir, "automatic-attempt-budget-v1.initialized")

    override fun <T> exclusive(action: () -> T): T = RandomAccessFile(lockFile, "rw").use { handle ->
        val lock = handle.channel.tryLock() ?: error("Budget owner busy")
        lock.use { action() }
    }

    override fun read(): BudgetSnapshot? {
        // AtomicFile.openRead handles interrupted-write backups; never use baseFile.exists alone.
        val bytes = try {
            file.openRead().use { input ->
                val data = ByteArray(257)
                var size = 0
                while (size < data.size) {
                    val count = input.read(data, size, data.size - size)
                    if (count < 0) break
                    size += count
                }
                check(size <= 256)
                data.copyOf(size)
            }
        } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || initialized.exists()) throw missing
            return null
        }
        return BudgetSnapshotCodec.decode(bytes)
    }

    override fun write(value: BudgetSnapshot) {
        val bytes = BudgetSnapshotCodec.encode(value)
        // Mark initialization first: a crash before first commit must fail closed, not look fresh.
        if (!initialized.exists()) {
            java.io.FileOutputStream(initialized).use { it.write(1); it.fd.sync() }
        }
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failed: Exception) {
            file.failWrite(output)
            throw failed
        }
        check(!File(file.baseFile.path + ".new").exists() && read() == value) { "Budget read-back failed" }
    }
}

internal object BudgetSnapshotCodec {
    fun encode(value: BudgetSnapshot): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            require(value.attempts.size <= 3)
            output.writeInt(0x46434D42)
            output.writeInt(1)
            output.writeLong(value.clock.boot)
            output.writeLong(value.clock.elapsed)
            output.writeLong(value.clock.epoch)
            output.writeInt(value.attempts.size)
            value.attempts.forEach(output::writeLong)
            output.writeBoolean(value.quarantineUntil != null)
            value.quarantineUntil?.let(output::writeLong)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): BudgetSnapshot = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(bytes.size <= 256)
        require(input.readInt() == 0x46434D42 && input.readInt() == 1)
        val clock = BudgetClock(input.readLong(), input.readLong(), input.readLong())
        val count = input.readInt()
        require(count in 0..3)
        val attempts = List(count) { input.readLong() }
        val present = input.readUnsignedByte()
        require(present in 0..1)
        val quarantine = if (present == 1) input.readLong() else null
        require(input.available() == 0)
        BudgetSnapshot(clock, attempts, quarantine)
    }
}
