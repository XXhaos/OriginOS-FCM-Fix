package io.github.originosfcmguardian.privileged

/** Exact read-only platform signatures; only missing attribution support selects the older API. */
internal fun invokeShellNetworkCapabilities(api: Class<*>, remote: Any, networkType: Class<*>, network: Any): Any? {
    val attributed = try {
        api.getMethod("getNetworkCapabilities", networkType, String::class.java, String::class.java)
    } catch (_: NoSuchMethodException) { null }
    return if (attributed != null) attributed.invoke(remote, network, "com.android.shell", null)
    else api.getMethod("getNetworkCapabilities", networkType, String::class.java)
        .invoke(remote, network, "com.android.shell")
}
