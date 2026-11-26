import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// App-level API credentials live in local.properties (gitignored), never in the repo.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun appSecret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

// Release build version can be overridden for local test builds:
//   .\gradlew assembleRelease -PkoverVersionName=0.1.0 -PkoverVersionCode=1
val koverVersionName = (project.findProperty("koverVersionName") as String?) ?: "0.3.0"
val koverVersionCode = (project.findProperty("koverVersionCode") as String?)?.toIntOrNull() ?: 6

val releaseStoreFile = localProps.getProperty("KOVER_STORE_FILE")
    ?: System.getenv("KOVER_STORE_FILE")

android {
    namespace = "com.ark.kover"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ark.kover"
        minSdk = 30
        targetSdk = 34
        versionCode = koverVersionCode
        versionName = koverVersionName

        buildConfigField("String", "NEOASSETS_CLIENT_ID", "\"${appSecret("NEOASSETS_CLIENT_ID")}\"")
        buildConfigField("String", "NEOASSETS_CLIENT_SECRET", "\"${appSecret("NEOASSETS_CLIENT_SECRET")}\"")
        buildConfigField("String", "SCREENSCRAPER_DEV_ID", "\"${appSecret("SCREENSCRAPER_DEV_ID")}\"")
        buildConfigField("String", "SCREENSCRAPER_DEV_PASSWORD", "\"${appSecret("SCREENSCRAPER_DEV_PASSWORD")}\"")
    }

    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty("KOVER_STORE_PASSWORD")
                    ?: System.getenv("KOVER_STORE_PASSWORD")
                keyAlias = localProps.getProperty("KOVER_KEY_ALIAS") ?: "kover"
                keyPassword = localProps.getProperty("KOVER_KEY_PASSWORD")
                    ?: System.getenv("KOVER_KEY_PASSWORD")
            }
        }
    }

    val releaseSigning = signingConfigs.findByName("release")

    buildTypes {
        release {
            if (releaseSigning != null) signingConfig = releaseSigning
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Retrofit (HTTP)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Room (database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // WorkManager (background tasks)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Hilt (DI)
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room compiler
    ksp("androidx.room:room-compiler:2.6.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
