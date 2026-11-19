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
        versionCode = providers.gradleProperty("nesty.versionCode").orNull?.toInt() ?: 8
        versionName = providers.gradleProperty("nesty.versionName").orNull ?: "0.1.7"
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

    // The libraries' own texts in the app's languages only (I18n.kt's Lang), not in some 80.
    androidResources {
        localeFilters += listOf("en", "in", "de", "es", "pt", "pt-rBR", "vi", "ru", "hi", "ja", "zh-rCN", "ko")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * The translations, kept as src/main/i18n/<tag>.json (each text's English to its translation),
 * packed for the app: i18n/keys.txt lists every English text once, and i18n/<tag>.txt has each
 * language's lines in the same order, empty where a text is not translated. The APK stores them
 * compressed, and the app reads only the language in use.
 */
abstract class PackI18n : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun pack() {
        val out = output.get().asFile.resolve("i18n")
        out.deleteRecursively()
        out.mkdirs()
        val tables = source.get().asFile.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.associate { f ->
            @Suppress("UNCHECKED_CAST")
            f.nameWithoutExtension to (groovy.json.JsonSlurper().parse(f, "UTF-8") as Map<String, String>)
        }
        val keys = tables.values.flatMap { it.keys }.toSortedSet().toList()
        fun line(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")
        out.resolve("keys.txt").writeText(keys.joinToString("\n") { line(it) }, Charsets.UTF_8)
        for ((tag, table) in tables) {
            out.resolve("$tag.txt").writeText(keys.joinToString("\n") { line(table[it].orEmpty()) }, Charsets.UTF_8)
        }
    }
}

val packI18n = tasks.register<PackI18n>("packI18n") {
    source.set(layout.projectDirectory.dir("src/main/i18n"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(packI18n, PackI18n::output) }
}

// A test run with -Pnesty.pseudo=1 shows every text in brackets (I18n.kt's PseudoTexts).
tasks.withType<Test>().configureEach {
    providers.gradleProperty("nesty.pseudo").orNull?.let { systemProperty("nesty.pseudo", it) }
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
