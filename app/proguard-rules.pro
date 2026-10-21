# External Shizuku UserService entry points retain their existing @Keep annotations.
# No app-owned runtime, hidden TLS exporter or pairing exceptions are packaged.
# Fixed shell child entry point is loaded by app_process, never by manifest or arbitrary IPC.
-keep class io.github.originosfcmguardian.recovery.PackageStoppedStateMain {
    public static void main(java.lang.String[]);
}
