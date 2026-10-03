// EBMS client: DTOs, token handling, error classification. Pure Kotlin, testable without a device.
plugins {
    id("easybilling.kotlin.library")
}

dependencies {
    implementation(project(":domain"))
    // The HTTP client is part of the API: the application builds it once and injects it.
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":testing:obr-simulator"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.tls)
}
