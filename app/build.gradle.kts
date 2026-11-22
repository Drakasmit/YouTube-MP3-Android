plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drakasmit.youtubemp3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drakasmit.youtubemp3"
        minSdk = 24
        targetSdk = 28
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("dev.ffmpegkit-maintained:yt-dlp-android:2.0.2")
}
