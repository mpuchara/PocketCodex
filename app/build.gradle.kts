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
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
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
