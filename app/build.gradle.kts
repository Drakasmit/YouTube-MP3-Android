plugins {
    id("com.android.application")
}

android {
    namespace = "com.drakasmit.youtubemp3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drakasmit.youtubemp3"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.ffmpegkit-maintained:yt-dlp-android:2.0.2")
}
