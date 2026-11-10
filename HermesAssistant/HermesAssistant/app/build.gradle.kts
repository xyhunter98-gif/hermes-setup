import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Reads PICOVOICE_ACCESS_KEY from local.properties, which Android Studio
// creates automatically and is already .gitignored by default — so your
// key never gets committed or shared, only ever lives on your own machine.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}
val picovoiceAccessKey: String = localProps.getProperty("PICOVOICE_ACCESS_KEY", "")
val openRouterApiKey: String = localProps.getProperty("OPENROUTER_API_KEY", "")

android {
    namespace = "com.hermes.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermes.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"$picovoiceAccessKey\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openRouterApiKey\"")
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
    implementation("androidx.lifecycle:lifecycle-service:2.8.1")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Porcupine: real low-power, on-device wake-word detection
    implementation("ai.picovoice:porcupine-android:3.0.2")

    // Networking + coroutines for the OpenRouter fallback intent parser
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
