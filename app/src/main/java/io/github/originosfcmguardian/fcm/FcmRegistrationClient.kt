package io.github.originosfcmguardian.fcm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import io.github.originosfcmguardian.BuildConfig
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface RegistrationResult {
    class Success(val token: SensitiveToken) : RegistrationResult {
        override fun toString(): String = "RegistrationResult.Success(token=<redacted>)"
    }
    data object ConfigMissing : RegistrationResult
    data object TimedOut : RegistrationResult
    data object SdkIoFailure : RegistrationResult
    data object Failed : RegistrationResult
}

internal class FcmRegistrationClient(context: Context) {
    private val appContext = context.applicationContext

    fun configurationStatus(): FcmConfigurationStatus =
        if (validOptions() != null) {
            FcmConfigurationStatus.CONFIGURED_NOT_TESTED
        } else {
            FcmConfigurationStatus.MISSING
        }

    suspend fun requestToken(): RegistrationResult {
        if (validOptions() == null) return RegistrationResult.ConfigMissing
        val initialized = runCatching { FirebaseApp.getInstance() }.getOrNull()
            ?: runCatching { FirebaseApp.initializeApp(appContext) }.getOrNull()
            ?: return RegistrationResult.ConfigMissing
        if (initialized.options.applicationId.isBlank()) return RegistrationResult.ConfigMissing

        val task = runCatching { FirebaseMessaging.getInstance().token }.getOrElse {
            return sanitizeRegistrationFailure(it)
        }
        return when (val completion = task.awaitSanitized()) {
            is TokenTaskCompletion.Success -> {
                val token = completion.token
                if (token.isBlank() || token.length > MAX_TOKEN_CHARS) RegistrationResult.Failed
                else RegistrationResult.Success(SensitiveToken(token))
            }
            is TokenTaskCompletion.Failure -> sanitizeRegistrationFailure(completion.failure)
        }
    }

    private fun validOptions(): FirebaseOptions? {
        if (!BuildConfig.FIREBASE_CONFIG_INCLUDED) return null
        val options = runCatching { FirebaseOptions.fromResource(appContext) }.getOrNull() ?: return null
        return options.takeIf {
            it.applicationId.isNotBlank() && it.apiKey.isNotBlank() && !it.projectId.isNullOrBlank()
        }
    }

    private companion object {
        const val MAX_TOKEN_CHARS = 8_192
    }
}

internal class SensitiveTokenClipboard(context: Context) {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)

    fun copy(token: SensitiveToken): Boolean = runCatching {
        val clip = ClipData.newPlainText("Guardian FCM test token", token.value)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard?.setPrimaryClip(clip) ?: error("Clipboard unavailable")
    }.isSuccess

    fun clearIfOwned(token: SensitiveToken) {
        runCatching {
            val current = clipboard?.primaryClip
            if (current != null && current.itemCount == 1 && current.getItemAt(0).text?.toString() == token.value) {
                clipboard.clearPrimaryClip()
            }
        }
    }
}

internal fun sanitizeRegistrationFailure(failure: Throwable?): RegistrationResult = when (failure) {
    is IOException -> RegistrationResult.SdkIoFailure
    else -> RegistrationResult.Failed
}

private sealed interface TokenTaskCompletion {
    data class Success(val token: String) : TokenTaskCompletion
    data class Failure(val failure: Throwable?) : TokenTaskCompletion
}

private suspend fun Task<String>.awaitSanitized(): TokenTaskCompletion = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { completed ->
        if (!continuation.isActive) return@addOnCompleteListener
        val token = if (completed.isSuccessful) completed.result else null
        if (token != null) {
            continuation.resume(TokenTaskCompletion.Success(token))
        } else {
            continuation.resume(TokenTaskCompletion.Failure(completed.exception))
        }
    }
}
