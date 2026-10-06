// Isolated compile-only evaluation. Not included in Guardian or installed on a phone.
// Optional second probe; the default still reproduces Guardian's current Kotlin toolchain.
buildscript {
    val probeKotlin = providers.gradleProperty("probeKotlinVersion").orNull
    if (probeKotlin != null) {
        repositories { mavenCentral() }
        dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$probeKotlin") }
    }
}
plugins { id("com.android.library") version "9.1.1" }

android {
    namespace = "io.github.originosfcmguardian.compatprobe"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation("io.github.priv-kit:priv-core:0.16.1") }
