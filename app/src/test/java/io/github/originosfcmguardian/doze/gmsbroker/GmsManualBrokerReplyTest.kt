package io.github.originosfcmguardian.doze.gmsbroker

import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure map/typing host tests. These fixtures are not authenticated Android or Binder evidence. */
class GmsManualBrokerReplyTest {
    private fun base(code: String) = mapOf<String, Any?>("schema" to 2, "nonce" to "test_nonce", "code" to code)

    @Test fun `base reply rejects unknown fields schemas codes and nonce correlation`() {
        GmsManualBrokerReply.validate(base("ACK"), "test_nonce", "ACK")
        for (invalid in listOf(base("ACK") + ("schema" to 1), base("ACK") + ("schema" to 2L),
            base("ACK") + ("nonce" to "other_nonce"), base("REJECTED"), base("ACK") + ("unexpected" to true),
            base("ACK") - "schema", base("ACK") + ("nonce" to null))) {
            assertThrows(IllegalStateException::class.java) { GmsManualBrokerReply.validate(invalid, "test_nonce", "ACK") }
        }
    }

    @Test fun `revoke and release require actual typed long string and boolean fields`() {
        val revokeTypes = mapOf("generation" to Long::class.javaObjectType, "revokeVersion" to Long::class.javaObjectType)
        val ack = base("ACK") + mapOf("generation" to 1L, "revokeVersion" to 1L)
        GmsManualBrokerReply.validate(ack, "test_nonce", "ACK", revokeTypes)
        for (invalid in listOf(ack + ("generation" to 1), ack + ("revokeVersion" to "1"), ack - "generation")) {
            assertThrows(IllegalStateException::class.java) { GmsManualBrokerReply.validate(invalid, "test_nonce", "ACK", revokeTypes) }
        }
        val releaseTypes = mapOf("grantId" to String::class.java, "generation" to Long::class.javaObjectType,
            "preparationRevision" to Long::class.javaObjectType, "release" to Boolean::class.javaObjectType)
        val release = base("RELEASED") + mapOf("grantId" to "test_grant", "generation" to 1L,
            "preparationRevision" to 3L, "release" to true)
        GmsManualBrokerReply.validate(release, "test_nonce", "RELEASED", releaseTypes)
        for (invalid in listOf(release + ("release" to 1), release - "release", release + ("grantId" to byteArrayOf(1)))) {
            assertThrows(IllegalStateException::class.java) { GmsManualBrokerReply.validate(invalid, "test_nonce", "RELEASED", releaseTypes) }
        }
    }

    @Test fun `cleanup missing facts and wrong proof payload types never become default evidence`() {
        val types = mapOf("preparedBytes" to ByteArray::class.java, "observation" to ByteArray::class.java,
            "childExited" to Boolean::class.javaObjectType, "stdoutEofAndDrained" to Boolean::class.javaObjectType,
            "stderrEofAndDrained" to Boolean::class.javaObjectType, "collectorsTerminated" to Boolean::class.javaObjectType,
            "handlesClosed" to Boolean::class.javaObjectType, "outputValid" to Boolean::class.javaObjectType)
        val cleanup = base("CLEANUP") + mapOf("preparedBytes" to byteArrayOf(1), "observation" to byteArrayOf(2),
            "childExited" to true, "stdoutEofAndDrained" to true, "stderrEofAndDrained" to true,
            "collectorsTerminated" to true, "handlesClosed" to true, "outputValid" to true)
        GmsManualBrokerReply.validate(cleanup, "test_nonce", "CLEANUP", types)
        for (field in types.keys) {
            assertThrows(IllegalStateException::class.java) { GmsManualBrokerReply.validate(cleanup - field, "test_nonce", "CLEANUP", types) }
        }
        assertThrows(IllegalStateException::class.java) {
            GmsManualBrokerReply.validate(cleanup + ("preparedBytes" to "bytes"), "test_nonce", "CLEANUP", types)
        }
        GmsManualBrokerReply.validate(cleanup + ("exitCode" to 0), "test_nonce", "CLEANUP", types + ("exitCode" to Int::class.javaObjectType))
        assertThrows(IllegalStateException::class.java) {
            GmsManualBrokerReply.validate(cleanup + ("exitCode" to 0L), "test_nonce", "CLEANUP", types + ("exitCode" to Int::class.javaObjectType))
        }
    }
}
