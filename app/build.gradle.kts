import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Playlist URL lives only in local.properties (not in git):
//   iptv.playlistUrl=http://.../playlist.m3u8
//   iptv.epgUrl=            (optional, otherwise taken from the playlist header)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String) = (localProps.getProperty(name) ?: "").trim()

android {
    namespace = "com.zmeuk.zmiptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zmeuk.zmiptv"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.7"
        buildConfigField("String", "PLAYLIST_URL", "\"${localProp("iptv.playlistUrl")}\"")
        // Empty by default: the guide URL is then taken from the playlist header
        val epg = localProp("iptv.epgUrl")
        buildConfigField("String", "EPG_URL", "\"$epg\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Side-loaded only: sign release with the debug key so it installs as-is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
