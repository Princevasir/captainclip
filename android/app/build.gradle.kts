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
    compileSdk = 36

    defaultConfig {
        applicationId = "app.clipbridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "0.7.0"
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

    // standard ("ClipBridge"): no accessibility service and no location, so Play Protect allows browser
    // installs and it can go on Google Play (which only allows accessibility services for disability use).
    // pro ("ClipBridge Pro"): adds automatic copy (accessibility service) and Bluetooth on Android 10-11 (location).
    // Same package and signing key on GitHub: either installs over the other and keeps the pairing.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "AUTO_COPY", "false")
            // No location permission at all: Bluetooth fallback is Android 12+ only (Nearby devices).
            buildConfigField("boolean", "LOCATION_FOR_BLUETOOTH", "false")
        }
        create("pro") {
            dimension = "edition"
            versionNameSuffix = "-pro"
            buildConfigField("boolean", "AUTO_COPY", "true")
            buildConfigField("boolean", "LOCATION_FOR_BLUETOOTH", "true")
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
