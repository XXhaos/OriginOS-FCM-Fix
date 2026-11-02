package io.github.originosfcmguardian.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** This client is used only by explicit update actions, independently of protection workers. */
internal class GitHubUpdateRepository(private val context: Context) {
    private val directory get() = File(context.cacheDir, "updates").apply { mkdirs() }
    private val apk get() = File(directory, "guardian-update.apk")

    fun check(): AvailableAppUpdate? {
        val installed = installedIdentity()
        val release = decodeRelease(readSmall(UPDATE_LATEST_URL, 1024 * 1024))
        require(!release.draft && !release.prerelease) { "Invalid stable release" }
        val latest = requireNotNull(UpdateVersion.parse(release.tag))
        if (latest <= requireNotNull(UpdateVersion.parse(installed.versionName))) return null
        val manifestAsset = releaseAsset(release, "artifact-manifest.json")
        require(manifestAsset.bytes <= 128 * 1024) { "Manifest too large" }
        val manifestBytes = readSmall(manifestAsset.url, 128 * 1024)
        require(manifestBytes.size.toLong() == manifestAsset.bytes) { "Manifest size mismatch" }
        manifestAsset.digest?.let { require(sha256(manifestBytes).equals(it.substringAfter(":"), true)) }
        return qualifyAppUpdate(release, decodeManifest(manifestBytes), installed.versionName, installed.code)
    }

    fun download(update: AvailableAppUpdate, ensureActive: () -> Unit, progress: (Int) -> Unit): File {
        val temporary = File(directory, "guardian-update.part")
        temporary.delete()
        apk.delete()
        try {
            openUpdateConnection(update.asset.url).let { connection ->
                try {
                    require(connection.contentLengthLong == -1L || connection.contentLengthLong == update.asset.bytes)
                    connection.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            transferUpdate(input, update.asset.bytes, ensureActive, progress) { data, count ->
                                output.write(data, 0, count)
                            }
                        }
                    }
                } finally { connection.disconnect() }
            }
            validate(temporary, update)
            require(temporary.renameTo(apk)) { "Cannot prepare update" }
            return apk
        } finally { temporary.delete() }
    }

    /** Recheck after the install-source settings round-trip, including fresh installed version/signers. */
    fun validatedApk(update: AvailableAppUpdate): File = apk.also { validate(it, update) }

    private fun validate(file: File, update: AvailableAppUpdate) {
        require(file.isFile && file.length() == update.asset.bytes)
        require(sha256(file).equals(update.sha256, ignoreCase = true)) { "APK checksum mismatch" }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
        require(validUpdateApk(installedIdentity(), archive.updateIdentity(), update, Build.VERSION.SDK_INT)) {
            "APK identity mismatch"
        }
    }

    @Suppress("DEPRECATION")
    private fun installedIdentity(): UpdateApkIdentity = context.packageManager.getPackageInfo(context.packageName,
        PackageManager.GET_SIGNING_CERTIFICATES).updateIdentity()
}

@Suppress("DEPRECATION")
private fun PackageInfo.updateIdentity(): UpdateApkIdentity {
    val signing = requireNotNull(signingInfo)
    val signers = signing.apkContentsSigners.map { sha256(it.toByteArray()) }.toSet()
    val history = if (signing.hasMultipleSigners()) emptySet() else
        signing.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    val app = requireNotNull(applicationInfo)
    return UpdateApkIdentity(packageName, versionName.orEmpty(), longVersionCode, signers, history,
        app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, app.minSdkVersion)
}

private fun decodeRelease(bytes: ByteArray): UpdateRelease {
    val json = JSONObject(bytes.toString(Charsets.UTF_8))
    val assets = json.getJSONArray("assets")
    require(assets.length() <= 100) { "Too many release assets" }
    return UpdateRelease(json.getString("tag_name"), json.getBoolean("draft"), json.getBoolean("prerelease"),
        (0 until assets.length()).map { index -> assets.getJSONObject(index).let { asset ->
            require(asset.getString("state") == "uploaded") { "Incomplete release" }
            UpdateAsset(asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"),
                if (asset.isNull("digest")) null else asset.getString("digest"))
        } })
}

private fun decodeManifest(bytes: ByteArray): UpdateManifest = JSONObject(bytes.toString(Charsets.UTF_8)).let {
    UpdateManifest(it.getString("file"), it.getString("versionName"), it.getLong("versionCode"),
        it.getString("applicationId"), it.getLong("bytes"), it.getString("sha256"), it.getBoolean("debuggable"))
}

private fun readSmall(url: String, limit: Int): ByteArray {
    val connection = openUpdateConnection(url)
    return try {
        require(connection.contentLengthLong == -1L || connection.contentLengthLong <= limit)
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            transferBounded(input, limit.toLong(), 30_000L, {}, { data, count -> output.write(data, 0, count) })
            output.toByteArray()
        }
    } finally { connection.disconnect() }
}

private fun openUpdateConnection(initialUrl: String): HttpURLConnection {
    var url = initialUrl
    repeat(5) {
        require(trustedUpdateUrl(url)) { "Untrusted update URL" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "OriginOS-FCM-Guardian-updater")
        connection.setRequestProperty("Accept", if (url.startsWith("https://api.github.com/"))
            "application/vnd.github+json" else "application/octet-stream")
        try {
            when (connection.responseCode) {
                200 -> return connection
                301, 302, 303, 307, 308 -> {
                    val redirect = connection.getHeaderField("Location") ?: error("Missing redirect")
                    url = URI(url).resolve(redirect).toString()
                    connection.disconnect()
                }
                else -> error("Update server unavailable")
            }
        } catch (error: Exception) { connection.disconnect(); throw error }
    }
    error("Too many update redirects")
}

internal fun transferUpdate(input: InputStream, expectedBytes: Long, ensureActive: () -> Unit,
    progress: (Int) -> Unit, nowNanos: () -> Long = System::nanoTime, write: (ByteArray, Int) -> Unit) {
    require(expectedBytes in 1..MAX_UPDATE_APK_BYTES)
    var completed = 0L
    var lastProgress = -1
    transferBounded(input, expectedBytes, UPDATE_DOWNLOAD_TIMEOUT_MILLIS, ensureActive, { bytes, count ->
        write(bytes, count)
        completed += count
        val percent = (completed * 100 / expectedBytes).toInt()
        if (percent != lastProgress) { lastProgress = percent; progress(percent) }
    }, nowNanos)
    require(completed == expectedBytes) { "Truncated update" }
}

private fun transferBounded(input: InputStream, maxBytes: Long, timeoutMillis: Long, ensureActive: () -> Unit,
    write: (ByteArray, Int) -> Unit, nowNanos: () -> Long = System::nanoTime) {
    val started = nowNanos()
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        ensureActive()
        require((nowNanos() - started) / 1_000_000 < timeoutMillis) { "Update timed out" }
        val count = input.read(buffer)
        if (count < 0) return
        if (count == 0) continue
        total += count
        require(total <= maxBytes) { "Update too large" }
        write(buffer, count)
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    digest.digest().toHex()
}
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
