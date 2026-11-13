plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "id.litevpn.slowdns"
    compileSdk = 35
    defaultConfig {
        applicationId = "id.litevpn.slowdns"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlinOptions {
    jvmTarget = "17"
}
