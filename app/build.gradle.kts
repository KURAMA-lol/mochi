plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.sahil.chibi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sahil.chibi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Embedded Python is a native component: one ABI keeps the APK small (all current phones are arm64).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Fixed signing key so every new build installs as an UPDATE over the old one
    // (CI would otherwise generate a different debug key each run).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Embedded Python (Chaquopy) for Agent-Reach. The agent_reach package itself is copied into
// src/main/python by CI (see .github/workflows/build.yml); only its dependencies are pip-installed here.
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("requests")
            install("loguru")
            install("pyyaml")
            install("yt-dlp[default]==2026.8.19")
            install("scrapy")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
