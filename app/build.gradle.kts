plugins {
    id("com.android.application")
}

android {
    namespace = "com.messenger.little"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.messenger.little"
        // Android 4.4.x (KitKat, API 19) only.
        minSdk = 19
        targetSdk = 19
        maxSdk = 19
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
