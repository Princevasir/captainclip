import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key lives OUTSIDE the repo (never committed). Path can be overridden with
// -Pclipbridge.signing=/path/keystore.properties. Without it, release builds are unsigned.
val signingFile = file(
    (project.findProperty("clipbridge.signing") as String?)
        ?: "${System.getProperty("user.home")}/.config/clipbridge-release/keystore.properties"
)
val signingProps = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "app.clipbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clipbridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures { buildConfig = true }

    // full: automatic copy (accessibility service). lite: no accessibility service, so Play Protect
    // doesn't block installs from the browser; phone → Mac uses the Send to Mac tile / Share / in-app button.
    // Same package and signing key: either installs over the other and keeps the pairing.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "AUTO_COPY", "true")
            buildConfigField("boolean", "LOCATION_FOR_BLUETOOTH", "true")
        }
        create("lite") {
            dimension = "edition"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "AUTO_COPY", "false")
            // No location permission at all: Bluetooth fallback is Android 12+ only (Nearby devices).
            buildConfigField("boolean", "LOCATION_FOR_BLUETOOTH", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
