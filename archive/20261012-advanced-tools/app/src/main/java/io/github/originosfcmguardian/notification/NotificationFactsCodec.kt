package io.github.originosfcmguardian.notification

import java.nio.charset.StandardCharsets

internal object NotificationFactsCodec {
    private const val HEADER = "GUARDIAN_NOTIFICATION_FACTS_V1"
    private const val SEPARATOR = '|'

    fun encode(events: List<NotificationFact>): ByteArray {
        require(events.size <= NotificationFactsStore.MAX_EVENTS)
        val bytes = buildString {
            append(HEADER).append('\n')
            events.forEach { event ->
                append(event.packageName).append(SEPARATOR)
                append(event.observedAtEpochMillis).append(SEPARATOR)
                append(event.kind.name).append(SEPARATOR)
                append(event.evidenceLevel.name).append('\n')
            }
        }.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= NotificationFactsStore.MAX_FILE_BYTES)
        return bytes
    }

    fun decode(bytes: ByteArray): List<NotificationFact>? {
        if (bytes.isEmpty() || bytes.size > NotificationFactsStore.MAX_FILE_BYTES) return null
        val text = bytes.toString(StandardCharsets.UTF_8)
        if (!text.endsWith('\n')) return null
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER || lines.lastOrNull() != "") return null
        val encodedEvents = lines.drop(1).dropLast(1)
        if (encodedEvents.size > NotificationFactsStore.MAX_EVENTS) return null
        return encodedEvents.map { line ->
            val fields = line.split(SEPARATOR)
            if (fields.size != 4) return null
            runCatching {
                NotificationFact(
                    packageName = fields[0],
                    observedAtEpochMillis = fields[1].toLong(),
                    kind = enumValueOf(fields[2]),
                    evidenceLevel = enumValueOf(fields[3]),
                )
            }.getOrNull() ?: return null
        }
    }
}
