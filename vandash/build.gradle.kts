plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vdVersion = "0.1"
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
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // SmartDeviceLink = the open-source library behind Ford SYNC AppLink.
    implementation("com.smartdevicelink:sdl_android:5.7.0")
}
