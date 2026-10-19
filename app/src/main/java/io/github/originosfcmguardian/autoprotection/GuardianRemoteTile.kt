package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.daemon.DaemonUiSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** One collected remote aggregate, never a mix with the subsequently published local mirror. */
internal fun guardianRemoteTilePresentation(snapshot: DaemonUiSnapshot?): GuardianTilePresentation {
    if (snapshot == null) return GuardianTilePresentation(false, "正在同步保护状态", available = false)
    val runtime = snapshot.automatic
    val configurationAvailable = snapshot.configurationAvailable && runtime.configurationAvailable
    val unsettled = runtime.waitingForReconciliation || runtime.revocationSaveFailed
    val confirmedOff = configurationAvailable && !runtime.masterEnabled && !unsettled
    val presentation = guardianTilePresentation(runtime,
        snapshot.hostReady && configurationAvailable && runtime.masterEnabled)
    return presentation.copy(
        // A missing connection/configuration is unknown, whereas acknowledged master OFF is inactive.
        available = configurationAvailable && !unsettled && (snapshot.hostReady || confirmedOff),
        subtitle = when {
            runtime.revocationSaveFailed -> "关闭结果待确认"
            !configurationAvailable -> "保护配置暂不可用"
            else -> presentation.subtitle
        },
    )
}

/** Only panel-owned reads. A new listening session always gets its own immediate opportunity. */
internal suspend fun refreshRemoteGuardianTile(
    listening: () -> Boolean,
    canRead: () -> Boolean,
    refresh: suspend () -> DaemonUiSnapshot?,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    for (pause in longArrayOf(0, 1_000, 4_000, 30_000)) {
        currentCoroutineContext().ensureActive()
        if (!listening() || !canRead()) return
        if (pause > 0) wait(pause)
        currentCoroutineContext().ensureActive()
        if (!listening() || !canRead()) return
        val snapshot = try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        // The existing refresh facade may swallow cancellation; no cancelled tile pass may continue.
        currentCoroutineContext().ensureActive()
        if (!listening()) return
        if (snapshot?.hostReady == true || snapshot?.let {
                it.configurationAvailable && !it.automatic.masterEnabled ||
                    it.automatic.waitingForReconciliation || it.automatic.revocationSaveFailed
            } == true) return
    }
}
