plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val hasGoogleServicesConfig = file("google-services.json").isFile
if (hasGoogleServicesConfig) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "io.github.originosfcmguardian"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.originosfcmguardian"
        minSdk = 30
        targetSdk = 37
        versionCode = 58
        versionName = "0.1.0-gms-check-preview"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "FIREBASE_CONFIG_INCLUDED", hasGoogleServicesConfig.toString())
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Optional project-specific copy of the existing test-device debug key when changing computers.
    providers.environmentVariable("GUARDIAN_DEBUG_KEYSTORE").orNull?.let { keyPath ->
        val keyFile = rootProject.file(keyPath)
        require(keyFile.isFile) { "GUARDIAN_DEBUG_KEYSTORE must name an existing private debug keystore" }
        signingConfigs.getByName("debug").storeFile = keyFile
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Shizuku's APK-only class loader needs a verified extracted library for System.load.
        // This costs installed disk space and APK compression; it grants no execution entry point.
        jniLibs.useLegacyPackaging = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // Retain explicit redistribution license texts in both APK variants.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("licenses"))
}

dependencies {
    implementation(project(":core"))

    constraints {
        implementation("androidx.fragment:fragment:1.9.1") {
            because("Firebase Play services brings Fragment 1.1.0, incompatible with ActivityResult permission APIs")
        }
    }

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
