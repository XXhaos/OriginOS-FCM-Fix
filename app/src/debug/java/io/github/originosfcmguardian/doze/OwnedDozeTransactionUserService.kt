package io.github.originosfcmguardian.doze

import android.content.Context
import androidx.annotation.Keep
import io.github.originosfcmguardian.core.doze.DozeTarget

/** Debug APK only; the reflected Shizuku endpoint fixes the owned test package in code. */
class OwnedDozeTransactionUserService : FixedDozeUserService {
    constructor() : super(DozeTarget.OWNED_DEBUG_TEST)

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super(DozeTarget.OWNED_DEBUG_TEST)
}
