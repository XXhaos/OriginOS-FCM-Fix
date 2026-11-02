package io.github.originosfcmguardian.diagnostics

import java.security.MessageDigest

/**
 * Export-time copy only. App references are local to this report: the same reference in another
 * report need not denote the same app. Runtime selections, authority and diagnostic files are
 * untouched. Device model/system versions and typed outcome/timing/resource evidence stay useful.
 */
internal fun sanitizeDiagnosticReportForExport(report: CombinedDiagnosticReport): CombinedDiagnosticReport {
    if (report.exportRedacted) return report
    val packageNames = (report.targets.orEmpty().map { it.packageName } +
        listOfNotNull(report.stoppedRecovery.latestRecord?.packageName)).distinct().sorted()
    val references = packageNames.withIndex().associate { (index, packageName) ->
        packageName to "app-${index + 1}"
    }
    val processReferences = mutableMapOf<String, String>()
    fun processReference(value: String): String = processReferences.getOrPut(value) {
        // Preserve the validated UUID-shaped diagnostic field without exporting its raw value.
        // One-way references retain same-process CPU/resource comparisons across report sections.
        val hex = MessageDigest.getInstance("SHA-256")
            .digest("guardian-diagnostic-process-v1:$value".toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
    return report.copy(
        exportRedacted = true,
        targets = report.targets?.map { target ->
            val reference = references.getValue(target.packageName)
            DiagnosticTarget(packageName = reference, currentLabel = reference)
        },
        stoppedRecovery = report.stoppedRecovery.copy(
            latestRecord = report.stoppedRecovery.latestRecord?.let { record ->
                record.copy(packageName = references.getValue(record.packageName))
            },
        ),
        fcmRecoveryTrial = report.fcmRecoveryTrial.copy(events = report.fcmRecoveryTrial.events.map { event ->
            event.copy(power = event.power?.let { power ->
                power.copy(processInstanceId = power.processInstanceId?.let(::processReference))
            })
        }),
        protectionResources = report.protectionResources.copy(samples = report.protectionResources.samples.map { sample ->
            sample.copy(processInstance = processReference(sample.processInstance))
        }),
    )
}
