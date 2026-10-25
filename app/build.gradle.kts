import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.arivna.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arivna.browser"
        minSdk = 26
        targetSdk = 36
        // Play rejects an upload whose versionCode is not higher than the last one: bump it on every release.
        versionCode = 2
        versionName = "1.0.1"
        // x86 (32-bit) is emulator-only; the Rust engine is built for these three.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // keystore.properties (git-ignored) holds the Play *upload* key; without it release builds fall back to the debug key.
    val keystoreProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    signingConfigs {
        if (!keystoreProps.isEmpty) create("upload") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The upload key signs only the bundle Play receives; local APK installs keep the debug key so they update the app already on the phone.
            val forPlay = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
            if (forPlay && signingConfigs.findByName("upload") == null) throw GradleException("keystore.properties is missing: the Play bundle cannot be signed")
            signingConfig = signingConfigs.getByName(if (forPlay) "upload" else "debug")
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
    }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
