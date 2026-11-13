plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shortsmaker.proof"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shortsmaker.proof"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }

    signingConfigs {
        // Fixed key committed to the repository so every new build installs OVER the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // required by NewPipe Extractor below Android 13
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }
    testOptions {
        animationsDisabled = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-common:1.6.1")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Open-source YouTube link reader used by the NewPipe app (GPL-3.0).
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
