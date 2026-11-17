plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.mohnen.prodvxled"
    compileSdk = 34

    signingConfigs {
        // committed debug keystore -> all builds share one signature so
        // `adb install -r` updates work regardless of which machine built the APK
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "net.mohnen.prodvxled"
        minSdk = 28          // Android 9
        targetSdk = 28       // Android 9 (sideloaded, so no Play target-SDK requirement)
        versionCode = 1
        versionName = "1.0"
    }

    applicationVariants.all {
        outputs.all {
            // outputFileName setter lives on the internal impl class
            if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                outputFileName = "WLED-ProDVX.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
}
