package io.github.originosfcmguardian.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

class AppUpdatePolicyTest {
    private val checksum = "a".repeat(64)
    private fun asset(name: String = "OriginOS-FCM-Guardian-1.1.0.apk", size: Long = 4096) =
        UpdateAsset(name, "https://github.com/$UPDATE_REPOSITORY/releases/download/v1.1.0/$name", size,
            "sha256:$checksum")
    private fun release(vararg assets: UpdateAsset = arrayOf(asset())) =
        UpdateRelease("v1.1.0", draft = false, prerelease = false, assets = assets.toList())
    private fun manifest() = UpdateManifest(asset().name, "1.1.0", 110, UPDATE_PACKAGE, 4096, checksum, false)
    private fun update() = qualifyAppUpdate(release(), manifest(), "1.0.8", 108)
    private fun installed() = UpdateApkIdentity(UPDATE_PACKAGE, "1.0.8", 108,
        setOf("old"), setOf("old"), false, 30)
    private fun candidate() = UpdateApkIdentity(UPDATE_PACKAGE, "1.1.0", 110,
        setOf("old"), setOf("old"), false, 30)
    private fun rejects(block: () -> Unit) { assertThrows(Exception::class.java, block) }

    @Test fun `numeric version ordering handles two digit minor patch without lexical downgrade`() {
        assertTrue(UpdateVersion.parse("v1.10.0")!! > UpdateVersion.parse("1.9.99")!!)
        assertTrue(UpdateVersion.parse("2.0.0")!! > UpdateVersion.parse("1.999.999")!!)
        assertEquals(UpdateVersion(1, 0, 8), UpdateVersion.parse("1.0.8"))
        listOf("1.0", "1.0.8-test", "v1.0.8\n", "01.0.8", "2147483648.0.0", "latest", "").forEach {
            assertNull(it, UpdateVersion.parse(it))
        }
    }

    @Test fun `published update requires both greater semantic version and higher Android code`() {
        assertEquals(110L, update().code)
        rejects { qualifyAppUpdate(release(), manifest(), "1.1.0", 109) }
        rejects { qualifyAppUpdate(release(), manifest(), "1.2.0", 99) }
        rejects { qualifyAppUpdate(release(), manifest(), "1.0.8", 110) }
        rejects { qualifyAppUpdate(release(), manifest(), "1.0.8", 200) }
    }

    @Test fun `draft prerelease duplicate and foreign asset cannot become an install candidate`() {
        rejects { qualifyAppUpdate(release().copy(draft = true), manifest(), "1.0.8", 108) }
        rejects { qualifyAppUpdate(release().copy(prerelease = true), manifest(), "1.0.8", 108) }
        rejects { qualifyAppUpdate(release(asset(), asset()), manifest(), "1.0.8", 108) }
        rejects { qualifyAppUpdate(release(asset().copy(url = "https://evil.example/update.apk")), manifest(), "1.0.8", 108) }
        rejects { qualifyAppUpdate(release(asset("other.apk")), manifest(), "1.0.8", 108) }
        rejects { qualifyAppUpdate(release().copy(tag = "1.1.0"), manifest(), "1.0.8", 108) }
    }

    @Test fun `manifest and server hash size identity disagreement fails before download`() {
        listOf(manifest().copy(file = "other.apk"), manifest().copy(version = "1.2.0"),
            manifest().copy(applicationId = "other.package"), manifest().copy(debuggable = true),
            manifest().copy(bytes = 4095), manifest().copy(sha256 = "b".repeat(64)),
            manifest().copy(sha256 = "bad")).forEach { bad ->
            rejects { qualifyAppUpdate(release(), bad, "1.0.8", 108) }
        }
        rejects { qualifyAppUpdate(release(asset(size = MAX_UPDATE_APK_BYTES + 1)),
            manifest().copy(bytes = MAX_UPDATE_APK_BYTES + 1), "1.0.8", 108) }
        rejects { qualifyAppUpdate(release(asset().copy(digest = "md5:abc")), manifest(), "1.0.8", 108) }
        // Older GitHub assets may omit API digest; their release manifest checksum remains mandatory.
        assertEquals(checksum, qualifyAppUpdate(release(asset().copy(digest = null)), manifest(), "1.0.8", 108).sha256)
    }

