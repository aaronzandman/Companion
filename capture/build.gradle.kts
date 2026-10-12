plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = "0.1.0"
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.aaronzandman.portalcapture"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.aaronzandman.portalcapture"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "$appVersion.$buildNumber"
    }
    signingConfigs {
        create("release") {
            val ks = file("../app/release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "companion"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (file("../app/release.jks").exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
