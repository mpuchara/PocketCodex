plugins {
    id("com.android.application")
}

android {
    namespace = "pl.homemind.pocketcodex"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.homemind.pocketcodex"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }

    signingConfigs {
        create("pocketcodexDebug") {
            storeFile = file("pocketcodex-debug.keystore")
            storePassword = "pocketcodex"
            keyAlias = "pocketcodex"
            keyPassword = "pocketcodex"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pocketcodexDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
