package io.github.originosfcmguardian.ui

/** Today/week belong to this saved calculation, not the time the user opens the sheet. */
internal fun recoveryCountsSnapshotText(calculatedAtMillis: Long?, zoneId: String?): String {
    val timestamp = if (calculatedAtMillis != null && zoneId != null) runCatching {
        java.time.Instant.ofEpochMilli(calculatedAtMillis).atZone(java.time.ZoneId.of(zoneId))
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrNull() else null
    return if (timestamp != null) "统计截至 $timestamp（$zoneId）"
        else "统计时间未知；请刷新"
}
