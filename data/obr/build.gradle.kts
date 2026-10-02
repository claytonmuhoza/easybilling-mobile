// EBMS client: DTOs, token handling, error classification. Pure Kotlin, testable without a device.
plugins {
    id("easybilling.kotlin.library")
}

dependencies {
    implementation(project(":domain"))

    testImplementation(project(":testing:obr-simulator"))
}
