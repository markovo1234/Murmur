plugins {
    alias(libs.plugins.android.application) apply false
    // Declared (not applied) so AGP's built-in Kotlin support uses this KGP version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
