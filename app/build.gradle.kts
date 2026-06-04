plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.copperhead.gateway"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.copperhead.gateway"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Pixel 7 Pro is arm64 — ship that primarily. armeabi/x86 included
            // so we don't get install errors on emulators or older devices.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cFlags += "-std=c99"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
        aidl = true
    }
}

// Copy Magisk module files into APK assets so the app can install them via root
tasks.register<Copy>("copyMagiskModule") {
    from("${rootProject.projectDir}/magisk") {
        include("module.prop")
        include("system/**")
        include("sepolicy.rule")
    }
    into("src/main/assets/magisk")
}

tasks.named("preBuild") {
    dependsOn("copyMagiskModule")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
