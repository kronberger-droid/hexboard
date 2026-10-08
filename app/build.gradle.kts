plugins {
    // AGP 9 compiles Kotlin itself; do not add org.jetbrains.kotlin.android.
    id("com.android.application")
}

android {
    namespace = "dev.kronberger.hexboard"
    // Must match the platform and build-tools in flake.nix.
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "dev.kronberger.hexboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // Committed so every machine signs alike and installs update in place.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
