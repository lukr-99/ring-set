import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ring BLE address is NOT stored in the repo. It is resolved at build time from:
//   1. the RING_MAC environment variable, or
//   2. `ring.mac=...` in local.properties (git-ignored),
// falling back to a harmless placeholder so the project still builds without it.
val ringMac: String = run {
    System.getenv("RING_MAC")?.takeIf { it.isNotBlank() }?.let { return@run it }
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val props = Properties().apply { lp.inputStream().use { load(it) } }
        props.getProperty("ring.mac")?.takeIf { it.isNotBlank() }?.let { return@run it }
    }
    "00:00:00:00:00:00"
}

android {
    namespace = "com.krejci.qringset"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.krejci.qringset"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "RING_MAC", "\"$ringMac\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
