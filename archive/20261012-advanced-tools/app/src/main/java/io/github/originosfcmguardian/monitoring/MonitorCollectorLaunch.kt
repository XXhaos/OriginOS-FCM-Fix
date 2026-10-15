package io.github.originosfcmguardian.monitoring

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Enter the collector's try/finally even if STOP arrives before the IO queue starts its body. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun CoroutineScope.launchMonitorCollector(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend () -> Unit,
): Job = launch(dispatcher, start = CoroutineStart.ATOMIC) { block() }
