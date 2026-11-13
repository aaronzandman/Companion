plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vdVersion = "0.2"
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.aaronzandman.vandash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aaronzandman.vandash"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "$vdVersion.$buildNumber"
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("app/release.jks")
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
            signingConfig = if (rootProject.file("app/release.jks").exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-common:2.6.2")
}
