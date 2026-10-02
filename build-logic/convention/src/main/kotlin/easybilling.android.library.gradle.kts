// Android library module.
plugins {
    id("com.android.library")
    id("easybilling.detekt")
}

android {
    compileSdk {
        version = release(ProjectConfig.COMPILE_SDK)
    }

    defaultConfig {
        minSdk = ProjectConfig.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = ProjectConfig.javaVersion
        targetCompatibility = ProjectConfig.javaVersion
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.jvmArgs(ProjectConfig.robolectricJvmArgs) }
        }
    }
}

dependencies {
    "testImplementation"(versionCatalog.library("junit"))
    "testImplementation"(versionCatalog.library("kotlin-test-junit"))
    "testImplementation"(versionCatalog.library("robolectric"))
    "testImplementation"(versionCatalog.library("androidx-test-core"))
    "testImplementation"(versionCatalog.library("androidx-test-ext-junit"))
    "androidTestImplementation"(versionCatalog.library("kotlin-test-junit"))
    "androidTestImplementation"(versionCatalog.library("androidx-test-runner"))
    "androidTestImplementation"(versionCatalog.library("androidx-test-ext-junit"))
}
