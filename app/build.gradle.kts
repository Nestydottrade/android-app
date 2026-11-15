plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "trade.nesty.app"
    compileSdk = 36

    defaultConfig {
        // Confirmed by the user on 2026-11-13. It cannot change after the first release.
        applicationId = "trade.nesty.app"
        minSdk = 26
        targetSdk = 36
        // Raised for each release. A test build may raise them on the command line instead
        // (-Pnesty.versionCode=2 -Pnesty.versionName=0.1.1), to try the updater in the emulator.
        versionCode = providers.gradleProperty("nesty.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("nesty.versionName").orNull ?: "0.1.0"
    }

    signingConfigs {
        // The user's own release key, named by the build's environment only (release/build.sh
        // asks for it). It is never in this repository, on the server or with Claude.
        val store = providers.environmentVariable("NESTY_KEYSTORE").orNull
        if (!store.isNullOrBlank()) {
            create("release") {
                storeFile = file(store)
                storePassword = providers.environmentVariable("NESTY_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("NESTY_KEY_ALIAS").orNull ?: "nesty"
                keyPassword = providers.environmentVariable("NESTY_KEY_PASSWORD").orNull
                    ?: providers.environmentVariable("NESTY_KEYSTORE_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the user's key when the build names it. Without it, Gradle makes an
            // unsigned APK, except for a test of the shrunk build in the emulator, which may use
            // this computer's debug key (-Pnesty.testSigning=debug): never one to publish.
            signingConfig = signingConfigs.findByName("release")
                ?: if (providers.gradleProperty("nesty.testSigning").orNull == "debug") signingConfigs.getByName("debug") else null
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
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Key derivation and the vault: secp256k1, Keccak, Ed25519, PBKDF2 and Argon2id. Used through
    // its own classes only; it is never registered as a security provider.
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
