plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.vladimir.c12gimbal"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.vladimir.c12gimbal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
