// The Kotlin plugin is named here only to pick the Kotlin version; the Android plugin
// compiles Kotlin itself (built-in Kotlin, AGP 9).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
