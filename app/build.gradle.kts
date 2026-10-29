plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.originosfcmguardian"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.originosfcmguardian"
        minSdk = 30
        targetSdk = 37
        versionCode = 99
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Retain explicit redistribution license texts in both APK variants.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("licenses"))

    packaging.resources.merges += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
}

// Explicit maintenance task; normal source builds need no signing key or notice-generation tools.
// tools/generate_dependency_notices.py copies upstream notices into independently named assets.
tasks.register("exportReleaseDependencies") {
    val destination = layout.buildDirectory.file("reports/dependencies/release-artifacts.json")
    outputs.file(destination)
    outputs.upToDateWhen { false }
    doLast {
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.id.componentIdentifier !is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
            .distinctBy { "${it.moduleVersion.id}:${it.file.name}" }
            .sortedBy { it.moduleVersion.id.toString() }
            .map { artifact ->
                val id = artifact.moduleVersion.id
                val coordinate = "${id.group}:${id.name}:${id.version}"
                val cachedPoms = gradle.gradleUserHomeDir.resolve("caches/modules-2/files-2.1/${id.group}/${id.name}/${id.version}")
                    .walkTopDown().filter { it.isFile && it.name == "${id.name}-${id.version}.pom" }.toList()
                val pom = cachedPoms.singleOrNull() ?: configurations.detachedConfiguration(
                    dependencies.create("$coordinate@pom")
                ).apply { isTransitive = false }.singleFile
                mapOf("coordinate" to coordinate, "artifact" to artifact.file.absolutePath, "pom" to pom.absolutePath)
            }
        val output = destination.get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(artifacts)) + "\n")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

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
