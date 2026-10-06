plugins { `java-library` }

repositories { google(); mavenCentral() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    compileOnly(gradleApi())
    // API interfaces only: do not pull AGP's implementation/plugin into buildSrc's parent loader.
    implementation("com.android.tools.build:gradle-api:9.1.1") {
        exclude(group = "com.android.tools.build", module = "gradle")
    }
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    implementation("org.ow2.asm:asm:9.9.1")
    testImplementation("junit:junit:4.13.2")
}
