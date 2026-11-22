plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksPath = System.getenv("NYUSHU_KEYSTORE_PATH")

android {
    namespace = "dev.nyushu.optimizer"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.nyushu.optimizer"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Release signing is used only when a keystore is supplied through the environment (CI secrets).
        if (ksPath != null) create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("NYUSHU_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("NYUSHU_KEY_ALIAS")
            keyPassword = System.getenv("NYUSHU_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a supplied keystore the release APK is signed with the debug key so it stays installable. Not for distribution.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
}
