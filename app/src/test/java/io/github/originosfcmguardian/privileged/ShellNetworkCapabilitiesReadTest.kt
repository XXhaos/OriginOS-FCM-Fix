package io.github.originosfcmguardian.privileged

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class ShellNetworkCapabilitiesReadTest {
    class NetworkToken
    interface LegacyApi {
        fun getNetworkCapabilities(network: NetworkToken, packageName: String): Any?
    }
    interface AttributedApi : LegacyApi {
        fun getNetworkCapabilities(network: NetworkToken, packageName: String, attributionTag: String?): Any?
    }
    private class Legacy : LegacyApi {
        var calls = 0
        var network: NetworkToken? = null
        var packageName: String? = null
        override fun getNetworkCapabilities(network: NetworkToken, packageName: String): Any {
            calls++; this.network = network; this.packageName = packageName
            return "legacy"
        }
    }
    private class Attributed : AttributedApi {
        var legacyCalls = 0
        var attributedCalls = 0
        var network: NetworkToken? = null
        var packageName: String? = null
        var attributionTag: String? = "not-called"
        var failure: Exception? = null
        var result: Any? = "attributed"
        override fun getNetworkCapabilities(network: NetworkToken, packageName: String): Any {
            legacyCalls++
            return "legacy"
        }
        override fun getNetworkCapabilities(network: NetworkToken, packageName: String, attributionTag: String?): Any? {
            attributedCalls++; this.network = network; this.packageName = packageName; this.attributionTag = attributionTag
            failure?.let { throw it }
            return result
        }
    }
    private fun read(api: Class<*>, remote: Any, network: NetworkToken) =
        invokeShellNetworkCapabilities(api, remote, NetworkToken::class.java, network)

    @Test fun attributedSignatureIsPreferredAndKeepsExactShellIdentityAndNetwork() {
        val remote = Attributed(); val network = NetworkToken()
        assertEquals("attributed", read(AttributedApi::class.java, remote, network))
        assertEquals(1, remote.attributedCalls)
        assertEquals(0, remote.legacyCalls)
        assertSame(network, remote.network)
        assertEquals("com.android.shell", remote.packageName)
        assertNull(remote.attributionTag)
    }

    @Test fun onlyAnAbsentAttributedSignatureSelectsLegacyRead() {
        val remote = Legacy(); val network = NetworkToken()
        assertEquals("legacy", read(LegacyApi::class.java, remote, network))
        assertEquals(1, remote.calls)
        assertSame(network, remote.network)
        assertEquals("com.android.shell", remote.packageName)
    }

    @Test fun securityFailureNeverFallsBackToLegacySignature() {
        val remote = Attributed().apply { failure = SecurityException("denied") }
        try { read(AttributedApi::class.java, remote, NetworkToken()); fail("Expected denial") }
        catch (failure: InvocationTargetException) { assertTrue(failure.cause is SecurityException) }
        assertEquals(1, remote.attributedCalls)
        assertEquals(0, remote.legacyCalls)
    }

    @Test fun invokedMethodThrowingNoSuchMethodDoesNotSelectAnotherSignature() {
        val remote = Attributed().apply { failure = NoSuchMethodException("backend failure") }
        try { read(AttributedApi::class.java, remote, NetworkToken()); fail("Expected invocation failure") }
        catch (failure: InvocationTargetException) { assertTrue(failure.cause is NoSuchMethodException) }
        assertEquals(0, remote.legacyCalls)
    }

    @Test fun nullCapabilitiesRemainUnknownWithoutLegacyFallback() {
        val remote = Attributed().apply { result = null }
        assertNull(read(AttributedApi::class.java, remote, NetworkToken()))
        assertEquals(1, remote.attributedCalls)
        assertEquals(0, remote.legacyCalls)
    }

    @Test fun unsupportedSignatureDoesNotUseOtherNetworkShapes() {
        val remote = Legacy()
        try {
            invokeShellNetworkCapabilities(LegacyApi::class.java, remote, Any::class.java, NetworkToken())
            fail("Expected exact-signature lookup failure")
        } catch (_: NoSuchMethodException) { }
        assertEquals(0, remote.calls)
    }
}
