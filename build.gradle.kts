// Top-level build file; the app module lives in app/.
plugins {
    alias(libs.plugins.android.application) apply false
    // Declared so the Kotlin Gradle plugin matches the Compose compiler plugin version
    // (AGP 9 compiles Kotlin itself; the app module does not apply this plugin).
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
