package io.github.originosfcmguardian.privileged

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerStatParser

/** Fixed proc sources. Only an errno-confirmed missing PID is absence; denied reads stay unknown. */
internal class SystemGmsHeartbeatCallerReadPort : GmsHeartbeatCallerReadPort {
    private val identity = SystemGmsConnectionReadPort()
    override fun environment() = identity.environment()
    override fun packagesForUid(uid: Int) = identity.packagesForUid(uid)
    override fun process(pid: Int, uid: Int, interrupted: () -> Boolean): GmsHeartbeatCallerProcess {
        if (pid !in 1..4_194_304 || uid !in 10_000..99_999 || interrupted()) return GmsHeartbeatCallerProcess.Unknown
        val directory = "/proc/$pid"
        return try {
            val fd = Os.open("$directory/stat", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                val bytes = ByteArray(GmsHeartbeatCallerStatParser.MAX_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    if (interrupted()) return GmsHeartbeatCallerProcess.Unknown
                    val next = Os.read(fd, bytes, count, bytes.size - count)
                    if (next == 0) break
                    count += next
                }
                if (count > GmsHeartbeatCallerStatParser.MAX_BYTES || interrupted())
                    return GmsHeartbeatCallerProcess.Unknown
                val ticks = GmsHeartbeatCallerStatParser.startTicks(String(bytes, 0, count, Charsets.US_ASCII), pid)
                    ?: return GmsHeartbeatCallerProcess.Unknown
                GmsHeartbeatCallerProcess.Present(ticks)
            } finally { Os.close(fd) }
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ENOENT || interrupted()) GmsHeartbeatCallerProcess.Unknown
            else try { Os.stat(directory); GmsHeartbeatCallerProcess.Unknown }
            catch (missing: ErrnoException) {
                if (missing.errno == OsConstants.ENOENT && !interrupted()) GmsHeartbeatCallerProcess.Gone else GmsHeartbeatCallerProcess.Unknown
            }
        } catch (_: Throwable) { GmsHeartbeatCallerProcess.Unknown }
    }
}
