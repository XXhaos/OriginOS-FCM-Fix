package io.github.originosfcmguardian.fcm

import java.nio.charset.StandardCharsets

internal object FcmEvidenceCodec {
    private const val HEADER_V1 = "GUARDIAN_FCM_EVIDENCE_V1"
    private const val HEADER_V2 = "GUARDIAN_FCM_EVIDENCE_V2"
    private const val SEPARATOR = '|'

    fun encode(receipts: List<FcmReceipt>): ByteArray {
        require(receipts.size <= FcmEvidenceStore.MAX_RECEIPTS)
        val body = buildString {
            append(HEADER_V2).append('\n')
            receipts.forEach { receipt ->
                append(receipt.testId).append(SEPARATOR)
                append(receipt.sentAtEpochMillis).append(SEPARATOR)
                append(receipt.receivedAtEpochMillis).append(SEPARATOR)
                append(receipt.receivedAtElapsedMillis).append(SEPARATOR)
                append(receipt.messagePriority.name).append(SEPARATOR)
                append(receipt.deviceInteractive.name).append(SEPARATOR)
                append(receipt.deviceIdleMode.name).append(SEPARATOR)
                append(receipt.networkAvailability.name).append(SEPARATOR)
                append(receipt.notificationOutcome.name).append(SEPARATOR)
                append(receipt.handlerDurationMillis ?: "-").append(SEPARATOR)
                append(receipt.processStartedAtElapsedMillis ?: "-").append('\n')
            }
        }
        return body.toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= FcmEvidenceStore.MAX_FILE_BYTES)
        }
    }

    fun decode(bytes: ByteArray): List<FcmReceipt>? {
        if (bytes.isEmpty() || bytes.size > FcmEvidenceStore.MAX_FILE_BYTES) return null
        val text = bytes.toString(StandardCharsets.UTF_8)
        if (!text.endsWith('\n')) return null
        val lines = text.split('\n')
        val fieldCount = when (lines.firstOrNull()) {
            HEADER_V1 -> 10
            HEADER_V2 -> 11
            else -> return null
        }
        if (lines.lastOrNull() != "") return null
        val encodedReceipts = lines.drop(1).dropLast(1)
        if (encodedReceipts.size > FcmEvidenceStore.MAX_RECEIPTS) return null
        val receipts = encodedReceipts.map { decodeReceipt(it, fieldCount) ?: return null }
        if (receipts.map(FcmReceipt::testId).toSet().size != receipts.size) return null
        return receipts
    }

    private fun decodeReceipt(encoded: String, fieldCount: Int): FcmReceipt? {
        val fields = encoded.split(SEPARATOR)
        if (fields.size != fieldCount) return null
        return runCatching {
            FcmReceipt(
                testId = fields[0],
                sentAtEpochMillis = fields[1].toLong(),
                receivedAtEpochMillis = fields[2].toLong(),
                receivedAtElapsedMillis = fields[3].toLong(),
                messagePriority = enumValueOf(fields[4]),
                deviceInteractive = enumValueOf(fields[5]),
                deviceIdleMode = enumValueOf(fields[6]),
                networkAvailability = enumValueOf(fields[7]),
                notificationOutcome = enumValueOf(fields[8]),
                handlerDurationMillis = fields[9].takeUnless { it == "-" }?.toLong(),
                processStartedAtElapsedMillis = fields.getOrNull(10)
                    ?.takeUnless { it == "-" }
                    ?.toLong(),
            )
        }.getOrNull()
    }
}
