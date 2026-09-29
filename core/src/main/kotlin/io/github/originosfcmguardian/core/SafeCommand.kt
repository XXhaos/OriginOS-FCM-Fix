package io.github.originosfcmguardian.core

const val GMS_PACKAGE_NAME: String = "com.google.android.gms"
const val GUARDIAN_PACKAGE_NAME: String = "io.github.originosfcmguardian"
const val VIVO_AUTOSTART_URI: String =
    "content://com.vivo.permissionmanager.provider.permission/bg_start_up_apps"

enum class AppOp(val shellName: String) {
    RUN_IN_BACKGROUND("RUN_IN_BACKGROUND"),
    RUN_ANY_IN_BACKGROUND("RUN_ANY_IN_BACKGROUND"),
    WAKE_LOCK("WAKE_LOCK"),
}

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

    data object GetGmsEnabledPackage : SafeCommand {
        override val operationId = "get_gms_enabled_package_user_0"
        override fun argv() = listOf(
            "pm", "list", "packages", "--user", "0", "-e", "-U", GMS_PACKAGE_NAME,
        )
    }

    data object GetBuildFingerprint : SafeCommand {
        override val operationId = "get_build_fingerprint"
        override fun argv() = listOf("getprop", "ro.build.fingerprint")
    }

    data class GetGmsAppOpByPackage(val operation: AppOp) : SafeCommand {
        override val operationId = "get_gms_appop_package_${operation.name.lowercase()}"
        override fun argv() = listOf(
            "cmd", "appops", "get", "--user", "0", GMS_PACKAGE_NAME, operation.shellName,
        )
    }

    data class GetAppOpByUid(val uid: Int, val operation: AppOp) : SafeCommand {
        init {
            require(isUserZeroAppId(uid)) { "UID must be a positive user-0 appId below 100000" }
        }

        override val operationId = "get_gms_appop_uid_${operation.name.lowercase()}"
        override fun argv() = listOf(
            "cmd", "appops", "get", "--user", "0", uid.toString(), operation.shellName,
        )
    }

    data object GetDeviceIdleWhitelist : SafeCommand {
        override val operationId = "get_deviceidle_whitelist"
        override fun argv() = listOf("cmd", "deviceidle", "whitelist")
    }

    data object GetVivoPemList : SafeCommand {
        override val operationId = "get_vivo_pem_list"
        override fun argv() = listOf("settings", "get", "global", "vivo_pem_ignore_list")
    }

    data object GetVivoPemPackagePath : SafeCommand {
        override val operationId = "get_vivo_pem_package_path"
        override fun argv() = listOf("pm", "path", "--user", "0", "com.vivo.pem")
    }

    /** Fixed Guardian-only package lookup used by the service as a fresh UID guard. */
    data object GetGuardianPackage : SafeCommand {
        override val operationId = "get_guardian_package_user_0"
        override fun argv() = listOf(
            "pm", "list", "packages", "--user", "0", "-U", GUARDIAN_PACKAGE_NAME,
        )
    }

    /**
     * Fixed, read-only OriginOS provider query for Guardian's own row.
     *
     * The package and selection are deliberately compiled into the operation. This is not a
     * generic content/SQL or package-name IPC surface.
     */
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

    /** Package-typed read only; the service reconstructs the predicate from this field. */
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
            "am_proc_start:I", "am_uid_active:I", "am_uid_idle:I", "*:S",
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
        SafeCommand.GetGmsEnabledPackage.operationId ->
            noArguments(uid, packageName, SafeCommand.GetGmsEnabledPackage)
        SafeCommand.GetBuildFingerprint.operationId ->
            noArguments(uid, packageName, SafeCommand.GetBuildFingerprint)
        SafeCommand.GetDeviceIdleWhitelist.operationId ->
            noArguments(uid, packageName, SafeCommand.GetDeviceIdleWhitelist)
        SafeCommand.GetVivoPemList.operationId -> noArguments(uid, packageName, SafeCommand.GetVivoPemList)
        SafeCommand.GetVivoPemPackagePath.operationId ->
            noArguments(uid, packageName, SafeCommand.GetVivoPemPackagePath)
        SafeCommand.GetGuardianPackage.operationId ->
            noArguments(uid, packageName, SafeCommand.GetGuardianPackage)
        SafeCommand.GetGuardianAutostartRow.operationId ->
            noArguments(uid, packageName, SafeCommand.GetGuardianAutostartRow)
        SafeCommand.GetTargetAutostartRow("example.valid").operationId ->
            targetAutostart(uid, packageName)
        SafeCommand.GetTargetProcessEvents(10_000, "example.valid").operationId ->
            targetProcessEvents(uid, packageName)
        else -> decodeAppOp(operationId, uid, packageName)
    }

    private fun decodeAppOp(operationId: String, uid: Int?, packageName: String?): SafeCommand? {
        if (packageName != null) return null
        AppOp.entries.forEach { operation ->
            val packageCommand = SafeCommand.GetGmsAppOpByPackage(operation)
            if (operationId == packageCommand.operationId) return noUid(uid, packageCommand)

            val uidPrefix = "get_gms_appop_uid_${operation.name.lowercase()}"
            if (operationId == uidPrefix && uid != null && isUserZeroAppId(uid)) {
                return SafeCommand.GetAppOpByUid(uid, operation)
            }
        }
        return null
    }

    private fun targetAutostart(uid: Int?, packageName: String?): SafeCommand? =
        if (uid == null && packageName != null && isSafeAndroidPackageName(packageName)) {
            SafeCommand.GetTargetAutostartRow(packageName)
        } else {
            null
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

    private fun noUid(uid: Int?, command: SafeCommand): SafeCommand? =
        command.takeIf { uid == null }
}
