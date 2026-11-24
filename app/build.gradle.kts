plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rantingpohon.tools"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rantingpohon.tools"
        minSdk = 24
        targetSdk = 35
        val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = ciRunNumber
        versionName = "1.0.$ciRunNumber"
    }

    signingConfigs {
        getByName("debug") {
            val ksFile = file("debug-consistent.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "rantingpohon"
                keyAlias = "rantingkey"
                keyPassword = "rantingpohon"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
