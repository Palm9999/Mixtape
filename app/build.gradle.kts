plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.mixtape"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mixtape"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        val spotifyClientId = providers.gradleProperty("spotifyClientId").getOrElse("")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        manifestPlaceholders += mapOf(
            "redirectSchemeName" to "mixtape",
            "redirectHostName" to "callback",
            "redirectPathPattern" to ".*",
        )
    }

    // Committed on purpose (personal app) so the SHA-1 registered with Spotify and Google stays stable.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.room:room-runtime:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.work:work-runtime:2.10.5")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("sh.calvin.reorderable:reorderable:2.5.1")
    implementation("com.spotify.android:auth:3.1.0")
    // App Remote is not on Maven; the AAR is from github.com/spotify/android-sdk releases. It needs Gson.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.android.gms:play-services-auth:21.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814") // android.jar's org.json is a stub on the JVM
}
