// GhostStatSDK-Android 1.1.1 — cookieless GhostStat analytics for Android apps. No dependencies.
// For Android Gradle Plugin 9 and newer: AGP compiles Kotlin itself, so there is no Kotlin plugin here.
// On AGP 8, use agp8/ghoststat-build.gradle.kts instead of this file (see README.md).
plugins {
    id("com.android.library")
}

android {
    namespace = "me.ghoststat.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    // Kotlin targets the same JVM version (AGP 9 takes it from here).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
