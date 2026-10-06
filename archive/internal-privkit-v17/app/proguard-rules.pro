# Dedicated privileged service entry points use kept constructors/entry types in source.
# Dependency reflection rules are added only for verified bootstrap entry points.

# Priv Kit links to hidden Android framework interfaces supplied by the device,
# absent from the public android.jar. Keep this exact R8-reported list bounded.
# These rules allow packaging; runtime API compatibility still requires device verification.
-dontwarn android.app.ActivityThread
-dontwarn android.app.ContentProviderHolder
-dontwarn android.app.ContextImpl
-dontwarn android.app.IActivityManager$Stub
-dontwarn android.app.IActivityManager
-dontwarn android.app.LoadedApk
-dontwarn android.content.IContentProvider
-dontwarn android.content.pm.IPackageManager$Stub
-dontwarn android.content.pm.IPackageManager
-dontwarn android.content.res.CompatibilityInfo
-dontwarn android.os.SELinux
-dontwarn android.os.ServiceManager
-dontwarn android.permission.IPermissionManager$Stub
-dontwarn android.permission.IPermissionManager
# Priv Kit's ADB TLS pairing uses Android's platform Conscrypt exportKeyingMaterial.
# This hidden platform class is not in public android.jar; do not package a replacement.
# Narrow packaging allowance only: actual pairing compatibility remains a device gate.
-dontwarn com.android.org.conscrypt.Conscrypt
