plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace "net.typeblob.socks"
    compileSdk 34

    defaultConfig {
        applicationId "net.typeblob.socks"
        minSdk 21
        targetSdk 34
        versionCode 1
        versionName "1.0"

        // Ensure we build JNI bridge for all ABIs you ship .so for
        ndk {
            abiFilters "arm64-v8a", "armeabi-v7a", "x86", "x86_64"
        }
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            // You can keep defaults here
        }
    }

    // Wire up JNI bridge build (native_runner.cpp via CMake)
    externalNativeBuild {
        cmake {
            path file("src/main/cpp/CMakeLists.txt")
        }
    }

    // Java/Kotlin toolchains
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }

    // Packaging options for native libs; keep defaults unless you have conflicts
    packagingOptions {
        // Do not set extractNativeLibs here; remove it from the manifest per AGP guidance
        jniLibs {
            useLegacyPackaging false
        }
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

    // Add any other dependencies your project already uses below
    // implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.6.2'
    // implementation 'androidx.activity:activity-ktx:1.8.2'
}