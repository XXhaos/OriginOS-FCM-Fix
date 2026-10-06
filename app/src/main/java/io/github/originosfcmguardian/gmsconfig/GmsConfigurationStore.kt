package io.github.originosfcmguardian.gmsconfig

import android.content.Context
import android.content.SharedPreferences
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.EvidenceStatus
import java.io.*
import java.util.Base64

class GmsConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("gms_configuration_read_only_v1", Context.MODE_PRIVATE)
    fun save(value: GmsConfigSnapshot) { check(preferences.edit().putString("snapshot", GmsConfigurationCodec.encode(value)).commit()) }
    fun load(): GmsConfigSnapshot? = preferences.getString("snapshot", null)?.let {
        GmsConfigurationCodec.decode(it) ?: error("GMS configuration snapshot is damaged")
    }
    fun registerListener(listener: () -> Unit): AutoCloseable {
        val subscription = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "snapshot") listener() }
        preferences.registerOnSharedPreferenceChangeListener(subscription)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(subscription) }
    }
    internal fun saveAttempt(boot: Int?, elapsed: Long) {
        check(preferences.edit().putInt("attempt_boot", boot ?: -1).putLong("attempt_elapsed", elapsed).commit())
    }
    internal fun lastAttempt(boot: Int?): Long? = preferences.getLong("attempt_elapsed", -1).takeIf {
        boot != null && preferences.getInt("attempt_boot", -1) == boot && it >= 0
    }
}

/** One bounded blob, fixed settings, no raw dumps or authority/journal fields. */
internal object GmsConfigurationCodec {
    private const val MAX_CHARS = 4096
    fun encode(value: GmsConfigSnapshot): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeUTF(value.context.fingerprint); out.writeInt(value.context.userId); out.writeInt(value.context.bootCount ?: -1)
            val identity = value.context.identity
            out.writeBoolean(identity != null)
            if (identity != null) {
                val pkg = identity.packageIdentity
                out.writeUTF(pkg.packageName); out.writeInt(pkg.uid); out.writeLong(pkg.firstInstallTime)
                out.writeInt(pkg.signerSha256.size); pkg.signerSha256.forEach(out::writeUTF)
                out.writeLong(identity.versionCode); out.writeLong(identity.lastUpdateTime); out.writeBoolean(identity.enabled)
            }
            out.writeLong(value.checkedAtMillis); out.writeLong(value.checkedAtElapsed)
            value.settings.forEach { out.writeUTF(it.name) }; out.writeUTF(value.failure.name)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray()).also { require(it.length <= MAX_CHARS) }
    }
    fun decode(raw: String): GmsConfigSnapshot? {
        if (raw.length > MAX_CHARS) return null
        return runCatching { DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(raw))).use { input ->
            require(input.readInt() == 1)
            val fingerprint = input.readUTF(); val user = input.readInt(); val boot = input.readInt().also { require(it >= -1) }.takeIf { it >= 0 }
            val identity = if (input.readBoolean()) {
                val pkg = input.readUTF(); val uid = input.readInt(); val install = input.readLong()
                val count = input.readInt().also { require(it in 1..8) }
                GmsConfigIdentity(SelectedTargetIdentity(pkg, uid, install, List(count) { input.readUTF() }), input.readLong(), input.readLong(), input.readBoolean())
            } else null
            val wall = input.readLong(); val elapsed = input.readLong()
            val statuses = List(5) { enumValueOf<EvidenceStatus>(input.readUTF()) }
            val failure = enumValueOf<GmsConfigFailure>(input.readUTF())
            require(input.available() == 0)
            GmsConfigSnapshot(GmsConfigContext(fingerprint, user, boot, identity), wall, elapsed,
                statuses[0], statuses[1], statuses[2], statuses[3], statuses[4], failure)
        } }.getOrNull()
    }
}
