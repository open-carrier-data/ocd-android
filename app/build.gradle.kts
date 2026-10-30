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
        // Every build that goes onto a phone raises it (OlderAppTest): Android drops an adb-installed update of a
        // system app only for a system copy with a higher versionCode, so a ROM's newer copy of the same code
        // stays shadowed by the older update (the S20 on 2026-10-28: round 30's e5240ec ran instead of round 31's 64e559c).
        versionCode = 8
        versionName = "0.4.4"
        // True only in the owner build type; debug and release stay read-only.
        buildConfigField("boolean", "OWNER", "false")
        // Round 31: which tester build this is: "" (read-only), "owner", or "rom" (the build other ROMs ship).
        buildConfigField("String", "TESTER", "\"\"")
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
            buildConfigField("String", "TESTER", "\"owner\"")
            // Its own key, never the debug key: without local.properties the APK stays unsigned.
            signingConfig = signingConfigs.findByName("owner")
            matchingFallbacks += "debug"
        }
        // OCD round 31 (scope, SM1 to SM12): the build another ROM ships in its own image. The same
        // tester code as the owner build (src/owner), with the person's opt-in for the active tests
        // (off by default), calls and SMS only where the allowlist marks them free, no token and no
        // automatic report; results stay on the phone. Unsigned here: the ROM builder signs it with
        // his own key and sets CarrierSettings's writer certificate to it (SM5). Never the owner key.
        // Its package is the read-only app's (io.github.open_carrier_data.check), which a ROM ships instead of it; it is
        // CarrierSettings's OCD_WRITER_PACKAGE choice beside the owner's (spec R31.11).
        create("rom") {
            initWith(getByName("release"))
            versionNameSuffix = "-rom"
            buildConfigField("boolean", "OWNER", "false")
            buildConfigField("String", "TESTER", "\"rom\"")
            signingConfig = null
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        getByName("debug").java.srcDir("src/readonly/java")
        getByName("release").java.srcDir("src/readonly/java")
        // The ROM build runs the owner build's tester code under the ROM build's policy (owner/core/Tester.kt).
        getByName("rom") {
            java.srcDir("src/owner/java")
            res.srcDir("src/owner/res")
            assets.srcDir("src/owner/assets")
            manifest.srcFile("src/owner/AndroidManifest.xml")
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

    "ownerImplementation"(libs.work.runtime)
    "romImplementation"(libs.work.runtime)

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
