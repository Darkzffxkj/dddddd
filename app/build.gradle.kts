plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.quiet.keymapper"
    compileSdk = 35
    buildFeatures { aidl = true }
    defaultConfig {
        applicationId = "com.quiet.keymapper"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }
    packaging {
        resources {
            excludes += "META-INF/versions/**"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.RSA"
            excludes += "META-INF/*.DSA"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.flyfishxu:kadb:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
