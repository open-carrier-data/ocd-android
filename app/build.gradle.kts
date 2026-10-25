import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The owner build's signing key lives outside the repository, in local.properties (round 25 OB1).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.open_carrier_data.check"
    compileSdk = 36
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "io.github.open_carrier_data.check"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
        // True only in the owner build type; debug and release stay read-only.
        buildConfigField("boolean", "OWNER", "false")
    }

    signingConfigs {
        val store = localProperties.getProperty("ocd.owner.storeFile")
        if (store != null) {
            create("owner") {
                storeFile = file(store)
                storePassword = localProperties.getProperty("ocd.owner.storePassword")
                keyAlias = localProperties.getProperty("ocd.owner.keyAlias")
                keyPassword = localProperties.getProperty("ocd.owner.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // The owner's own build (round 25 B, the owner direction of 2026-10-19): it tests and
        // applies carrier settings on the owner's S20 through Shizuku as root. Its code is in
        // src/owner only; debug and release get the no-op hook from src/readonly. Never a
        // release asset, a CI artifact or a store build; installed by adb only.
        create("owner") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".owner"
            versionNameSuffix = "-owner"
            buildConfigField("boolean", "OWNER", "true")
            // Its own key, never the debug key: without local.properties the APK stays unsigned.
            signingConfig = signingConfigs.findByName("owner")
            matchingFallbacks += "debug"
        }
    }

    sourceSets {
        getByName("debug").java.srcDir("src/readonly/java")
        getByName("release").java.srcDir("src/readonly/java")
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

    "ownerImplementation"(libs.work.runtime)

    testImplementation(libs.junit)
}

// Round 25 OB1: the owner APK is never a CI artifact. Only the tasks that produce or sign an
// owner APK or bundle are blocked; packageOwnerResources and the other resource steps the owner
// unit tests need still run.
tasks.matching { task ->
    task.name in setOf("assembleOwner", "packageOwner", "bundleOwner", "signOwnerBundle", "packageOwnerBundle")
}.configureEach {
    doFirst {
        if (System.getenv("CI") != null) throw GradleException("The owner build is never built in CI (round 25 OB1).")
    }
}
