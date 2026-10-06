package io.github.originosfcmguardian.autoprotection

/** One FIFO start request. Read-only rejection never retires the existing protection owner. */
internal class ProtectionStartAdmission {
    private var consumed = false

    @Synchronized
    fun run(
        isCurrent: () -> Boolean,
        preflight: () -> String?,
        admit: () -> Boolean,
        commit: () -> Unit,
        reject: (String) -> Unit,
        failClosed: (commitMayHaveWritten: Boolean) -> Unit,
        obsolete: () -> Unit,
    ) {
        if (consumed) return
        consumed = true
        var admitted = false
        try {
            if (!isCurrent()) { obsolete(); return }
            val rejection = preflight()
            if (!isCurrent()) { obsolete(); return }
            if (rejection != null) { reject(rejection); return }
            // Admission checks request/revision again under the owner's lock, then retires it.
            if (!admit()) { obsolete(); return }
            admitted = true
            commit()
        } catch (_: Exception) {
            // A commit may already be durable even if its read-back failed. An obsolete read,
            // however, must not suspend a replacement operation queued behind this request.
            if (admitted || isCurrent()) failClosed(admitted) else obsolete()
        }
    }
}
