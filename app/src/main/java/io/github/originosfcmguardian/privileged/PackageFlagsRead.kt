package io.github.originosfcmguardian.privileged

/** Android changed PM flags from int to long. Resolve the actual read interface at runtime. */
internal fun invokePackageFlagsRead(api: Class<*>, remote: Any, name: String,
    prefixTypes: Array<Class<*>>, prefixArguments: Array<Any?>, flags: Long, userId: Int): Any? {
    require(prefixTypes.size == prefixArguments.size && flags >= 0 && userId == 0)
    val longMethod = runCatching {
        api.getMethod(name, *prefixTypes, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }.getOrNull()
    if (longMethod != null) return longMethod.invoke(remote, *prefixArguments, flags, userId)
    require(flags <= Int.MAX_VALUE)
    return api.getMethod(name, *prefixTypes, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .invoke(remote, *prefixArguments, flags.toInt(), userId)
}
