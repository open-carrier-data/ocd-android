import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.open_carrier_data.check"
    compileSdk = 36
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "io.github.open_carrier_data.check"
        minSdk = 29
        targetSdk = 36
        // Every build that goes onto a phone raises it (OlderAppTest): Android drops an adb-installed update of a
        // system app only for a system copy with a higher versionCode, so a ROM's newer copy of the same code
        // stays shadowed by the older update (the S20 on 2026-10-28: round 30's e5240ec ran instead of round 31's 64e559c).
        versionCode = 19
        versionName = "0.5.0"
    }

    // Round 46 and 47: one build and one package for everyone. Release stays unsigned: every ROM builder signs it with his
    // own key and sets CarrierSettings's writer certificate to it (SM5). Debug and release are the same code.
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Puts the merged manifest's path on the unit-test classpath (com/android/tools/test_config.properties) for ManifestTest.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // lifecycle 2.9's NonNullableMutableLiveDataDetector crashes AGP 8.7's lint; the app uses no LiveData.
        disable += "NullSafeMutableLiveData"
    }

    // No Google-signed dependency metadata block in the APK (F-Droid).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.work.runtime)

    testImplementation(libs.junit)
}
