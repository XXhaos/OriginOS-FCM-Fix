package io.github.originosfcmguardian.activation

import android.net.ssl.SSLSockets
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Exact static descriptor consumed by the pinned build-time callsite redirect. No provider swap. */
@Keep
object PairingTlsExporterBridge {
    @JvmStatic fun exportKeyingMaterial(socket: SSLSocket, label: String, context: ByteArray?, length: Int): ByteArray =
        exportPairingTlsMaterial(Build.VERSION.SDK_INT, socket, label, context, length) { originalSocket, originalLabel, originalContext, originalLength ->
            // The host-tested policy rejects API 30 before this callback; keep a local SDK guard
            // too so lint/platform verification can prove no newer API is reached below 31.
            if (Build.VERSION.SDK_INT >= 31)
                PublicExporter.export(originalSocket, originalLabel, originalContext, originalLength)
            else null
        }

    @RequiresApi(31)
    private object PublicExporter {
        fun export(socket: SSLSocket, label: String, context: ByteArray?, length: Int): ByteArray? =
            SSLSockets.exportKeyingMaterial(socket, label, context, length)
    }
}

/** Host-test seam used by the real bridge; never stores or logs exporter material. */
internal fun exportPairingTlsMaterial(sdk: Int, socket: SSLSocket, label: String, context: ByteArray?, length: Int,
    exporter: (SSLSocket, String, ByteArray?, Int) -> ByteArray?): ByteArray {
    if (sdk < 31) throw NoSuchMethodError("Public pairing TLS exporter requires API 31")
    return exporter(socket, label, context, length) ?: throw SSLException("Pairing TLS exporter unavailable")
}
