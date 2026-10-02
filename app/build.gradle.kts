// Screens (one package per feature), navigation and dependency injection.
plugins {
    id("easybilling.android.application")
    id("easybilling.android.compose")
    id("easybilling.android.screenshot")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.claytonmuhoza.easybilling"

    defaultConfig {
        applicationId = "com.claytonmuhoza.easybilling"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data:obr"))
    implementation(project(":data:local"))
    implementation(project(":platform"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
