plugins {
    // AGP 9 compiles Kotlin itself; do not add org.jetbrains.kotlin.android.
    id("com.android.application")
}

// Set by CI from the release tag, e.g. -Phexboard.version=1.2.3.
val appVersion = (findProperty("hexboard.version") as String?) ?: "0.1.0"

// 1.2.3 becomes 10203, so every release installs over the one before.
fun versionCodeOf(version: String): Int {
    val parts = version.split('.').map { it.toIntOrNull() ?: error("version $version is not X.Y.Z") }
    require(parts.size == 3 && parts.all { it in 0..99 }) { "version $version is not X.Y.Z with parts below 100" }
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}

// The release key stays out of the repository; see scripts/release-key.nu.
// Without it, a release build comes out unsigned.
val releaseKeystore: String? = System.getenv("HEXBOARD_KEYSTORE")

android {
    namespace = "dev.kronberger.hexboard"
    // Must match the platform and build-tools in flake.nix.
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "dev.kronberger.hexboard"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    // Committed so every machine signs alike and installs update in place.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("HEXBOARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HEXBOARD_KEY_ALIAS") ?: "hexboard"
                // PKCS12 keystores share one password for store and key.
                keyPassword = System.getenv("HEXBOARD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // The debug key is public, so a debug build must never be able to
        // install over a release; as its own app it sits beside one instead.
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
