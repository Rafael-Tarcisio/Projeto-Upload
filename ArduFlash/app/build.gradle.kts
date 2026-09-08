plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rafael.arduflash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rafael.arduflash"
        minSdk = 24        // OTG host precisa de API decente; 24+ cobre quase tudo
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Biblioteca que faz o trabalho pesado de USB Host + drivers CH340/FTDI/CDC
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
}
