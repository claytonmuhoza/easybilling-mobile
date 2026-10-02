// Pure Kotlin module: no Android dependency allowed.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("easybilling.detekt")
}

java {
    sourceCompatibility = ProjectConfig.javaVersion
    targetCompatibility = ProjectConfig.javaVersion
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(ProjectConfig.javaVersion.toString()))
    }
}

dependencies {
    "testImplementation"(versionCatalog.library("junit"))
    "testImplementation"(versionCatalog.library("kotlin-test-junit"))
}