    @Test fun `plaintext deceptive hosts credentials fragments and alternate ports are rejected`() {
        listOf("http://github.com/x", "https://github.com.evil.example/x", "https://github.com@evil.example/x",
            "https://user@github.com/x", "https://github.com:444/x", "https://github.com/x#section",
            "file:///tmp/update.apk", "https://evil.example/x").forEach { assertFalse(it, trustedUpdateUrl(it)) }
        assertTrue(trustedUpdateUrl("https://release-assets.githubusercontent.com/asset?signature=value"))
        assertTrue(trustedUpdateUrl(UPDATE_LATEST_URL))
    }

    @Test fun `fresh archive identity rejects wrong signer unrelated package old code debug and SDK mismatch`() {
        assertTrue(validUpdateApk(installed(), candidate(), update(), 37))
        listOf(candidate().copy(packageName = "other.package"), candidate().copy(code = 108),
            candidate().copy(versionName = "1.1.1"), candidate().copy(signers = setOf("other"), signingHistory = setOf("other")),
            candidate().copy(signers = emptySet()), candidate().copy(signers = setOf("old", "other")),
            candidate().copy(debuggable = true), candidate().copy(minSdk = 38)).forEach {
            assertFalse(validUpdateApk(installed(), it, update(), 37))
        }
        assertFalse(validUpdateApk(installed().copy(code = 110), candidate(), update(), 37))
        assertFalse(validUpdateApk(installed().copy(packageName = "other.package"), candidate(), update(), 37))
    }

    @Test fun `cryptographically parsed signing lineage may rotate while a foreign signer may not`() {
        assertTrue(validUpdateApk(installed(), candidate().copy(signers = setOf("new"),
            signingHistory = setOf("old", "new")), update(), 37))
        assertFalse(validUpdateApk(installed(), candidate().copy(signers = setOf("new"),
            signingHistory = setOf("new")), update(), 37))
    }

    @Test fun `streaming accepts exact size only bounds progress and never writes excess bytes`() {
        val original = ByteArray(100_000) { (it % 255).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        transferUpdate(ByteArrayInputStream(original), original.size.toLong(), {}, progress::add) { bytes, count ->
            output.write(bytes, 0, count)
        }
        assertArrayEquals(original, output.toByteArray())
        assertEquals(100, progress.last())
        assertEquals(progress.distinct(), progress)
        assertTrue(progress.all { it in 0..100 })
        rejects { transferUpdate(ByteArrayInputStream(byteArrayOf(1)), 2, {}, {}) { _, _ -> } }
        var written = 0
        rejects { transferUpdate(ByteArrayInputStream(ByteArray(4)), 3, {}, {}) { _, count -> written += count } }
        assertEquals(0, written)
    }

    @Test fun `cancelled update stops before reading or writing and impossible sizes fail immediately`() {
        var written = false
        assertThrows(CancellationException::class.java) {
            transferUpdate(ByteArrayInputStream(ByteArray(4)), 4, { throw CancellationException() }, {}) { _, _ -> written = true }
        }
        assertFalse(written)
        rejects { transferUpdate(ByteArrayInputStream(ByteArray(0)), 0, {}, {}) { _, _ -> } }
        rejects { transferUpdate(ByteArrayInputStream(ByteArray(0)), MAX_UPDATE_APK_BYTES + 1, {}, {}) { _, _ -> } }
    }

    @Test fun `slow progressing user download may exceed two minutes without being discarded`() {
        var elapsedNanos = 0L
        val output = ByteArrayOutputStream()
        val original = ByteArray(20) { it.toByte() }
        val input = object : ByteArrayInputStream(original) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // One chunk per ten seconds: still below the HTTP read timeout, over two minutes in total.
                val count = super.read(buffer, offset, minOf(length, 1))
                if (count > 0) elapsedNanos += 10_000_000_000L
                return count
            }
        }
        transferUpdate(input, original.size.toLong(), {}, {}, nowNanos = { elapsedNanos }) { bytes, count ->
            output.write(bytes, 0, count)
        }
        assertArrayEquals(original, output.toByteArray())
    }

    @Test fun `slow download remains bounded even if input stream keeps producing data`() {
        var elapsedNanos = 0L
        var writes = 0
        assertThrows(IllegalArgumentException::class.java) {
            transferUpdate(ByteArrayInputStream(byteArrayOf(1, 2)), 2, {}, {}, nowNanos = { elapsedNanos }) { _, _ ->
                writes++
                elapsedNanos += 601_000_000_000L
            }
        }
        assertEquals(1, writes)
    }
}
