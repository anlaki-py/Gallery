plugins {
    // Align AGP's built-in Kotlin compiler with LiteRT-LM's Kotlin 2.4 metadata.
    alias(libs.plugins.kotlin.android).apply(false)
    alias(libs.plugins.android).apply(false)
    alias(libs.plugins.ksp).apply(false)
    alias(libs.plugins.detekt).apply(false)
}
