package io.github.originosfcmguardian.core

const val GMS_PACKAGE_NAME: String = "com.google.android.gms"
const val GUARDIAN_PACKAGE_NAME: String = "io.github.originosfcmguardian"
const val VIVO_AUTOSTART_URI: String =
    "content://com.vivo.permissionmanager.provider.permission/bg_start_up_apps"

/**
 * Closed set of read-only commands accepted by the privileged bridge.
 *
 * The app must send [operationId] plus the one explicitly supported integer
 * parameter over IPC. The service reconstructs the command through
 * [SafeCommandFactory] and executes [argv] directly without a shell.
 */
sealed interface SafeCommand {
    val operationId: String

    fun argv(): List<String>

    data object GetCurrentUser : SafeCommand {
        override val operationId = "get_current_user"
        override fun argv() = listOf("am", "get-current-user")
    }

    data object GetCallingUid : SafeCommand {
        override val operationId = "get_calling_uid"
        override fun argv() = listOf("id", "-u")
    }

    data object GetGmsPackage : SafeCommand {
        override val operationId = "get_gms_package_user_0"
        override fun argv() = listOf(
            "pm", "list", "packages", "--user", "0", "-U", GMS_PACKAGE_NAME,
        )
    }

    data object GetBuildFingerprint : SafeCommand {
        override val operationId = "get_build_fingerprint"
        override fun argv() = listOf("getprop", "ro.build.fingerprint")
    }

    /** Fixed Guardian-only package lookup used by the service as a fresh UID guard. */
    data object GetGuardianPackage : SafeCommand {
        override val operationId = "get_guardian_package_user_0"
        override fun argv() = listOf(
            "pm", "list", "packages", "--user", "0", "-U", GUARDIAN_PACKAGE_NAME,
        )
    }

    /** Historical fixture/parser descriptor only; retired from the IPC execution allowlist. */
    data object GetGuardianAutostartRow : SafeCommand {
        override val operationId = "get_guardian_vivo_autostart_row_user_0"
        override fun argv() = listOf(
            "content",
            "query",
            "--user",
            "0",
            "--uri",
            VIVO_AUTOSTART_URI,
            "--where",
            "pkgname='$GUARDIAN_PACKAGE_NAME'",
        )
    }

    /** Fixed user-0 package lookup used only inside the service's target identity guard. */
    data class GetTargetPackage(val packageName: String) : SafeCommand {
        init {
            require(isSafeAndroidPackageName(packageName)) { "Invalid package name" }
        }

        override val operationId = "get_selected_target_package_user_0"
        override fun argv() = listOf(
            "pm", "list", "packages", "--user", "0", "-U", packageName,
        )

        /** Native cmd entry point avoids an intermediary pm shell script in timed trace probes. */
        fun uidOwnersArgv(uid: Int): List<String> {
            require(isUserZeroTargetAppUid(uid)) { "Invalid target UID" }
            return listOf(
                "cmd", "package", "list", "packages", "--user", "0", "--uid", uid.toString(), "-U",
            )
        }
    }

    /** Historical fixture/parser descriptor only; retired from the IPC execution allowlist. */
    data class GetTargetAutostartRow(val packageName: String) : SafeCommand {
        init {
            require(isSafeAndroidPackageName(packageName)) { "Invalid package name" }
        }

        override val operationId = "get_selected_target_vivo_autostart_row_user_0"
        override fun argv() = listOf(
            "content",
            "query",
            "--user",
            "0",
            "--uri",
            VIVO_AUTOSTART_URI,
            "--where",
            "pkgname='$packageName'",
        )
    }

    /** Bounded events-buffer snapshot for one freshly validated user-0 target. */
    data class GetTargetProcessEvents(val uid: Int, val packageName: String) : SafeCommand {
        init {
            require(isUserZeroTargetAppUid(uid)) { "Invalid target UID" }
            require(isSafeAndroidPackageName(packageName)) { "Invalid package name" }
        }

        override val operationId = "get_selected_target_process_events_user_0"
        override fun argv() = listOf(
            "logcat", "-b", "events", "-d", "-t", "256", "-v", "epoch",
            "am_proc_start:I", "am_uid_active:I", "am_uid_idle:I",
            "am_uid_running:I", "am_uid_stopped:I", "*:S",
        )
    }
}

fun isUserZeroAppId(uid: Int): Boolean = uid in 1 until 100_000

/** Ordinary user-0 app UID range used for user-selected targets. */
fun isUserZeroTargetAppUid(uid: Int): Boolean = uid in 10_000 until 100_000

private val SAFE_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

fun isSafeAndroidPackageName(packageName: String): Boolean =
    packageName.length in 3..255 && SAFE_PACKAGE_NAME.matches(packageName)

/** Service-side decoder. Unknown IDs and unexpected parameters are rejected. */
object SafeCommandFactory {
    fun fromIpc(
        operationId: String,
        uid: Int? = null,
        packageName: String? = null,
    ): SafeCommand? = when (operationId) {
        SafeCommand.GetCurrentUser.operationId -> noArguments(uid, packageName, SafeCommand.GetCurrentUser)
        SafeCommand.GetCallingUid.operationId -> noArguments(uid, packageName, SafeCommand.GetCallingUid)
        SafeCommand.GetGmsPackage.operationId -> noArguments(uid, packageName, SafeCommand.GetGmsPackage)
        SafeCommand.GetBuildFingerprint.operationId ->
            noArguments(uid, packageName, SafeCommand.GetBuildFingerprint)
        SafeCommand.GetGuardianPackage.operationId ->
            noArguments(uid, packageName, SafeCommand.GetGuardianPackage)
        SafeCommand.GetTargetProcessEvents(10_000, "example.valid").operationId ->
            targetProcessEvents(uid, packageName)
        else -> null
    }

    private fun targetProcessEvents(uid: Int?, packageName: String?): SafeCommand? =
        if (uid != null && isUserZeroTargetAppUid(uid) &&
            packageName != null && isSafeAndroidPackageName(packageName)
        ) {
            SafeCommand.GetTargetProcessEvents(uid, packageName)
        } else {
            null
        }

    private fun noArguments(
        uid: Int?,
        packageName: String?,
        command: SafeCommand,
    ): SafeCommand? = command.takeIf { uid == null && packageName == null }


}
