package io.github.originosfcmguardian.activation

import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class PairingTlsExporterBridgeTest {
    private fun socket() = SSLSocketFactory.getDefault().createSocket() as SSLSocket // Unconnected; no network.

    @Test fun forwardsOriginalArgumentsAndReturnsSameMaterialWithoutCopies() {
        socket().use { socket ->
            val context = byteArrayOf(1, 2)
            val exported = ByteArray(64)
            val label = "synthetic-label\u0000"
            val actual = exportPairingTlsMaterial(31, socket, label, context, 64) { originalSocket, originalLabel, originalContext, length ->
                assertSame(socket, originalSocket)
                assertSame(label, originalLabel)
                assertSame(context, originalContext)
                assertEquals(64, length)
                exported
            }
            assertSame(exported, actual)
            exportPairingTlsMaterial(37, socket, label, null, 64) { _, _, originalContext, _ ->
                assertNull(originalContext) // Null context must not become empty context.
                exported
            }
        }
    }

    @Test fun api30IsExplicitlyUnsupportedAndCannotInvokeExporter() {
        socket().use { socket ->
            var invoked = false
            val failure = assertThrows(NoSuchMethodError::class.java) {
                exportPairingTlsMaterial(30, socket, "synthetic", null, 64) { _, _, _, _ -> invoked = true; ByteArray(64) }
            }
            assertFalse(invoked)
            assertEquals(ActivationFailureKind.INCOMPATIBLE, activationDiagnostic(ActivationStage.PAIRING_REQUEST, failure).kind)
        }
    }

    @Test fun nullMaterialFailsTlsAndExporterFailureIsNotSwallowedOrRetried() {
        socket().use { socket ->
            val failure = assertThrows(SSLException::class.java) {
                exportPairingTlsMaterial(31, socket, "synthetic", null, 64) { _, _, _, _ -> null }
            }
            assertEquals(ActivationFailureKind.TLS, activationDiagnostic(ActivationStage.PAIRING_REQUEST, failure).kind)
            var calls = 0
            val original = SSLException("synthetic failure")
            assertSame(original, assertThrows(SSLException::class.java) {
                exportPairingTlsMaterial(31, socket, "synthetic", null, 64) { _, _, _, _ -> calls++; throw original }
            })
            assertEquals(1, calls)
        }
    }
}
