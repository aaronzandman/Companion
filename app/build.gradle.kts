plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = "1.11.1"
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.aaronzandman.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aaronzandman.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "$appVersion.$buildNumber"
    }

    signingConfigs {
        create("release") {
            val ks = file("release.jks")
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
            signingConfig = if (file("release.jks").exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
