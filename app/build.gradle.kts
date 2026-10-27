plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.karaok.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.karaok.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed key, so every new build installs over the old one and keeps your songs and settings.
    signingConfigs {
        create("karaok") {
            storeFile = file("karaok.jks")
            storePassword = "karaok123"
            keyAlias = "karaok"
            keyPassword = "karaok123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("karaok")
        }
        debug {
            signingConfig = signingConfigs.getByName("karaok")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    androidResources {
        noCompress += listOf("sf2")
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("com.google.zxing:core:3.5.3")
}
