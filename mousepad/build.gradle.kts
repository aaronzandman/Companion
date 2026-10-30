plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val mpVersion = "1.0"
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.aaronzandman.mousepad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aaronzandman.mousepad"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "$mpVersion.$buildNumber"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
