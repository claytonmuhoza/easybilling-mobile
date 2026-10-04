// Screens (one package per feature), navigation and dependency injection.
plugins {
    id("easybilling.android.application")
    id("easybilling.android.compose")
    id("easybilling.android.screenshot")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The version name has a single source, gradle.properties. The continuous integration passes the
// rest: -Papp.versionCode=<run number>, and -Papp.snapshot=<run number> for a build of develop.
val appVersionName = providers.gradleProperty("app.versionName").get()
val appVersionCode = providers.gradleProperty("app.versionCode").map(String::toInt).getOrElse(1)
val snapshotNumber = providers.gradleProperty("app.snapshot").orNull

// The release keystore never lives in the repository: the continuous integration restores it from
// its secrets and points to it with these variables. Without them the release APK is left unsigned.
val releaseKeystore = providers.environmentVariable("EASYBILLING_KEYSTORE_FILE").orNull

android {
    namespace = "com.claytonmuhoza.easybilling"

    defaultConfig {
        applicationId = "com.claytonmuhoza.easybilling"
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "EasyBilling"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("EASYBILLING_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("EASYBILLING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("EASYBILLING_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // The whole application is optimized: limiting R8 to a few packages produced an APK
            // that crashed at startup (classes renamed on one side of the limit only).
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.findByName("release")
            if (snapshotNumber != null) {
                // A snapshot installs next to the stable application and keeps its own data.
                applicationIdSuffix = ".snapshot"
                versionNameSuffix = "-snapshot.$snapshotNumber"
                manifestPlaceholders["appLabel"] = "EasyBilling Snapshot"
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
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.hilt.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.hilt.android.testing)
    testImplementation(project(":testing:obr-simulator"))
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.work.testing)
    kspTest(libs.hilt.compiler)
}
