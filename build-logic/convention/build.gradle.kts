plugins {
    `kotlin-dsl`
}

// The plugins are only needed to compile the convention plugins. The root build file loads them
// (with `apply false`) so every module shares the same plugin classloader.
dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    compileOnly(libs.detekt.gradle.plugin)
    compileOnly(libs.roborazzi.gradle.plugin)
}
