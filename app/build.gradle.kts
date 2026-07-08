import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("kapt")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Charts: custom Compose Canvas (Vico 3.x pulls Compose 1.11 / AGP 8.8+, which is
    // too bleeding-edge for a stable build right now; revisit once we're on AGP 9).

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
