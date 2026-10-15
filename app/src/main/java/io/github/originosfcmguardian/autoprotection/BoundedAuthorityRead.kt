package io.github.originosfcmguardian.autoprotection

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Read the opened authority stream afresh; file size/path metadata is never an admission gate. */
internal fun readBoundedAuthorityBytes(input: InputStream, maximum: Int): ByteArray {
    require(maximum >= 0)
    val chunk = ByteArray(8192)
    val output = ByteArrayOutputStream(minOf(maximum, chunk.size))
    var size = 0
    while (true) {
        // At the bound, one extra byte distinguishes exact-length EOF from overflow.
        val requested = if (size == maximum) 1 else minOf(chunk.size, maximum - size)
        val count = input.read(chunk, 0, requested)
        if (count < 0) return output.toByteArray()
        check(count > 0 && count <= requested) { "Authority read made no valid progress" }
        check(count <= maximum - size) { "Authority exceeds storage bound" }
        output.write(chunk, 0, count)
        size += count
    }
}
