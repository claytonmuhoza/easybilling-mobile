// Jetpack Compose for an Android module. Apply after the application or library convention.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> {
        buildFeatures {
            compose = true
        }
    }
}

pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> {
        buildFeatures {
            compose = true
        }
    }
}

dependencies {
    "implementation"(platform(versionCatalog.library("androidx-compose-bom")))
    "implementation"(versionCatalog.library("androidx-compose-ui"))
    "implementation"(versionCatalog.library("androidx-compose-ui-graphics"))
    "implementation"(versionCatalog.library("androidx-compose-ui-tooling-preview"))
    "implementation"(versionCatalog.library("androidx-compose-material3"))
    "debugImplementation"(versionCatalog.library("androidx-compose-ui-tooling"))
    "debugImplementation"(versionCatalog.library("androidx-compose-ui-test-manifest"))
    "testImplementation"(platform(versionCatalog.library("androidx-compose-bom")))
    "testImplementation"(versionCatalog.library("androidx-compose-ui-test-junit4"))
    // Compose UI test brings an Espresso too old for the Android versions Robolectric emulates.
    "testImplementation"(versionCatalog.library("androidx-test-espresso-core"))
}
