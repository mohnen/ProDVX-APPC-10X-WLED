plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.prodvxled"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.prodvxled"
        minSdk = 28          // Android 9
        targetSdk = 28       // Android 9 (sideloaded, so no Play target-SDK requirement)
        versionCode = 1
        versionName = "1.0"
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
